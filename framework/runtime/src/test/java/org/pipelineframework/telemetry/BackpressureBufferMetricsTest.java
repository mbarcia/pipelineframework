/*
 * Copyright (c) 2023-2025 Mariano Barcia
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.pipelineframework.telemetry;

import java.util.Collection;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import io.smallrye.mutiny.Multi;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BackpressureBufferMetricsTest {

    @Test
    void recordsBufferDepthAndCapacityGauges() {
        System.setProperty("pipeline.telemetry.enabled", "true");
        System.setProperty("pipeline.telemetry.metrics.enabled", "true");

        InMemoryMetricReader metricReader = InMemoryMetricReader.create();
        SdkMeterProvider meterProvider = SdkMeterProvider.builder()
            .registerMetricReader(metricReader)
            .build();
        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
            .setMeterProvider(meterProvider)
            .build();
        GlobalOpenTelemetry.resetForTest();
        GlobalOpenTelemetry.set(sdk);

        try {
            Multi<Integer> input = Multi.createFrom().items(1, 2, 3);
            Multi<Integer> buffered =
                BackpressureBufferMetrics.buffer(input, DummyStep.class, 4);

            buffered.collect().asList().await().indefinitely();

            Collection<MetricData> metrics = metricReader.collectAllMetrics();
            MetricData queued = metrics.stream()
                .filter(metric -> "tpf.step.buffer.queued".equals(metric.getName()))
                .findFirst()
                .orElseThrow();
            MetricData capacity = metrics.stream()
                .filter(metric -> "tpf.step.buffer.capacity".equals(metric.getName()))
                .findFirst()
                .orElseThrow();

            String stepClass = DummyStep.class.getName();
            assertEquals(1, queued.getLongGaugeData().getPoints().stream()
                .filter(point -> stepClass.equals(point.getAttributes()
                    .get(AttributeKey.stringKey("tpf.step.class"))))
                .count());
            assertEquals(1, capacity.getLongGaugeData().getPoints().stream()
                .filter(point -> stepClass.equals(point.getAttributes()
                    .get(AttributeKey.stringKey("tpf.step.class"))))
                .count());

            long capacityValue = capacity.getLongGaugeData().getPoints().stream()
                .filter(point -> stepClass.equals(point.getAttributes()
                    .get(AttributeKey.stringKey("tpf.step.class"))))
                .findFirst()
                .orElseThrow()
                .getValue();
            assertEquals(4L, capacityValue);
        } finally {
            meterProvider.shutdown();
            GlobalOpenTelemetry.resetForTest();
            System.clearProperty("pipeline.telemetry.enabled");
            System.clearProperty("pipeline.telemetry.metrics.enabled");
        }
    }

    static final class DummyStep {
    }
}
