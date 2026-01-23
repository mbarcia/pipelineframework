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
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class TelemetryFlushTest {

    @Test
    void flushDoesNotThrowWithoutSdk() {
        GlobalOpenTelemetry.resetForTest();
        assertDoesNotThrow(TelemetryFlush::flush);
    }

    @Test
    void flushDoesNotThrowWithSdk() {
        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
            .setTracerProvider(SdkTracerProvider.builder().build())
            .setMeterProvider(SdkMeterProvider.builder().build())
            .build();
        GlobalOpenTelemetry.resetForTest();
        GlobalOpenTelemetry.set(sdk);

        try {
            assertDoesNotThrow(TelemetryFlush::flush);
        } finally {
            GlobalOpenTelemetry.resetForTest();
        }
    }
}
