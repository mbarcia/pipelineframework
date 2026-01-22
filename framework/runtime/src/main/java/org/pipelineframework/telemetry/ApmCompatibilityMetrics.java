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

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleCounter;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;

/**
 * Emits New Relic APM-compatible metrics for orchestrator CLI runs.
 *
 * <p>These metrics are a compatibility shim when RPC metrics do not
 * synthesize APM transactions for short-lived orchestrator runs.</p>
 */
public final class ApmCompatibilityMetrics {

    private static final Meter METER = GlobalOpenTelemetry.getMeter("org.pipelineframework.apm");

    private static final LongCounter TRANSACTION_COUNT =
        METER.counterBuilder("apm.service.transaction.count").build();
    private static final LongCounter ERROR_COUNT =
        METER.counterBuilder("apm.service.error.count").build();
    private static final DoubleCounter TRANSACTION_DURATION =
        METER.counterBuilder("apm.service.transaction.duration").setUnit("ms").ofDoubles().build();

    private static final AttributeKey<String> TRANSACTION_TYPE = AttributeKey.stringKey("transaction.type");
    private static final AttributeKey<String> TRANSACTION_NAME = AttributeKey.stringKey("transaction.name");

    private ApmCompatibilityMetrics() {
    }

    /**
     * Record a successful orchestrator transaction duration.
     *
     * @param durationMs duration in milliseconds
     */
    public static void recordOrchestratorSuccess(double durationMs) {
        record(durationMs, false);
    }

    /**
     * Record a failed orchestrator transaction duration.
     *
     * @param durationMs duration in milliseconds
     */
    public static void recordOrchestratorFailure(double durationMs) {
        record(durationMs, true);
    }

    private static void record(double durationMs, boolean error) {
        Attributes attributes = Attributes.builder()
            .put(TRANSACTION_TYPE, "Other")
            .put(TRANSACTION_NAME, "OtherTransaction/OrchestratorService/Run")
            .build();
        TRANSACTION_COUNT.add(1, attributes);
        TRANSACTION_DURATION.add(durationMs, attributes);
        if (error) {
            ERROR_COUNT.add(1, attributes);
        }
    }
}
