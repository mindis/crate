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

package io.crate.metadata.doc;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import io.crate.analyze.AlterPartitionedTableParameterInfo;
import io.crate.analyze.TableParameterInfo;
import io.crate.analyze.WhereClause;
import io.crate.exceptions.UnavailableShardsException;
import io.crate.metadata.*;
import io.crate.metadata.table.AbstractDynamicTableInfo;
import io.crate.metadata.table.ColumnPolicy;
import io.crate.planner.RowGranularity;
import org.apache.lucene.util.BytesRef;
import org.elasticsearch.cluster.ClusterService;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.routing.GroupShardsIterator;
import org.elasticsearch.cluster.routing.ShardIterator;
import org.elasticsearch.cluster.routing.ShardRouting;
import org.elasticsearch.indices.IndexMissingException;

import javax.annotation.Nullable;
import java.util.*;


public class DocTableInfo extends AbstractDynamicTableInfo {

    private final List<ReferenceInfo> columns;
    private final List<ReferenceInfo> partitionedByColumns;
    private final Map<ColumnIdent, IndexReferenceInfo> indexColumns;
    private final ImmutableMap<ColumnIdent, ReferenceInfo> references;
    private final TableIdent ident;
    private final List<ColumnIdent> primaryKeys;
    private final ColumnIdent clusteredBy;
    private final String[] concreteIndices;
    private final List<ColumnIdent> partitionedBy;
    private final int numberOfShards;
    private final BytesRef numberOfReplicas;
    private final ClusterService clusterService;
    private final TableParameterInfo tableParameterInfo;

    private final String[] indices;
    private final List<PartitionName> partitions;

    private final boolean isAlias;
    private final boolean hasAutoGeneratedPrimaryKey;
    private final boolean isPartitioned;

    private final ColumnPolicy columnPolicy;

    public DocTableInfo(DocSchemaInfo schemaInfo,
                        TableIdent ident,
                        List<ReferenceInfo> columns,
                        List<ReferenceInfo> partitionedByColumns,
                        ImmutableMap<ColumnIdent, IndexReferenceInfo> indexColumns,
                        ImmutableMap<ColumnIdent, ReferenceInfo> references,
                        List<ColumnIdent> primaryKeys,
                        ColumnIdent clusteredBy,
                        boolean isAlias,
                        boolean hasAutoGeneratedPrimaryKey,
                        String[] concreteIndices,
                        ClusterService clusterService,
                        int numberOfShards,
                        BytesRef numberOfReplicas,
                        List<ColumnIdent> partitionedBy,
                        List<PartitionName> partitions,
                        ColumnPolicy columnPolicy) {
        super(schemaInfo);
        this.clusterService = clusterService;
        this.columns = columns;
        this.partitionedByColumns = partitionedByColumns;
        this.indexColumns = indexColumns;
        this.references = references;
        this.ident = ident;
        this.primaryKeys = primaryKeys;
        this.clusteredBy = clusteredBy;
        this.concreteIndices = concreteIndices;
        this.numberOfShards = numberOfShards;
        this.numberOfReplicas = numberOfReplicas;
        indices = new String[]{ident.esName()};
        this.isAlias = isAlias;
        this.hasAutoGeneratedPrimaryKey = hasAutoGeneratedPrimaryKey;
        isPartitioned = !partitionedByColumns.isEmpty();
        this.partitionedBy = partitionedBy;
        this.partitions = partitions;
        this.columnPolicy = columnPolicy;
        if (isPartitioned) {
            tableParameterInfo = new AlterPartitionedTableParameterInfo();
        } else {
            tableParameterInfo = new TableParameterInfo();
        }
    }

    /**
     * Returns the ReferenceInfo if it is defined in the table's schema.
     * If the table doesn't contain the column and the columnIdent has no path it will create a
     * referenceInfo with type null. This indicates a "unknown column".
     *
     * If the columnIdent does contain a path it's parent will be resolved, if found and if it a STRICT object
     * null is returned.
     *
     * If the parent isn't strict or not found a referenceInfo with type null is returned.
     */
    @Override
    @Nullable
    public ReferenceInfo getReferenceInfo(ColumnIdent columnIdent) {
        return references.get(columnIdent);
    }

    @Override
    public Collection<ReferenceInfo> columns() {
        return columns;
    }

    @Override
    public RowGranularity rowGranularity() {
        return RowGranularity.DOC;
    }

    @Override
    public TableIdent ident() {
        return ident;
    }

    private void processShardRouting(Map<String, Map<String, Set<Integer>>> locations, ShardRouting shardRouting) {
        String node = shardRouting.currentNodeId();
        Map<String, Set<Integer>> nodeMap = locations.get(node);
        if (nodeMap == null) {
            nodeMap = new HashMap<>();
            locations.put(shardRouting.currentNodeId(), nodeMap);
        }

        Set<Integer> shards = nodeMap.get(shardRouting.getIndex());
        if (shards == null) {
            shards = new HashSet<>();
            nodeMap.put(shardRouting.getIndex(), shards);
        }
        shards.add(shardRouting.id());
    }


    @Override
    public Routing getRouting(WhereClause whereClause, @Nullable String preference) {
        ClusterState clusterState = clusterService.state();
        Map<String, Map<String, Set<Integer>>> locations = new HashMap<>();

        String[] routingIndices = concreteIndices;
        if (whereClause.partitions().size() > 0) {
            routingIndices = whereClause.partitions().toArray(new String[whereClause.partitions().size()]);
        }

        Map<String, Set<String>> routingMap = null;
        if (whereClause.clusteredBy().isPresent()) {
            routingMap = clusterState.metaData().resolveSearchRouting(
                    ImmutableSet.of(whereClause.clusteredBy().get()), routingIndices);
        }

        GroupShardsIterator shardIterators;
        try {
            shardIterators = clusterService.operationRouting().searchShards(
                    clusterState,
                    indices,
                    routingIndices,
                    routingMap,
                    preference
            );
        } catch (IndexMissingException e) {
            return new Routing();
        }
        ShardRouting shardRouting;
        for (ShardIterator shardIterator : shardIterators) {
            shardRouting = shardIterator.nextOrNull();
            if (shardRouting != null && shardRouting.active()) {
                processShardRouting(locations, shardRouting);
            } else {
                throw new UnavailableShardsException(shardIterator.shardId());
            }
        }
        return new Routing(locations);
    }

    public List<ColumnIdent> primaryKey() {
        return primaryKeys;
    }

    @Override
    public int numberOfShards() {
        return numberOfShards;
    }

    @Override
    public BytesRef numberOfReplicas() {
        return numberOfReplicas;
    }

    @Override
    public boolean hasAutoGeneratedPrimaryKey() {
        return hasAutoGeneratedPrimaryKey;
    }

    @Override
    public ColumnIdent clusteredBy() {
        return clusteredBy;
    }

    @Override
    public boolean isAlias() {
        return isAlias;
    }

    @Override
    public String[] concreteIndices() {
        return concreteIndices;
    }

    /**
     * columns this table is partitioned by.
     *
     * guaranteed to be in the same order as defined in CREATE TABLE statement
     * @return always a list, never null
     */
    public List<ReferenceInfo> partitionedByColumns() {
        return partitionedByColumns;
    }

    /**
     * column names of columns this table is partitioned by (in dotted syntax).
     *
     * guaranteed to be in the same order as defined in CREATE TABLE statement
     * @return always a list, never null
     */
    @Override
    public List<ColumnIdent> partitionedBy() {
        return partitionedBy;
    }

    @Override
    public List<PartitionName> partitions() {
        return partitions;
    }

    @Override
    public boolean isPartitioned() {
        return isPartitioned;
    }

    @Override
    public IndexReferenceInfo indexColumn(ColumnIdent ident) {
        return indexColumns.get(ident);
    }

    @Override
    public Iterator<ReferenceInfo> iterator() {
        return references.values().iterator();
    }

    @Override
    public ColumnPolicy columnPolicy() {
        return columnPolicy;
    }

    @Override
    public TableParameterInfo tableParameterInfo () {
        return tableParameterInfo;
    }

}
