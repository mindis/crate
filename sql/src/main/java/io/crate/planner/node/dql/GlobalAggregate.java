/*
 * Licensed to CRATE Technology GmbH ("Crate") under one or more contributor
 * license agreements.  See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.  Crate licenses
 * this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
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

package io.crate.planner.node.dql;

import io.crate.analyze.relations.PlannedAnalyzedRelation;
import io.crate.analyze.relations.RelationVisitor;
import io.crate.exceptions.ColumnUnknownException;
import io.crate.metadata.Path;
import io.crate.planner.Plan;
import io.crate.planner.PlanVisitor;
import io.crate.planner.symbol.Field;

import javax.annotation.Nullable;
import java.util.List;

public class GlobalAggregate implements PlannedAnalyzedRelation, Plan {

    private final CollectNode collectNode;
    private final MergeNode mergeNode;

    public GlobalAggregate(CollectNode collectNode, MergeNode mergeNode) {
        this.collectNode = collectNode;
        this.mergeNode = mergeNode;
    }

    public CollectNode collectNode() {
        return collectNode;
    }

    public MergeNode mergeNode() {
        return mergeNode;
    }

    @Override
    public <C, R> R accept(PlanVisitor<C, R> visitor, C context) {
        return visitor.visitGlobalAggregate(this, context);
    }

    @Override
    public <C, R> R accept(RelationVisitor<C, R> visitor, C context) {
        return visitor.visitPlanedAnalyzedRelation(this, context);
    }

    @Nullable
    @Override
    public Field getField(Path path) {
        throw new UnsupportedOperationException("getField not supported on GlobalAggregateNode");
    }

    @Override
    public Field getWritableField(Path path) throws UnsupportedOperationException, ColumnUnknownException {
        throw new UnsupportedOperationException("getWritableField not supported on GlobalAggregateNode");
    }

    @Override
    public List<Field> fields() {
        throw new UnsupportedOperationException("fields not supported on GlobalAggregateNode");
    }

    @Override
    public Plan plan() {
        return this;
    }
}
