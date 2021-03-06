/*
 * Copyright (c) 2019 Spotify AB.
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

package com.spotify.heroic.aggregation.simple

import com.spotify.heroic.ObjectHasher
import com.spotify.heroic.metric.MetricCollection
import com.spotify.heroic.metric.MetricType
import com.spotify.heroic.metric.Point

data class FilterPointsThresholdStrategy(
    val filterType: FilterKThresholdType,
    val threshold: Double
) : MetricMappingStrategy {

    override fun apply(metrics: MetricCollection): MetricCollection {
        return if (metrics.type == MetricType.POINT) {
            MetricCollection.build(MetricType.POINT,
                    filterWithThreshold(metrics.getDataAs(Point::class.java)))
        } else {
            metrics
        }
    }

    override fun hashTo(hasher: ObjectHasher) {
        hasher.putObject(javaClass) {
            hasher.putField("filterType", filterType, hasher.enumValue<FilterKThresholdType>())
            hasher.putField("threshold", threshold, hasher.doubleValue())
        }
    }

    private fun filterWithThreshold(points: List<Point>): List<Point> {
        return points.filter { point -> filterType.predicate(point.value, threshold) }
    }
}
