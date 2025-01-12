package com.scylladb.cdc.cql.driver3;

import static com.datastax.driver.core.Metadata.quoteIfNecessary;
import static com.datastax.driver.core.querybuilder.QueryBuilder.bindMarker;
import static com.datastax.driver.core.querybuilder.QueryBuilder.gt;
import static com.datastax.driver.core.querybuilder.QueryBuilder.in;
import static com.datastax.driver.core.querybuilder.QueryBuilder.lte;
import static com.datastax.driver.core.querybuilder.QueryBuilder.select;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import com.datastax.driver.core.ConsistencyLevel;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.RegularStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.ResultSetFuture;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import com.google.common.base.Preconditions;
import com.google.common.flogger.FluentLogger;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.scylladb.cdc.cql.WorkerCQL;
import com.scylladb.cdc.model.StreamId;
import com.scylladb.cdc.model.TableName;
import com.scylladb.cdc.model.worker.ChangeSchema;
import com.scylladb.cdc.model.worker.RawChange;
import com.scylladb.cdc.model.worker.Task;

public final class Driver3WorkerCQL implements WorkerCQL {
    private static final FluentLogger logger = FluentLogger.forEnclosingClass();

    private final Session session;
    private final Map<TableName, PreparedStatement> preparedStmts = new HashMap<>();

    public Driver3WorkerCQL(Session session) {
        this.session = Preconditions.checkNotNull(session);
    }

    private static final class PreparedResult {
        public final TableName table;
        public final PreparedStatement stmt;

        public PreparedResult(TableName table, PreparedStatement stmt) {
            this.table = Preconditions.checkNotNull(table);
            this.stmt = Preconditions.checkNotNull(stmt);
        }
    }

    private static RegularStatement getStmt(TableName table) {
        return select().all().from(quoteIfNecessary(table.keyspace), quoteIfNecessary(table.name + "_scylla_cdc_log"))
                .where(in(quoteIfNecessary("cdc$stream_id"), bindMarker()))
                .and(gt(quoteIfNecessary("cdc$time"), bindMarker()))
                .and(lte(quoteIfNecessary("cdc$time"), bindMarker()));
    }

    private CompletableFuture<PreparedResult> prepare(TableName table) {
        CompletableFuture<PreparedResult> result = new CompletableFuture<>();
        Futures.addCallback(session.prepareAsync(getStmt(table)), new FutureCallback<PreparedStatement>() {

            @Override
            public void onSuccess(PreparedStatement r) {
                result.complete(new PreparedResult(table, r));
            }

            @Override
            public void onFailure(Throwable t) {
                result.completeExceptionally(t);
            }
        });
        return result;
    }

    @Override
    public void prepare(Set<TableName> tables) throws InterruptedException, ExecutionException {
        @SuppressWarnings("unchecked")
        CompletableFuture<PreparedResult>[] futures = tables.stream().filter(t -> !preparedStmts.containsKey(t))
                .map(this::prepare).toArray(n -> new CompletableFuture[n]);
        CompletableFuture.allOf(futures).get();
        for (CompletableFuture<PreparedResult> f : futures) {
            PreparedResult r = f.get();
            preparedStmts.put(r.table, r.stmt);
        }
    }

    private final class Driver3Reader implements Reader {

        private volatile ResultSet rs;
        private volatile ChangeSchema schema;

        public Driver3Reader(ResultSet rs) {
            this.rs = Preconditions.checkNotNull(rs);
        }

        private void findNext(CompletableFuture<Optional<RawChange>> fut) {
            if (rs.getAvailableWithoutFetching() == 0) {
                if (rs.isFullyFetched()) {
                    fut.complete(Optional.empty());
                } else {
                    Futures.addCallback(rs.fetchMoreResults(), new FutureCallback<ResultSet>() {

                        @Override
                        public void onSuccess(ResultSet result) {
                            // There's no guarantee what thread will run this
                            rs = result;
                            findNext(fut);
                        }

                        @Override
                        public void onFailure(Throwable t) {
                            fut.completeExceptionally(t);
                        }
                    });
                }
            } else {
                Row row = rs.one();
                if (schema == null) {
                    schema = new Driver3SchemaBuilder()
                            .withClusterMetadata(session.getCluster().getMetadata())
                            .withRow(row).build();
                } else {
                    // TODO: the schema might have changed
                    // is there some hash/digest that we can use to check that?
                    // it wouldn't be nice if we had to update `schema` on each query/page (expensive)
                    // See Scylla issue #7824.
                }
                fut.complete(Optional.of(new Driver3RawChange(row, schema)));
            }
        }

        @Override
        public CompletableFuture<Optional<RawChange>> nextChange() {
            CompletableFuture<Optional<RawChange>> result = new CompletableFuture<>();
            findNext(result);
            return result;
        }

    }

    private ConsistencyLevel computeCL() {
        return session.getCluster().getMetadata().getAllHosts().size() > 1 ? ConsistencyLevel.QUORUM
                : ConsistencyLevel.ONE;
    }

    private CompletableFuture<Reader> query(PreparedStatement stmt, Task task) {
        CompletableFuture<Reader> result = new CompletableFuture<>();
        ResultSetFuture future = session
                .executeAsync(stmt
                        .bind(task.streams.stream().map(StreamId::getValue).collect(Collectors.toList()),
                                task.state.getWindowStart(), task.state.getWindowEnd())
                        .setConsistencyLevel(computeCL()));
        logger.atFine().log("Querying window: [%s, %s] for task: %s, task state: %s", task.state.getWindowStart(), task.state.getWindowEnd(), task.id, task.state);

        Futures.addCallback(future, new FutureCallback<ResultSet>() {

            @Override
            public void onSuccess(ResultSet rs) {
                result.complete(new Driver3Reader(rs));
            }

            @Override
            public void onFailure(Throwable t) {
                result.completeExceptionally(t);
            }
        });
        return result;
    }

    @Override
    public CompletableFuture<Reader> createReader(Task task) {
        PreparedStatement stmt = preparedStmts.get(task.id.getTable());
        Preconditions.checkNotNull(stmt);
        return query(stmt, task);
    }

}
