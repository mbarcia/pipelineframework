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

import java.util.List;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class GrpcClientTracingTest {

    @Test
    void emitsUnaryClientSpan() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(exporter))
            .build();
        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
            .setTracerProvider(tracerProvider)
            .build();
        GlobalOpenTelemetry.resetForTest();
        GlobalOpenTelemetry.set(sdk);

        try {
            Uni<String> result = GrpcClientTracing.traceUnary(
                "ProcessPaymentStatusService",
                "remoteProcess",
                Uni.createFrom().item("ok"));

            result.await().indefinitely();

            List<SpanData> spans = exporter.getFinishedSpanItems();
            SpanData span = spans.getFirst();
            assertEquals(SpanKind.CLIENT, span.getKind());
            assertEquals("grpc", span.getAttributes().get(AttributeKey.stringKey("rpc.system")));
            assertEquals("ProcessPaymentStatusService",
                span.getAttributes().get(AttributeKey.stringKey("rpc.service")));
            assertEquals("remoteProcess",
                span.getAttributes().get(AttributeKey.stringKey("rpc.method")));
            assertNotNull(span.getAttributes().get(AttributeKey.longKey("rpc.grpc.status_code")));
        } finally {
            tracerProvider.shutdown();
            GlobalOpenTelemetry.resetForTest();
        }
    }

    @Test
    void emitsMultiClientSpan() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(exporter))
            .build();
        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
            .setTracerProvider(tracerProvider)
            .build();
        GlobalOpenTelemetry.resetForTest();
        GlobalOpenTelemetry.set(sdk);

        try {
            Multi<String> result = GrpcClientTracing.traceMulti(
                "ProcessPaymentStatusService",
                "remoteProcess",
                Multi.createFrom().items("a", "b"));

            result.collect().asList().await().indefinitely();

            List<SpanData> spans = exporter.getFinishedSpanItems();
            SpanData span = spans.getFirst();
            assertEquals(SpanKind.CLIENT, span.getKind());
            assertEquals("grpc", span.getAttributes().get(AttributeKey.stringKey("rpc.system")));
            assertEquals("ProcessPaymentStatusService",
                span.getAttributes().get(AttributeKey.stringKey("rpc.service")));
        } finally {
            tracerProvider.shutdown();
            GlobalOpenTelemetry.resetForTest();
        }
    }
}
