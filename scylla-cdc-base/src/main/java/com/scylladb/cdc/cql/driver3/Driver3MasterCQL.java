package com.scylladb.cdc.cql.driver3;

import static com.datastax.driver.core.querybuilder.QueryBuilder.bindMarker;
import static com.datastax.driver.core.querybuilder.QueryBuilder.column;
import static com.datastax.driver.core.querybuilder.QueryBuilder.eq;
import static com.datastax.driver.core.querybuilder.QueryBuilder.gt;
import static com.datastax.driver.core.querybuilder.QueryBuilder.select;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.ConsistencyLevel;
import com.datastax.driver.core.KeyspaceMetadata;
import com.datastax.driver.core.Metadata;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.RegularStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.ResultSetFuture;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.Statement;
import com.datastax.driver.core.TableMetadata;
import com.google.common.base.Preconditions;
import com.google.common.flogger.FluentLogger;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.scylladb.cdc.cql.BaseMasterCQL;
import com.scylladb.cdc.model.FutureUtils;
import com.scylladb.cdc.model.TableName;

public final class Driver3MasterCQL extends BaseMasterCQL {
    private static final FluentLogger logger = FluentLogger.forEnclosingClass();
    private final Session session;

    // (Streams description table V2)
    //
    // PreparedStatements for querying in clusters with
    // system_distributed.cdc_generation_timestamps
    // and system_distributed.cdc_streams_descriptions_v2 tables.
    private PreparedStatement fetchSmallestGenerationAfterStmt;
    private PreparedStatement fetchStreamsStmt;
    private boolean foundRewritten = false;

    // (Streams description table V1)
    //
    // PreparedStatements for querying in clusters WITHOUT
    // system_distributed.cdc_generation_timestamps
    // and system_distributed.cdc_streams_descriptions_v2 tables.
    private PreparedStatement legacyFetchSmallestGenerationAfterStmt;
    private PreparedStatement legacyFetchStreamsStmt;

    public Driver3MasterCQL(Session session) {
        Preconditions.checkNotNull(session);
        this.session = session;
    }

    private CompletableFuture<Boolean> fetchShouldQueryLegacyTables() {
        // Decide if we should query "Streams description table V1" (legacy)

        boolean hasNewTables = session.getCluster().getMetadata().getKeyspace("system_distributed")
                .getTable("cdc_generation_timestamps") != null;
        boolean hasLegacyTables = session.getCluster().getMetadata().getKeyspace("system_distributed")
                .getTable("cdc_streams_descriptions") != null;

        // Simple cases when there are only
        // tables from one version:

        if (hasLegacyTables && !hasNewTables) {
            logger.atFine().log("Using legacy (V1) streams description table, as a newer (V2) table was not found.");
            return CompletableFuture.completedFuture(true);
        }

        if (!hasLegacyTables && hasNewTables) {
            logger.atFine().log("Using new (V2) streams description table, as a legacy (V1) table was not found.");
            return CompletableFuture.completedFuture(false);
        }

        if (!hasLegacyTables && !hasNewTables) {
            // No stream description tables found!
            CompletableFuture<Boolean> exceptionalFuture = new CompletableFuture<>();
            exceptionalFuture.completeExceptionally(new IllegalStateException("Could not find any Scylla CDC stream " +
                    "description tables (either streams description table V1 or V2). Make sure you have Scylla CDC enabled."));
            return exceptionalFuture;
        }

        // By now we know that there are both "Streams description table V1"
        // and "Streams description table V2" present in the cluster.
        //
        // We should use "Streams description table V2" only after a
        // rewrite has completed:
        // https://github.com/scylladb/scylla/blob/master/docs/design-notes/cdc.md#streams-description-table-v1-and-rewriting

        if (foundRewritten) {
            // If we found a "rewritten" row, that means that
            // we can use the "Streams description table V2".
            logger.atFiner().log("Using new (V2) streams description table, because a 'rewritten' row was found previously.");
            return CompletableFuture.completedFuture(false);
        }

        // We haven't seen a rewritten row yet. Do a query
        // to check if it exists now.

        return executeOne(getFetchRewritten()).thenApply(fetchedRewritten -> {
            if (fetchedRewritten.isPresent()) {
                // There is a "rewritten" row.
                foundRewritten = true;
                logger.atInfo().log("Found a 'rewritten' row. Will use new (V2) streams description table from now on.");
                return false;
            } else {
                logger.atWarning().log("Using legacy (V1) streams description table, even though newer (V2) table was found, but " +
                        "a 'rewritten' row is still missing. This might mean that the rewriting process is still pending or you have " +
                        "disabled streams description rewriting - in that case the library will not switch to the new (V2) table " +
                        "until it discovers a 'rewritten' row. Read more at: " +
                        "https://github.com/scylladb/scylla/blob/master/docs/design-notes/cdc.md#streams-description-table-v1-and-rewriting");
                return true;
            }
        });
    }

    private CompletableFuture<PreparedStatement> getLegacyFetchSmallestGenerationAfter() {
        if (legacyFetchSmallestGenerationAfterStmt != null) {
            return CompletableFuture.completedFuture(legacyFetchSmallestGenerationAfterStmt);
        } else {
            ListenableFuture<PreparedStatement> prepareStatement = session.prepareAsync(
                    select().min(column("time")).from("system_distributed", "cdc_streams_descriptions")
                            .where(gt("time", bindMarker())).allowFiltering()
            );
            return FutureUtils.convert(prepareStatement).thenApply(preparedStatement -> {
                legacyFetchSmallestGenerationAfterStmt = preparedStatement;
                return preparedStatement;
            });
        }
    }

    private CompletableFuture<PreparedStatement> getFetchSmallestGenerationAfter() {
        if (fetchSmallestGenerationAfterStmt != null) {
            return CompletableFuture.completedFuture(fetchSmallestGenerationAfterStmt);
        } else {
            ListenableFuture<PreparedStatement> prepareStatement = session.prepareAsync(
                    select().min(column("time")).from("system_distributed", "cdc_generation_timestamps")
                            .where(eq("key", "timestamps")).and(gt("time", bindMarker()))
            );
            return FutureUtils.convert(prepareStatement).thenApply(preparedStatement -> {
                fetchSmallestGenerationAfterStmt = preparedStatement;
                return preparedStatement;
            });
        }
    }

    private CompletableFuture<PreparedStatement> getLegacyFetchStreams() {
        if (legacyFetchStreamsStmt != null) {
            return CompletableFuture.completedFuture(legacyFetchStreamsStmt);
        } else {
            ListenableFuture<PreparedStatement> prepareStatement = session.prepareAsync(
                    select().column("streams").from("system_distributed", "cdc_streams_descriptions")
                            .where(eq("time", bindMarker())).allowFiltering()
            );
            return FutureUtils.convert(prepareStatement).thenApply(preparedStatement -> {
                legacyFetchStreamsStmt = preparedStatement;
                return preparedStatement;
            });
        }
    }

    private CompletableFuture<PreparedStatement> getFetchStreams() {
        if (fetchStreamsStmt != null) {
            return CompletableFuture.completedFuture(fetchStreamsStmt);
        } else {
            ListenableFuture<PreparedStatement> prepareStatement = session.prepareAsync(
                    select().column("streams").from("system_distributed", "cdc_streams_descriptions_v2")
                            .where(eq("time", bindMarker()))
            );
            return FutureUtils.convert(prepareStatement).thenApply(preparedStatement -> {
                fetchStreamsStmt = preparedStatement;
                return preparedStatement;
            });
        }
    }

    private Statement getFetchRewritten() {
        return select().from("system", "cdc_local")
                .where(eq("key", "rewritten"));
    }

    private ConsistencyLevel computeCL() {
        return session.getCluster().getMetadata().getAllHosts().size() > 1 ? ConsistencyLevel.QUORUM
                : ConsistencyLevel.ONE;
    }

    private void consumeOneResult(ResultSet rs, CompletableFuture<Optional<Row>> result) {
        int availCount = rs.getAvailableWithoutFetching();
        if (availCount == 0) {
            if (rs.isFullyFetched()) {
                result.complete(Optional.empty());
            } else {
                Futures.addCallback(rs.fetchMoreResults(), new FutureCallback<ResultSet>() {

                    @Override
                    public void onSuccess(ResultSet rs) {
                        consumeOneResult(rs, result);
                    }

                    @Override
                    public void onFailure(Throwable t) {
                        result.completeExceptionally(t);
                    }
                });
            }
        } else {
            assert (availCount == 1);
            result.complete(Optional.of(rs.one()));
        }
    }

    private void consumeManyResults(ResultSet rs, Collection<Row> alreadyFetched,
                                    CompletableFuture<Collection<Row>> result) {
        int availableWithoutFetching = rs.getAvailableWithoutFetching();
        if (availableWithoutFetching == 0) {
            if (rs.isFullyFetched()) {
                result.complete(alreadyFetched);
            } else {
                Futures.addCallback(rs.fetchMoreResults(), new FutureCallback<ResultSet>() {

                    @Override
                    public void onSuccess(ResultSet rsNew) {
                        consumeManyResults(rsNew, alreadyFetched, result);
                    }

                    @Override
                    public void onFailure(Throwable t) {
                        result.completeExceptionally(t);
                    }
                });
            }
        } else {
            for (int i = 0; i < availableWithoutFetching; i++) {
                alreadyFetched.add(rs.one());
            }
            consumeManyResults(rs, alreadyFetched, result);
        }
    }

    private CompletableFuture<Optional<Row>> executeOne(Statement stmt) {
        CompletableFuture<Optional<Row>> result = new CompletableFuture<>();
        ResultSetFuture future = session.executeAsync(stmt.setConsistencyLevel(computeCL()));
        Futures.addCallback(future, new FutureCallback<ResultSet>() {

            @Override
            public void onSuccess(ResultSet rs) {
                consumeOneResult(rs, result);
            }

            @Override
            public void onFailure(Throwable t) {
                result.completeExceptionally(t);
            }
        });
        return result;
    }

    private CompletableFuture<Collection<Row>> executeMany(Statement stmt) {
        CompletableFuture<Collection<Row>> result = new CompletableFuture<>();
        ResultSetFuture future = session.executeAsync(stmt.setConsistencyLevel(computeCL()));
        Futures.addCallback(future, new FutureCallback<ResultSet>() {

            @Override
            public void onSuccess(ResultSet rs) {
                consumeManyResults(rs, new ArrayList<>(), result);
            }

            @Override
            public void onFailure(Throwable t) {
                result.completeExceptionally(t);
            }
        });
        return result;
    }

    @Override
    protected CompletableFuture<Optional<Date>> fetchSmallestGenerationAfter(Date after) {
        return fetchShouldQueryLegacyTables().thenCompose(shouldQueryLegacyTables -> {
            if (shouldQueryLegacyTables) {
                return getLegacyFetchSmallestGenerationAfter().thenCompose(statement ->
                        executeOne(statement.bind(after)).thenApply(o -> o.map(r -> r.getTimestamp(0))));
            } else {
                return getFetchSmallestGenerationAfter().thenCompose(statement ->
                        executeOne(statement.bind(after)).thenApply(o -> o.map(r -> r.getTimestamp(0))));
            }
        });
    }

    @Override
    protected CompletableFuture<Set<ByteBuffer>> fetchStreamsForGeneration(Date generationStart) {
        return fetchShouldQueryLegacyTables().thenCompose(shouldQueryLegacyTables -> {
            if (shouldQueryLegacyTables) {
                return getLegacyFetchStreams().thenCompose(statement ->
                        executeOne(statement.bind(generationStart)).thenApply(o -> o.get().getSet(0, ByteBuffer.class)));
            } else {
                return getFetchStreams().thenCompose(statement -> executeMany(statement.bind(generationStart))
                        .thenApply(o -> o.stream().flatMap(r -> r.getSet(0, ByteBuffer.class).stream()).collect(Collectors.toSet())));
            }
        });
    }

    @Override
    public CompletableFuture<Optional<Long>> fetchTableTTL(TableName tableName) {
        Metadata metadata = session.getCluster().getMetadata();
        KeyspaceMetadata keyspaceMetadata = metadata.getKeyspace(tableName.keyspace);
        if (keyspaceMetadata == null) {
            return FutureUtils.exceptionalFuture(new IllegalArgumentException(
                    String.format("Could not fetch the metadata of keyspace %s.", tableName.keyspace)));
        }

        TableMetadata tableMetadata = keyspaceMetadata.getTable(tableName.name);
        if (tableMetadata == null) {
            return FutureUtils.exceptionalFuture(new IllegalArgumentException(
                    String.format("Could not fetch the metadata of table %s.%s.", tableName.keyspace, tableName.name)));
        }

        if (!tableMetadata.getOptions().isScyllaCDC()) {
            return FutureUtils.exceptionalFuture(new IllegalArgumentException(
                    String.format("Table %s.%s does not have Scylla CDC enabled.", tableName.keyspace, tableName.name)));
        }

        Map<String, String> scyllaCDCOptions = tableMetadata.getOptions().getScyllaCDCOptions();
        if (scyllaCDCOptions == null) {
            return FutureUtils.exceptionalFuture(new IllegalArgumentException(
                    String.format("Table %s.%s does not have Scylla CDC metadata, " +
                            "even though CDC is enabled.", tableName.keyspace, tableName.name)));
        }

        String ttl = scyllaCDCOptions.get("ttl");
        if (ttl == null) {
            return FutureUtils.exceptionalFuture(new IllegalArgumentException(
                    String.format("Table %s.%s does not have a TTL value in its metadata, " +
                            "even though Scylla CDC is enabled and the metadata is present.", tableName.keyspace, tableName.name)));
        }

        try {
            long parsedTTL = Long.parseLong(ttl);
            if (parsedTTL == 0) {
                // TTL is disabled.
                return CompletableFuture.completedFuture(Optional.empty());
            } else {
                return CompletableFuture.completedFuture(Optional.of(parsedTTL));
            }
        } catch (NumberFormatException ex) {
            return FutureUtils.exceptionalFuture(new IllegalArgumentException(
                    String.format("Table %s.%s has invalid TTL value: %s.", tableName.keyspace, tableName.name, ttl)));
        }
    }
}
