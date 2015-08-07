/*
 * Licensed to CRATE Technology GmbH ("Crate") under one or more contributor
 * license agreements.  See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.  Crate licenses
 * this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial agreement.
 */

package io.crate.operation;

import com.google.common.base.Optional;
import io.crate.breaker.RamAccountingContext;
import io.crate.core.collections.Row;
import io.crate.executor.transport.TransportActionProvider;
import io.crate.metadata.Functions;
import io.crate.metadata.ReferenceResolver;
import io.crate.operation.merge.*;
import io.crate.operation.projectors.FlatProjectorChain;
import io.crate.operation.projectors.ProjectionToProjectorVisitor;
import io.crate.operation.projectors.sorting.OrderingByPosition;
import io.crate.planner.RowGranularity;
import io.crate.planner.node.dql.MergePhase;
import org.elasticsearch.action.bulk.BulkRetryCoordinatorPool;
import org.elasticsearch.cluster.ClusterService;
import org.elasticsearch.common.collect.Tuple;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.inject.Singleton;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.threadpool.ThreadPool;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.concurrent.Executor;

@Singleton
public class PageDownstreamFactory {

    private final ProjectionToProjectorVisitor projectionToProjectorVisitor;

    @Inject
    public PageDownstreamFactory(ClusterService clusterService,
                                 ThreadPool threadPool,
                                 Settings settings,
                                 TransportActionProvider transportActionProvider,
                                 BulkRetryCoordinatorPool bulkRetryCoordinatorPool,
                                 ReferenceResolver referenceResolver,
                                 Functions functions) {
        ImplementationSymbolVisitor implementationSymbolVisitor = new ImplementationSymbolVisitor(
                referenceResolver,
                functions,
                RowGranularity.DOC
        );
        this.projectionToProjectorVisitor = new ProjectionToProjectorVisitor(
                clusterService,
                threadPool,
                settings,
                transportActionProvider,
                bulkRetryCoordinatorPool,
                implementationSymbolVisitor
        );
    }

    public Tuple<PageDownstream, FlatProjectorChain> createMergeNodePageDownstream(MergePhase mergeNode,
                                                                                   RowDownstream rowDownstream,
                                                                                   RamAccountingContext ramAccountingContext,
                                                                                   Optional<Executor> executorOptional) {
        FlatProjectorChain projectorChain = null;
        if (!mergeNode.projections().isEmpty()) {
            projectorChain = FlatProjectorChain.withAttachedDownstream(
                    projectionToProjectorVisitor,
                    ramAccountingContext,
                    mergeNode.projections(),
                    rowDownstream,
                    mergeNode.jobId()
            );
            rowDownstream = projectorChain.firstProjector();
        }

        PagingIterator<Row> pagingIterator;
        if (mergeNode.sortedInputOutput() && mergeNode.numUpstreams() > 1) {
            pagingIterator = new SortedPagingIterator(
                    OrderingByPosition.ordering(
                            mergeNode.orderByIndices(),
                            mergeNode.reverseFlags(),
                            mergeNode.nullsFirst()
                    )
            );
        } else {
            pagingIterator = new PassThroughPagingIterator<>();
        }
        PageDownstream pageDownstream = new IteratorPageDownstream(rowDownstream, pagingIterator, executorOptional);
        return new Tuple<>(pageDownstream, projectorChain);
    }
}
