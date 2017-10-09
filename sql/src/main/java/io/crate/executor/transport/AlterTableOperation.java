/*
 * Licensed to Crate under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.  Crate licenses this file
 * to you under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial
 * agreement.
 */

package io.crate.executor.transport;

import com.carrotsearch.hppc.cursors.ObjectObjectCursor;
import com.google.common.annotations.VisibleForTesting;
import io.crate.Constants;
import io.crate.action.FutureActionListener;
import io.crate.action.sql.ResultReceiver;
import io.crate.action.sql.SQLOperations;
import io.crate.analyze.AddColumnAnalyzedStatement;
import io.crate.analyze.AlterTableAnalyzedStatement;
import io.crate.analyze.AlterTableAnalyzer;
import io.crate.analyze.AlterTableOpenCloseAnalyzedStatement;
import io.crate.analyze.AlterTableRenameAnalyzedStatement;
import io.crate.analyze.PartitionedTableParameterInfo;
import io.crate.analyze.RerouteAllocateReplicaShardAnalyzedStatement;
import io.crate.analyze.RerouteAnalyzedStatement;
import io.crate.analyze.RerouteCancelShardAnalyzedStatement;
import io.crate.analyze.RerouteMoveShardAnalyzedStatement;
import io.crate.analyze.TableParameter;
import io.crate.analyze.expressions.ExpressionToNumberVisitor;
import io.crate.analyze.expressions.ExpressionToObjectVisitor;
import io.crate.analyze.expressions.ExpressionToStringVisitor;
import io.crate.concurrent.CompletableFutures;
import io.crate.concurrent.MultiBiConsumer;
import io.crate.data.Row;
import io.crate.exceptions.AlterTableAliasException;
import io.crate.executor.transport.ddl.OpenCloseTableOrPartitionRequest;
import io.crate.executor.transport.ddl.OpenCloseTableOrPartitionResponse;
import io.crate.executor.transport.ddl.RenameTableRequest;
import io.crate.executor.transport.ddl.RenameTableResponse;
import io.crate.executor.transport.ddl.TransportOpenCloseTableOrPartitionAction;
import io.crate.executor.transport.ddl.TransportRenameTableAction;
import io.crate.metadata.IndexParts;
import io.crate.metadata.PartitionName;
import io.crate.metadata.TableIdent;
import io.crate.metadata.doc.DocTableInfo;
import io.crate.metadata.table.ShardedTable;
import io.crate.sql.tree.GenericProperties;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.ElasticsearchParseException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.admin.cluster.reroute.ClusterRerouteRequest;
import org.elasticsearch.action.admin.cluster.reroute.ClusterRerouteResponse;
import org.elasticsearch.action.admin.cluster.reroute.TransportClusterRerouteAction;
import org.elasticsearch.action.admin.indices.alias.Alias;
import org.elasticsearch.action.admin.indices.close.CloseIndexRequest;
import org.elasticsearch.action.admin.indices.close.CloseIndexResponse;
import org.elasticsearch.action.admin.indices.close.TransportCloseIndexAction;
import org.elasticsearch.action.admin.indices.mapping.put.PutMappingRequest;
import org.elasticsearch.action.admin.indices.mapping.put.PutMappingResponse;
import org.elasticsearch.action.admin.indices.mapping.put.TransportPutMappingAction;
import org.elasticsearch.action.admin.indices.open.OpenIndexRequest;
import org.elasticsearch.action.admin.indices.open.OpenIndexResponse;
import org.elasticsearch.action.admin.indices.open.TransportOpenIndexAction;
import org.elasticsearch.action.admin.indices.settings.put.TransportUpdateSettingsAction;
import org.elasticsearch.action.admin.indices.settings.put.UpdateSettingsRequest;
import org.elasticsearch.action.admin.indices.settings.put.UpdateSettingsResponse;
import org.elasticsearch.action.admin.indices.template.put.PutIndexTemplateRequest;
import org.elasticsearch.action.admin.indices.template.put.PutIndexTemplateResponse;
import org.elasticsearch.action.admin.indices.template.put.TransportPutIndexTemplateAction;
import org.elasticsearch.action.support.IndicesOptions;
import org.elasticsearch.cluster.metadata.AliasMetaData;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.metadata.IndexTemplateMetaData;
import org.elasticsearch.cluster.metadata.MetaData;
import org.elasticsearch.cluster.routing.allocation.command.AllocateReplicaAllocationCommand;
import org.elasticsearch.cluster.routing.allocation.command.AllocationCommand;
import org.elasticsearch.cluster.routing.allocation.command.CancelAllocationCommand;
import org.elasticsearch.cluster.routing.allocation.command.MoveAllocationCommand;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.compress.CompressedXContent;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.inject.Singleton;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.NamedXContentRegistry;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.common.xcontent.XContentParser;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

@Singleton
public class AlterTableOperation {

    private final ClusterService clusterService;
    private final TransportPutIndexTemplateAction transportPutIndexTemplateAction;
    private final TransportPutMappingAction transportPutMappingAction;
    private final TransportUpdateSettingsAction transportUpdateSettingsAction;
    private final TransportOpenIndexAction transportOpenIndexAction;
    private final TransportCloseIndexAction transportCloseIndexAction;
    private final TransportRenameTableAction transportRenameTableAction;
    private final TransportOpenCloseTableOrPartitionAction transportOpenCloseTableOrPartitionAction;
    private final TransportClusterRerouteAction transportClusterRerouteAction;
    private final SQLOperations sqlOperations;

    @Inject
    public AlterTableOperation(ClusterService clusterService,
                               TransportPutIndexTemplateAction transportPutIndexTemplateAction,
                               TransportPutMappingAction transportPutMappingAction,
                               TransportUpdateSettingsAction transportUpdateSettingsAction,
                               TransportOpenIndexAction transportOpenIndexAction,
                               TransportCloseIndexAction transportCloseIndexAction,
                               TransportRenameTableAction transportRenameTableAction,
                               TransportOpenCloseTableOrPartitionAction transportOpenCloseTableOrPartitionAction,
                               TransportClusterRerouteAction transportClusterRerouteAction,
                               SQLOperations sqlOperations) {
        this.clusterService = clusterService;
        this.transportPutIndexTemplateAction = transportPutIndexTemplateAction;
        this.transportPutMappingAction = transportPutMappingAction;
        this.transportUpdateSettingsAction = transportUpdateSettingsAction;
        this.transportOpenIndexAction = transportOpenIndexAction;
        this.transportCloseIndexAction = transportCloseIndexAction;
        this.transportRenameTableAction = transportRenameTableAction;
        this.transportOpenCloseTableOrPartitionAction = transportOpenCloseTableOrPartitionAction;
        this.transportClusterRerouteAction = transportClusterRerouteAction;
        this.sqlOperations = sqlOperations;
    }

    public CompletableFuture<Long> executeAlterTableAddColumn(final AddColumnAnalyzedStatement analysis) {
        final CompletableFuture<Long> result = new CompletableFuture<>();
        if (analysis.newPrimaryKeys() || analysis.hasNewGeneratedColumns()) {
            TableIdent ident = analysis.table().ident();
            String stmt =
                String.format(Locale.ENGLISH, "SELECT COUNT(*) FROM \"%s\".\"%s\"", ident.schema(), ident.name());

            SQLOperations.SQLDirectExecutor sqlDirectExecutor = sqlOperations.createSystemExecutor(
                null,
                SQLOperations.Session.UNNAMED,
                stmt,
                1);
            try {
                sqlDirectExecutor.execute(new ResultSetReceiver(analysis, result), Collections.emptyList());
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        } else {
            addColumnToTable(analysis, result);
        }
        return result;
    }

    public CompletableFuture<Long> executeAlterTableOpenClose(final AlterTableOpenCloseAnalyzedStatement analysis) {
        FutureActionListener<OpenCloseTableOrPartitionResponse, Long> listener = new FutureActionListener<>(r -> -1L);
        String partitionIndexName = null;
        Optional<PartitionName> partitionName = analysis.partitionName();
        if (partitionName.isPresent()) {
            partitionIndexName = partitionName.get().asIndexName();
        }
        OpenCloseTableOrPartitionRequest request = new OpenCloseTableOrPartitionRequest(
            analysis.tableInfo().ident(), partitionIndexName, analysis.openTable());
        transportOpenCloseTableOrPartitionAction.execute(request, listener);
        return listener;
    }

    private CompletableFuture<Long> openTable(String... indices) {
        FutureActionListener<OpenIndexResponse, Long> listener = new FutureActionListener<>(r -> -1L);
        OpenIndexRequest request = new OpenIndexRequest(indices);
        transportOpenIndexAction.execute(request, listener);
        return listener;
    }

    private CompletableFuture<Long> closeTable(String... indices) {
        FutureActionListener<CloseIndexResponse, Long> listener = new FutureActionListener<>(r -> -1L);
        CloseIndexRequest request = new CloseIndexRequest(indices);
        transportCloseIndexAction.execute(request, listener);
        return listener;
    }

    public CompletableFuture<Long> executeAlterTable(AlterTableAnalyzedStatement analysis) {
        DocTableInfo table = analysis.table();
        if (table.isAlias() && !table.isPartitioned()) {
            return CompletableFutures.failedFuture(new AlterTableAliasException(table.ident()));
        }

        List<CompletableFuture<Long>> results = new ArrayList<>(3);

        if (table.isPartitioned()) {
            // create new filtered partition table settings
            PartitionedTableParameterInfo tableSettingsInfo =
                (PartitionedTableParameterInfo) table.tableParameterInfo();
            TableParameter parameterWithFilteredSettings = new TableParameter(
                analysis.tableParameter().settings(),
                tableSettingsInfo.partitionTableSettingsInfo().supportedInternalSettings());

            Optional<PartitionName> partitionName = analysis.partitionName();
            if (partitionName.isPresent()) {
                String index = partitionName.get().asIndexName();
                results.add(updateMapping(analysis.tableParameter().mappings(), index));
                results.add(updateSettings(parameterWithFilteredSettings, index));
            } else {
                // template gets all changes unfiltered
                results.add(updateTemplate(analysis.tableParameter(), table.ident()));

                if (!analysis.excludePartitions()) {
                    String[] indices = Stream.of(table.concreteIndices()).toArray(String[]::new);
                    results.add(updateMapping(analysis.tableParameter().mappings(), indices));
                    results.add(updateSettings(parameterWithFilteredSettings, indices));
                }
            }
        } else {
            results.add(updateMapping(analysis.tableParameter().mappings(), table.ident().indexName()));
            results.add(updateSettings(analysis.tableParameter(), table.ident().indexName()));
        }

        final CompletableFuture<Long> result = new CompletableFuture<>();
        applyMultiFutureCallback(result, results);
        return result;
    }

    private CompletableFuture<Long> updateOpenCloseOnPartitionTemplate(boolean openTable, TableIdent tableIdent) {
        Map metaMap = Collections.singletonMap("_meta", Collections.singletonMap("closed", true));
        if (openTable) {
            //Remove the mapping from the template.
            return updateTemplate(Collections.emptyMap(), metaMap, Settings.EMPTY, tableIdent);
        } else {
            //Otherwise, add the mapping to the template.
            return updateTemplate(metaMap, Settings.EMPTY, tableIdent);
        }
    }

    public CompletableFuture<Long> executeAlterTableRenameTable(AlterTableRenameAnalyzedStatement statement) {
        DocTableInfo sourceTableInfo = statement.sourceTableInfo();
        TableIdent sourceTableIdent = sourceTableInfo.ident();
        TableIdent targetTableIdent = statement.targetTableIdent();

        if (sourceTableInfo.isPartitioned()) {
            return renamePartitionedTable(sourceTableInfo, targetTableIdent);
        }

        String[] sourceIndices = new String[]{sourceTableIdent.indexName()};
        String[] targetIndices = new String[]{targetTableIdent.indexName()};

        List<ChainableAction<Long>> actions = new ArrayList<>(3);

        if (sourceTableInfo.isClosed() == false) {
            actions.add(new ChainableAction<>(
                () -> closeTable(sourceIndices),
                () -> openTable(sourceIndices)
            ));
        }
        actions.add(new ChainableAction<>(
            () -> renameTable(sourceTableIdent, targetTableIdent, false),
            () -> renameTable(targetTableIdent, sourceTableIdent, false)
        ));
        if (sourceTableInfo.isClosed() == false) {
            actions.add(new ChainableAction<>(
                () -> openTable(targetIndices),
                () -> CompletableFuture.completedFuture(-1L)
            ));
        }
        return ChainableActions.run(actions);
    }

    private CompletableFuture<Long> renamePartitionedTable(DocTableInfo sourceTableInfo, TableIdent targetTableIdent) {
        boolean completeTableIsClosed = sourceTableInfo.isClosed();
        TableIdent sourceTableIdent = sourceTableInfo.ident();
        String[] sourceIndices = sourceTableInfo.concreteIndices();
        String[] targetIndices = new String[sourceIndices.length];
        // only close/open open partitions
        List<String> sourceIndicesToClose = new ArrayList<>(sourceIndices.length);
        List<String> targetIndicesToOpen = new ArrayList<>(sourceIndices.length);

        MetaData metaData = clusterService.state().metaData();
        for (int i = 0; i < sourceIndices.length; i++) {
            PartitionName partitionName = PartitionName.fromIndexOrTemplate(sourceIndices[i]);
            String sourceIndexName = partitionName.asIndexName();
            String targetIndexName = IndexParts.toIndexName(targetTableIdent, partitionName.ident());
            targetIndices[i] = targetIndexName;
            if (metaData.index(sourceIndexName).getState() == IndexMetaData.State.OPEN) {
                sourceIndicesToClose.add(sourceIndexName);
                targetIndicesToOpen.add(targetIndexName);
            }
        }
        String[] sourceIndicesToCloseArray = sourceIndicesToClose.toArray(new String[0]);
        String[] targetIndicesToOpenArray = targetIndicesToOpen.toArray(new String[0]);

        List<ChainableAction<Long>> actions = new ArrayList<>(7);

        if (completeTableIsClosed == false) {
            actions.add(new ChainableAction<>(
                () -> updateOpenCloseOnPartitionTemplate(false, sourceTableIdent),
                () -> updateOpenCloseOnPartitionTemplate(true, sourceTableIdent)
            ));
            if (sourceIndicesToCloseArray.length > 0) {
                actions.add(new ChainableAction<>(
                    () -> closeTable(sourceIndicesToCloseArray),
                    () -> openTable(sourceIndicesToCloseArray)
                ));
            }
        }

        actions.add(new ChainableAction<>(
            () -> renameTable(sourceTableIdent, targetTableIdent, true),
            () -> renameTable(targetTableIdent, sourceTableIdent, true)
        ));

        if (completeTableIsClosed == false) {
            actions.add(new ChainableAction<>(
                () -> updateOpenCloseOnPartitionTemplate(true, targetTableIdent),
                () -> updateOpenCloseOnPartitionTemplate(false, targetTableIdent)
            ));

            if (targetIndicesToOpenArray.length > 0) {
                actions.add(new ChainableAction<>(
                    () -> openTable(targetIndicesToOpenArray),
                    () -> CompletableFuture.completedFuture(-1L)
                ));
            }
        }

        return ChainableActions.run(actions);
    }

    private CompletableFuture<Long> renameTable(TableIdent sourceTableIdent,
                                                TableIdent targetTableIdent,
                                                boolean isPartitioned) {
        RenameTableRequest request = new RenameTableRequest(sourceTableIdent, targetTableIdent, isPartitioned);
        FutureActionListener<RenameTableResponse, Long> listener = new FutureActionListener<>(r -> -1L);
        transportRenameTableAction.execute(request, listener);
        return listener;
    }


    private CompletableFuture<Long> updateTemplate(TableParameter tableParameter, TableIdent tableIdent) {
        return updateTemplate(tableParameter.mappings(), tableParameter.settings(), tableIdent);
    }

    private CompletableFuture<Long> updateTemplate(Map<String, Object> newMappings,
                                                   Settings newSettings,
                                                   TableIdent tableIdent) {
        return updateTemplate(newMappings, Collections.emptyMap(), newSettings, tableIdent);
    }

    private CompletableFuture<Long> updateTemplate(Map<String, Object> newMappings,
                                                   Map<String, Object> mappingsToRemove,
                                                   Settings newSettings,
                                                   TableIdent tableIdent) {
        String templateName = PartitionName.templateName(tableIdent.schema(), tableIdent.name());
        IndexTemplateMetaData indexTemplateMetaData =
            clusterService.state().metaData().templates().get(templateName);
        if (indexTemplateMetaData == null) {
            return CompletableFutures.failedFuture(new RuntimeException("Template '" + templateName + "' for partitioned table is missing"));
        }

        // merge mappings
        Map<String, Object> mapping = mergeTemplateMapping(indexTemplateMetaData, newMappings);

        // remove mappings
        mapping = removeFromMapping(mapping, mappingsToRemove);

        // merge settings
        Settings.Builder settingsBuilder = Settings.builder();
        settingsBuilder.put(indexTemplateMetaData.settings());
        settingsBuilder.put(newSettings);

        PutIndexTemplateRequest request = new PutIndexTemplateRequest(templateName)
            .create(false)
            .mapping(Constants.DEFAULT_MAPPING_TYPE, mapping)
            .order(indexTemplateMetaData.order())
            .settings(settingsBuilder.build())
            .template(indexTemplateMetaData.template())
            .alias(new Alias(tableIdent.indexName()));
        for (ObjectObjectCursor<String, AliasMetaData> container : indexTemplateMetaData.aliases()) {
            Alias alias = new Alias(container.key);
            request.alias(alias);
        }

        FutureActionListener<PutIndexTemplateResponse, Long> listener = new FutureActionListener<>(r -> -1L);
        transportPutIndexTemplateAction.execute(request, listener);
        return listener;
    }

    /**
     It is important to add the _meta field explicitly to the changed mapping here since ES updates
     the mapping and removes/overwrites the _meta field.
     Tested with PartitionedTableIntegrationTest#testAlterPartitionedTableKeepsMetadata()
     */
    @VisibleForTesting
    static PutMappingRequest preparePutMappingRequest(Map<String, Object> oldMapping, Map<String, Object> newMapping, String... indices) {

        // Only merge the _meta
        XContentHelper.update(oldMapping, newMapping, false);
        newMapping.put("_meta", oldMapping.get("_meta"));

        // update mapping of all indices
        PutMappingRequest request = new PutMappingRequest(indices);
        request.indicesOptions(IndicesOptions.lenientExpandOpen());
        request.type(Constants.DEFAULT_MAPPING_TYPE);
        request.source(newMapping);
        return request;
    }

    private CompletableFuture<Long> updateMapping(Map<String, Object> newMapping, String... indices) {
        if (newMapping.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        assert areAllMappingsEqual(clusterService.state().metaData(), indices) :
            "Trying to update mapping for indices with different existing mappings";

        Map<String, Object> mapping;
        try {
            MetaData metaData = clusterService.state().metaData();
            String index = indices[0];
            mapping = metaData.index(index).mapping(Constants.DEFAULT_MAPPING_TYPE).getSourceAsMap();
        } catch (ElasticsearchParseException e) {
            return CompletableFutures.failedFuture(e);
        }

        FutureActionListener<PutMappingResponse, Long> listener = new FutureActionListener<>(r -> 0L);
        transportPutMappingAction.execute(preparePutMappingRequest(mapping, newMapping, indices), listener);
        return listener;
    }

    public static Map<String, Object> parseMapping(String mappingSource) throws IOException {
        try (XContentParser parser = XContentFactory.xContent(mappingSource).createParser(NamedXContentRegistry.EMPTY, mappingSource)) {
            return parser.map();
        } catch (IOException e) {
            throw new ElasticsearchException("failed to parse mapping");
        }
    }

    public static Map<String, Object> mergeTemplateMapping(IndexTemplateMetaData templateMetaData,
                                                            Map<String, Object> newMapping) {
        Map<String, Object> mergedMapping = new HashMap<>();
        for (ObjectObjectCursor<String, CompressedXContent> cursor : templateMetaData.mappings()) {
            try {
                Map<String, Object> mapping = parseMapping(cursor.value.toString());
                Object o = mapping.get(Constants.DEFAULT_MAPPING_TYPE);
                assert o != null && o instanceof Map :
                    "o must not be null and must be instance of Map";

                XContentHelper.update(mergedMapping, (Map) o, false);
            } catch (IOException e) {
                // pass
            }
        }
        XContentHelper.update(mergedMapping, newMapping, false);
        return mergedMapping;
    }

    public static Map<String, Object> removeFromMapping(Map<String, Object> mapping, Map<String, Object> mappingsToRemove) {
        for (String key : mappingsToRemove.keySet()) {
            if (mapping.containsKey(key)) {
                if (mapping.get(key) instanceof Map) {
                    mapping.put(key, removeFromMapping((Map<String, Object>) mapping.get(key),
                                                 (Map<String, Object>) mappingsToRemove.get(key)));
                } else {
                    mapping.remove(key);
                }
            }
        }

        return mapping;
    }

    private CompletableFuture<Long> updateSettings(TableParameter concreteTableParameter, String... indices) {
        if (concreteTableParameter.settings().getAsMap().isEmpty() || indices.length == 0) {
            return CompletableFuture.completedFuture(null);
        }
        UpdateSettingsRequest request = new UpdateSettingsRequest(concreteTableParameter.settings(), indices);
        request.indicesOptions(IndicesOptions.lenientExpandOpen());

        FutureActionListener<UpdateSettingsResponse, Long> listener = new FutureActionListener<>(r -> 0L);
        transportUpdateSettingsAction.execute(request, listener);
        return listener;
    }

    private void addColumnToTable(AddColumnAnalyzedStatement analysis, final CompletableFuture<?> result) {
        boolean updateTemplate = analysis.table().isPartitioned();
        List<CompletableFuture<Long>> results = new ArrayList<>(2);
        final Map<String, Object> mapping = analysis.analyzedTableElements().toMapping();

        if (updateTemplate) {
            results.add(updateTemplate(mapping, Settings.EMPTY, analysis.table().ident()));
        }

        String[] indexNames = getIndexNames(analysis.table(), null);
        if (indexNames.length > 0) {
            results.add(updateMapping(mapping, indexNames));
        }

        applyMultiFutureCallback(result, results);
    }

    private void applyMultiFutureCallback(final CompletableFuture<?> result, List<CompletableFuture<Long>> futures) {
        BiConsumer<List<Long>, Throwable> finalConsumer = (List<Long> receivedResult, Throwable t) -> {
            if (t == null) {
                result.complete(null);
            } else {
                result.completeExceptionally(t);
            }
        };

        MultiBiConsumer<Long> consumer = new MultiBiConsumer<>(futures.size(), finalConsumer);
        for (CompletableFuture<Long> future : futures) {
            future.whenComplete(consumer);
        }
    }

    private static String[] getIndexNames(DocTableInfo tableInfo, @Nullable PartitionName partitionName) {
        String[] indexNames;
        if (tableInfo.isPartitioned()) {
            if (partitionName == null) {
                // all partitions
                indexNames = Stream.of(tableInfo.concreteIndices()).toArray(String[]::new);
            } else {
                // single partition
                indexNames = new String[]{partitionName.asIndexName()};
            }
        } else {
            indexNames = new String[]{tableInfo.ident().indexName()};
        }
        return indexNames;
    }

    private static boolean areAllMappingsEqual(MetaData metaData, String... indices) {
        Map<String, Object> lastMapping = null;
        for (String index : indices) {
            try {
                Map<String, Object> mapping = metaData.index(index).mapping(Constants.DEFAULT_MAPPING_TYPE).getSourceAsMap();
                if (lastMapping != null && !lastMapping.equals(mapping)) {
                    return false;
                }
                lastMapping = mapping;
            } catch (ElasticsearchParseException e) {
                throw new RuntimeException(e);
            }
        }
        return true;
    }

    @VisibleForTesting
    static String getRerouteIndex(RerouteAnalyzedStatement statement, Row parameters) throws SQLException {
        ShardedTable shardedTable = statement.tableInfo();
        if (shardedTable instanceof DocTableInfo) {
            DocTableInfo docTableInfo = (DocTableInfo) shardedTable;
            PartitionName partitionName = AlterTableAnalyzer.createPartitionName(statement.partitionProperties(),
                docTableInfo, parameters);

            String indexName = docTableInfo.ident().indexName();
            if (partitionName != null) {
                indexName = partitionName.asIndexName();
            }

            if (statement.partitionProperties().isEmpty() &&
                docTableInfo.isPartitioned()) {
                throw new SQLException("table is partitioned however no partition clause has been specified");
            }
            return indexName;
        }

        // Table is a blob table
        assert shardedTable.concreteIndices().length == 1;
        return shardedTable.concreteIndices()[0];
    }

    @VisibleForTesting
    static boolean validateProperty(String propertyKey, GenericProperties properties, Row parameters) throws IllegalArgumentException {
        if (properties != null) {
            for (String key : properties.keys()) {
                if (propertyKey.equals(key)) {
                    return (boolean) ExpressionToObjectVisitor.convert(
                        properties.get(propertyKey),
                        parameters);
                } else {
                    throw new IllegalArgumentException(String.format(Locale.ENGLISH,
                        "\"%s\" is not a valid setting for CANCEL SHARD", key));
                }
            }
        }
        return false;
    }

    private CompletableFuture<Long> executeRerouteRequest(AllocationCommand command, boolean retryFailed) {
        final CompletableFuture<Long> resultFuture = new CompletableFuture<>();

        ClusterRerouteRequest request = new ClusterRerouteRequest();
        request.add(command);
        transportClusterRerouteAction.execute(request, new ActionListener<ClusterRerouteResponse>() {
            @Override
            public void onResponse(ClusterRerouteResponse clusterRerouteResponse) {
                if (clusterRerouteResponse.isAcknowledged()) {
                    resultFuture.complete(1L);
                } else {
                    resultFuture.complete(-1L);
                }
            }

            @Override
            public void onFailure(Exception e) {
                resultFuture.completeExceptionally(e);
            }
        });
        return resultFuture;
    }

    public CompletableFuture<Long> executeRerouteMoveShard(RerouteMoveShardAnalyzedStatement statement, Row parameters) {
        String indexName;
        try {
            indexName = getRerouteIndex(statement, parameters);
        } catch (SQLException e) {
            return CompletableFutures.failedFuture(e);
        }
        int shardId = ExpressionToNumberVisitor.convert(statement.shardId(), parameters).intValue();
        String fromNodeId = ExpressionToStringVisitor.convert(statement.fromNodeId(), parameters);
        String toNodeId = ExpressionToStringVisitor.convert(statement.toNodeId(), parameters);

        MoveAllocationCommand command = new MoveAllocationCommand(indexName, shardId, fromNodeId, toNodeId);
        return executeRerouteRequest(command, false);
    }

    public CompletableFuture<Long> executeRerouteCancelShard(RerouteCancelShardAnalyzedStatement statement, Row parameters) {
        final String ALLOW_PRIMARY = "allow_primary";

        String indexName;
        boolean allowPrimary;
        try {
            indexName = getRerouteIndex(statement, parameters);
            allowPrimary = validateProperty(ALLOW_PRIMARY, statement.properties(), parameters);
        } catch (Exception e) {
            return CompletableFutures.failedFuture(e);
        }

        int shardId = ExpressionToNumberVisitor.convert(statement.shardId(), parameters).intValue();
        String nodeId = ExpressionToStringVisitor.convert(statement.nodeId(), parameters);


        CancelAllocationCommand command = new CancelAllocationCommand(indexName, shardId, nodeId, allowPrimary);
        return executeRerouteRequest(command, false);
    }

    public CompletableFuture<Long> executeRerouteAllocateReplicaShard(RerouteAllocateReplicaShardAnalyzedStatement statement, Row parameters) {
        String indexName;
        try {
            indexName = getRerouteIndex(statement, parameters);
        } catch (SQLException e) {
            return CompletableFutures.failedFuture(e);
        }

        int shardId = ExpressionToNumberVisitor.convert(statement.shardId(), parameters).intValue();
        String nodeId = ExpressionToStringVisitor.convert(statement.nodeId(), parameters);

        AllocateReplicaAllocationCommand command = new AllocateReplicaAllocationCommand(indexName, shardId, nodeId);
        return executeRerouteRequest(command, false);
    }

    private class ResultSetReceiver implements ResultReceiver {

        private final AddColumnAnalyzedStatement analysis;
        private final CompletableFuture<?> result;

        private long count;

        ResultSetReceiver(AddColumnAnalyzedStatement analysis, CompletableFuture<?> result) {
            this.analysis = analysis;
            this.result = result;
        }

        @Override
        public void setNextRow(Row row) {
            count = (long) row.get(0);
        }

        @Override
        public void batchFinished() {
        }

        @Override
        public void allFinished(boolean interrupted) {
            if (count == 0L) {
                addColumnToTable(analysis, result);
            } else {
                String columnFailure = analysis.newPrimaryKeys() ? "primary key" : "generated";
                fail(new UnsupportedOperationException(String.format(Locale.ENGLISH,
                    "Cannot add a %s column to a table that isn't empty", columnFailure)));
            }
        }

        @Override
        public void fail(@Nonnull Throwable t) {
            result.completeExceptionally(t);
        }

        @Override
        public CompletableFuture<?> completionFuture() {
            return result;
        }

    }
}
