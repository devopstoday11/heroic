/*
 * Copyright (c) 2015 Spotify AB.
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.spotify.heroic.metric.datastax;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;

import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.ExecutionInfo;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Statement;
import com.datastax.driver.core.utils.Bytes;
import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableList;
import com.spotify.heroic.common.DateRange;
import com.spotify.heroic.common.Groups;
import com.spotify.heroic.common.LifeCycle;
import com.spotify.heroic.common.Series;
import com.spotify.heroic.metric.AbstractMetricBackend;
import com.spotify.heroic.metric.BackendEntry;
import com.spotify.heroic.metric.BackendKey;
import com.spotify.heroic.metric.BackendKeySet;
import com.spotify.heroic.metric.FetchData;
import com.spotify.heroic.metric.FetchQuotaWatcher;
import com.spotify.heroic.metric.MetricCollection;
import com.spotify.heroic.metric.MetricType;
import com.spotify.heroic.metric.Point;
import com.spotify.heroic.metric.QueryOptions;
import com.spotify.heroic.metric.QueryTrace;
import com.spotify.heroic.metric.WriteMetric;
import com.spotify.heroic.metric.WriteResult;
import com.spotify.heroic.metric.datastax.schema.Schema;
import com.spotify.heroic.metric.datastax.schema.Schema.PreparedFetch;
import com.spotify.heroic.metric.datastax.schema.SchemaInstance;
import com.spotify.heroic.statistics.MetricBackendReporter;

import eu.toolchain.async.AsyncFramework;
import eu.toolchain.async.AsyncFuture;
import eu.toolchain.async.FutureDone;
import eu.toolchain.async.Managed;
import eu.toolchain.async.ResolvableFuture;
import eu.toolchain.async.StreamCollector;
import eu.toolchain.async.Transform;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

/**
 * MetricBackend for Heroic cassandra datastore.
 */
@Slf4j
@ToString(of = { "connection" })
public class DatastaxBackend extends AbstractMetricBackend implements LifeCycle {
    private final AsyncFramework async;
    private final MetricBackendReporter reporter;
    private final Managed<Connection> connection;
    private final Groups groups;

    @Inject
    public DatastaxBackend(final AsyncFramework async, final MetricBackendReporter reporter,
            final Managed<Connection> connection, final Groups groups) {
        super(async);
        this.async = async;
        this.reporter = reporter;
        this.connection = connection;
        this.groups = groups;
    }

    @Override
    public Groups getGroups() {
        return groups;
    }

    @Override
    public AsyncFuture<Void> configure() {
        return async.resolved();
    }

    @Override
    public AsyncFuture<WriteResult> write(final WriteMetric w) {
        return connection.doto(c -> {
            return doWrite(c, c.schema.writeSession(), w);
        });
    }

    @Override
    public AsyncFuture<WriteResult> write(final Collection<WriteMetric> writes) {
        return connection.doto(c -> {
            final List<AsyncFuture<WriteResult>> futures = new ArrayList<>();

            for (final WriteMetric w : writes) {
                futures.add(doWrite(c, c.schema.writeSession(), w));
            }

            return async.collect(futures, WriteResult.merger());
        });
    }

    @Override
    public AsyncFuture<FetchData> fetch(MetricType source, Series series, final DateRange range,
            final FetchQuotaWatcher watcher) {
        if (source == MetricType.POINT) {
            return fetchDataPoints(series, range, watcher);
        }

        throw new IllegalArgumentException("unsupported source: " + source);
    }

    @Override
    public AsyncFuture<Void> start() {
        return connection.start();
    }

    @Override
    public AsyncFuture<Void> stop() {
        return connection.stop();
    }

    @Override
    public Iterable<BackendEntry> listEntries() {
        throw new IllegalStateException("#listEntries is not supported");
    }

    @Override
    public boolean isReady() {
        return connection.isReady();
    }

    @Override
    public AsyncFuture<BackendKeySet> keys(BackendKey start, final int limit, final QueryOptions options) {
        return connection.doto(c -> {
            final Optional<ByteBuffer> first = start == null ? Optional.empty()
                    : Optional.of(c.schema.rowKey().serialize(new MetricsRowKey(start.getSeries(), start.getBase())));

            final Statement stmt;
            final Transform<RowFetchResult<BackendKey>, BackendKeySet> converter;

            if (options.isTracing()) {
                final Stopwatch w = Stopwatch.createStarted();

                stmt = c.schema.keysPaging(first, limit).enableTracing();
                converter = result -> {
                    final QueryTrace trace = buildTrace(DatastaxBackend.class.getName() + "#keys",
                            w.elapsed(TimeUnit.NANOSECONDS), result.info);
                    return new BackendKeySet(result.data, Optional.of(trace));
                };
            } else {
                final Stopwatch w = Stopwatch.createStarted();

                stmt = c.schema.keysPaging(first, limit);
                converter = result -> {
                    final QueryTrace trace = new QueryTrace(DatastaxBackend.class.getName() + "#keys",
                            w.elapsed(TimeUnit.NANOSECONDS));
                    return new BackendKeySet(result.data, Optional.of(trace));
                };
            }

            final ResolvableFuture<BackendKeySet> future = async.future();

            Async.bind(async, c.session.executeAsync(stmt)).onDone(
                    new RowFetchHelper<BackendKey, BackendKeySet>(future, c.schema.keyConverter(), converter));

            return future;
        });
    }

    @Override
    public AsyncFuture<List<String>> serializeKeyToHex(final BackendKey key) {
        final MetricsRowKey rowKey = new MetricsRowKey(key.getSeries(), key.getBase());

        return connection.doto(c -> {
            return async.resolved(ImmutableList.of(Bytes.toHexString(c.schema.rowKey().serialize(rowKey))));
        });
    }

    @Override
    public AsyncFuture<List<BackendKey>> deserializeKeyFromHex(String key) {
        return connection.doto(c -> {
            final MetricsRowKey rowKey = c.schema.rowKey().deserialize(Bytes.fromHexString(key));
            return async.resolved(ImmutableList.of(new BackendKey(rowKey.getSeries(), rowKey.getBase())));
        });
    }

    private AsyncFuture<WriteResult> doWrite(final Connection c, final SchemaInstance.WriteSession session, final WriteMetric w) throws IOException {
        final List<Callable<AsyncFuture<Long>>> callables = new ArrayList<>();

        for (final MetricCollection g : w.getGroups()) {
            if (g.getType() == MetricType.POINT) {
                for (final Point d : g.getDataAs(Point.class)) {
                    final BoundStatement stmt = session.writePoint(w.getSeries(), d);

                    callables.add(() -> {
                        final long start = System.nanoTime();
                        return Async.bind(async, c.session.executeAsync(stmt)).directTransform((r) -> System.nanoTime() - start);
                    });
                }
            }
        }

        return async.eventuallyCollect(callables, new StreamCollector<Long, WriteResult>() {
            final ConcurrentLinkedQueue<Long> q = new ConcurrentLinkedQueue<Long>();

            @Override
            public void resolved(Long result) throws Exception {
                q.add(result);
            }

            @Override
            public void failed(Throwable cause) throws Exception {
            }

            @Override
            public void cancelled() throws Exception {
            }

            @Override
            public WriteResult end(int resolved, int failed, int cancelled) throws Exception {
                return WriteResult.of(q);
            }
        }, 500);
    }

    private QueryTrace buildTrace(final String what, final long elapsed, List<ExecutionInfo> info) {
        final ImmutableList.Builder<QueryTrace> top = ImmutableList.builder();

        for (final ExecutionInfo i : info) {
            final ImmutableList.Builder<QueryTrace> children = ImmutableList.builder();

            com.datastax.driver.core.QueryTrace qt = i.getQueryTrace();

            for (final com.datastax.driver.core.QueryTrace.Event e : qt.getEvents()) {
                final long eventElapsed = TimeUnit.NANOSECONDS.convert(e.getSourceElapsedMicros(),
                        TimeUnit.MICROSECONDS);
                children.add(new QueryTrace(e.getDescription(), eventElapsed));
            }

            top.add(new QueryTrace(qt.getTraceId().toString(),
                    TimeUnit.NANOSECONDS.convert(qt.getDurationMicros(), TimeUnit.MICROSECONDS), children.build()));
        }

        return new QueryTrace(what, elapsed, top.build());
    }

    private AsyncFuture<FetchData> fetchDataPoints(final Series series, DateRange range,
            final FetchQuotaWatcher watcher) {

        if (!watcher.mayReadData())
            throw new IllegalArgumentException("query violated data limit");

        final int limit = watcher.getReadDataQuota();

        return connection.doto(c -> {
            final List<PreparedFetch> prepared;

            try {
                prepared = c.schema.ranges(series, range);
            } catch (IOException e) {
                return async.failed(e);
            }

            final List<AsyncFuture<FetchData>> futures = new ArrayList<>();

            for (final Schema.PreparedFetch f : prepared) {
                final BoundStatement fetch = f.fetch(limit);

                final ResolvableFuture<FetchData> future = async.future();

                final long start = System.nanoTime();

                final RowFetchHelper<Point, FetchData> helper = new RowFetchHelper<Point, FetchData>(future, f.converter(), result -> {
                    final ImmutableList<Long> times = ImmutableList.of(System.nanoTime() - start);
                    final List<MetricCollection> groups = ImmutableList.of(MetricCollection.points(result.data));
                    return new FetchData(series, times, groups);
                });

                Async.bind(async, c.session.executeAsync(fetch)).onDone(helper);
                futures.add(future.onDone(reporter.reportFetch()));
            }

            return async.collect(futures, FetchData.merger(series));
        });
    }

    @RequiredArgsConstructor
    private final class RowFetchHelper<R, T> implements FutureDone<ResultSet> {
        private final List<R> data = new ArrayList<>();

        private final ResolvableFuture<T> future;
        private final Transform<Row, R> rowConverter;
        private final Transform<RowFetchResult<R>, T> endConverter;

        @Override
        public void failed(Throwable cause) throws Exception {
            future.fail(cause);
        }

        @Override
        public void cancelled() throws Exception {
            future.cancel();
        }

        @Override
        public void resolved(final ResultSet rows) throws Exception {
            if (future.isDone()) {
                return;
            }

            int count = rows.getAvailableWithoutFetching();

            final Optional<AsyncFuture<Void>> nextFetch = rows.isFullyFetched() ? Optional.empty()
                    : Optional.of(Async.bind(async, rows.fetchMoreResults()));

            while (count-- > 0) {
                final R part;

                try {
                    part = rowConverter.transform(rows.one());
                } catch (Exception e) {
                    future.fail(e);
                    return;
                }

                data.add(part);
            }

            if (nextFetch.isPresent()) {
                nextFetch.get().onDone(new FutureDone<Void>() {
                    @Override
                    public void failed(Throwable cause) throws Exception {
                        RowFetchHelper.this.failed(cause);
                    }

                    @Override
                    public void cancelled() throws Exception {
                        RowFetchHelper.this.cancelled();
                    }

                    @Override
                    public void resolved(Void result) throws Exception {
                        RowFetchHelper.this.resolved(rows);
                    }
                });

                return;
            }

            final T result;

            try {
                result = endConverter.transform(new RowFetchResult<>(rows.getAllExecutionInfo(), data));
            } catch (final Exception e) {
                future.fail(e);
                return;
            }

            future.resolve(result);
        }
    }

    @Data
    private static class RowFetchResult<T> {
        final List<ExecutionInfo> info;
        final List<T> data;
    }
}