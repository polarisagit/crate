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

package io.crate.planner.operators;

import com.google.common.collect.Sets;
import io.crate.action.sql.SessionContext;
import io.crate.analyze.OrderBy;
import io.crate.analyze.QueriedTableRelation;
import io.crate.analyze.WhereClause;
import io.crate.analyze.symbol.FieldReplacer;
import io.crate.analyze.symbol.RefVisitor;
import io.crate.analyze.symbol.Symbol;
import io.crate.analyze.symbol.Symbols;
import io.crate.collections.Lists2;
import io.crate.metadata.Reference;
import io.crate.metadata.doc.DocSysColumns;
import io.crate.metadata.doc.DocTableInfo;
import io.crate.metadata.table.TableInfo;
import io.crate.planner.Plan;
import io.crate.planner.Planner;
import io.crate.planner.PositionalOrderBy;
import io.crate.planner.distribution.DistributionInfo;
import io.crate.planner.fetch.FetchRewriter;
import io.crate.planner.node.dql.PlanWithFetchDescription;
import io.crate.planner.node.dql.RoutedCollectPhase;
import io.crate.planner.projection.builder.ProjectionBuilder;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

class Collect implements LogicalPlan {

    final QueriedTableRelation relation;
    final WhereClause where;

    final List<Symbol> toCollect;
    final FetchRewriter.FetchDescription fetchDescription;
    final TableInfo tableInfo;

    Collect(QueriedTableRelation relation, List<Symbol> toCollect, WhereClause where, Set<Symbol> usedColumns) {
        this.relation = relation;
        this.where = where;
        this.tableInfo = relation.tableRelation().tableInfo();

        // TODO: extract/rework this
        if (tableInfo instanceof DocTableInfo) {
            DocTableInfo docTableInfo = (DocTableInfo) this.tableInfo;
            Set<Symbol> columnsToCollect = LogicalPlanner.extractColumns(toCollect);
            Sets.SetView<Symbol> unusedColumns = Sets.difference(columnsToCollect, usedColumns);
            ArrayList<Symbol> fetchable = new ArrayList<>();
            ArrayList<Reference> fetchRefs = new ArrayList<>();
            for (Symbol unusedColumn : unusedColumns) {
                if (!Symbols.containsColumn(unusedColumn, DocSysColumns.SCORE)) {
                    fetchable.add(unusedColumn);
                }
                RefVisitor.visitRefs(unusedColumn, fetchRefs::add);
            }
            if (!fetchable.isEmpty()) {
                Reference fetchIdRef = DocSysColumns.forTable(docTableInfo.ident(), DocSysColumns.FETCHID);
                ArrayList<Symbol> preFetchSymbols = new ArrayList<>();
                preFetchSymbols.add(fetchIdRef);
                preFetchSymbols.addAll(usedColumns);
                fetchDescription = new FetchRewriter.FetchDescription(
                    docTableInfo.ident(),
                    docTableInfo.partitionedByColumns(),
                    fetchIdRef,
                    preFetchSymbols,
                    toCollect,
                    fetchRefs
                );
                this.toCollect = preFetchSymbols;
            } else {
                this.fetchDescription = null;
                this.toCollect = toCollect;
            }
        } else {
            this.fetchDescription = null;
            this.toCollect = toCollect;
        }
    }

    @Override
    public Plan build(Planner.Context plannerContext,
                      ProjectionBuilder projectionBuilder,
                      int limitHint,
                      int offset,
                      @Nullable OrderBy order) {
        // workaround for dealing with fields from joins
        java.util.function.Function<? super Symbol, ? extends Symbol> fieldsToRefs =
            FieldReplacer.bind(f -> relation.querySpec().outputs().get(f.index()));
        List<Symbol> collectRefs = Lists2.copyAndReplace(toCollect, fieldsToRefs);

        SessionContext sessionContext = plannerContext.transactionContext().sessionContext();
        RoutedCollectPhase collectPhase = new RoutedCollectPhase(
            plannerContext.jobId(),
            plannerContext.nextExecutionPhaseId(),
            "collect",
            plannerContext.allocateRouting(tableInfo, where, null, sessionContext),
            tableInfo.rowGranularity(),
            collectRefs,
            Collections.emptyList(),
            where,
            DistributionInfo.DEFAULT_BROADCAST,
            sessionContext.user()
        );
        collectPhase.orderBy(order);
        io.crate.planner.node.dql.Collect collect = new io.crate.planner.node.dql.Collect(
            collectPhase,
            limitHint,
            offset,
            collectRefs.size(),
            limitHint,
            PositionalOrderBy.of(order, collectRefs)
        );
        if (fetchDescription == null) {
            return collect;
        }
        return new PlanWithFetchDescription(collect, fetchDescription);
    }

    @Override
    public LogicalPlan tryCollapse() {
        return this;
    }

    @Override
    public List<Symbol> outputs() {
        return toCollect;
    }
}
