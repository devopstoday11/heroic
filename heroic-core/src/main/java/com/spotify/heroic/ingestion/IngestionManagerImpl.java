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

package com.spotify.heroic.ingestion;

import static com.google.common.base.Preconditions.checkNotNull;

import com.spotify.heroic.common.Statistics;
import com.spotify.heroic.filter.Filter;
import com.spotify.heroic.metadata.MetadataBackend;
import com.spotify.heroic.metadata.MetadataManager;
import com.spotify.heroic.metric.MetricBackend;
import com.spotify.heroic.metric.MetricManager;
import com.spotify.heroic.statistics.IngestionManagerReporter;
import com.spotify.heroic.suggest.SuggestBackend;
import com.spotify.heroic.suggest.SuggestManager;
import eu.toolchain.async.AsyncFramework;
import eu.toolchain.async.AsyncFuture;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Named;

public class IngestionManagerImpl implements IngestionManager {
    final AsyncFramework async;
    final MetadataManager metadata;
    final MetricManager metric;
    final SuggestManager suggest;
    final IngestionManagerReporter reporter;

    private final boolean updateMetrics;
    private final boolean updateMetadata;
    private final boolean updateSuggestions;

    private volatile Filter filter;

    private final Semaphore writePermits;

    private final LongAdder ingested = new LongAdder();

    /**
     * @param updateMetrics Ingested metrics will update metric backends.
     * @param updateMetadata Ingested metrics will update metadata backends.
     * @param updateSuggestions Ingested metrics will update suggest backends.
     * @param maxConcurrentWrites Limit the number of concurrent writes, 0 means no limit at all
     */
    @Inject
    public IngestionManagerImpl(
        final AsyncFramework async,
        final MetadataManager metadata,
        final MetricManager metric,
        final SuggestManager suggest,
        final IngestionManagerReporter reporter,
        @Named("updateMetrics") final boolean updateMetrics,
        @Named("updateMetadata") final boolean updateMetadata,
        @Named("updateSuggestions") final boolean updateSuggestions,
        @Named("maxConcurrentWrites") final int maxConcurrentWrites,
        final Filter filter
    ) {
        this.async = async;
        this.metadata = metadata;
        this.metric = metric;
        this.suggest = suggest;
        this.reporter = reporter;

        this.updateMetrics = updateMetrics;
        this.updateMetadata = updateMetadata;
        this.updateSuggestions = updateSuggestions;
        this.filter = filter;

        this.writePermits = new Semaphore(maxConcurrentWrites);
    }

    @Override
    public IngestionGroup useOptionalGroup(final Optional<String> group) {
        return buildGroup(group, metric::useOptionalGroup, metadata::useOptionalGroup,
            suggest::useOptionalGroup);
    }

    @Override
    public AsyncFuture<Void> setFilter(Filter filter) {
        this.filter = checkNotNull(filter, "filter");
        return async.resolved();
    }

    @Override
    public AsyncFuture<Filter> getFilter() {
        return async.resolved(filter);
    }

    @Override
    public Statistics getStatistics() {
        return new Statistics(INGESTED, ingested.sum(), AVAILABLE_WRITE_PERMITS,
            writePermits.availablePermits());
    }

    private <I> IngestionGroup buildGroup(
        final I input,
        Function<I, MetricBackend> metric,
        Function<I, MetadataBackend> metadata,
        Function<I, SuggestBackend> suggest
    ) {
        // @formatter:off
        return new CoreIngestionGroup(
            async,
            () -> filter,
            writePermits,
            reporter,
            ingested,

            optionally(updateMetrics, () -> metric.apply(input)),
            optionally(updateMetadata, () -> metadata.apply(input)),
            optionally(updateSuggestions, () -> suggest.apply(input))
        );
        // @formatter:on
    }

    private <T> Optional<T> optionally(final boolean shouldSupply, final Supplier<T> supplier) {
        if (!shouldSupply) {
            return Optional.empty();
        }

        return Optional.of(supplier.get());
    }
}
