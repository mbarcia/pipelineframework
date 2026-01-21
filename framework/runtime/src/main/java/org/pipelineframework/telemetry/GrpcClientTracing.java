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

import java.util.concurrent.atomic.AtomicReference;

import io.grpc.Status;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;

/**
 * Adds OpenTelemetry gRPC client spans around reactive calls.
 */
public final class GrpcClientTracing {

    private static final Tracer TRACER = GlobalOpenTelemetry.getTracer("org.pipelineframework.grpc.client");
    private static final AttributeKey<String> RPC_SYSTEM = AttributeKey.stringKey("rpc.system");
    private static final AttributeKey<String> RPC_SERVICE = AttributeKey.stringKey("rpc.service");
    private static final AttributeKey<String> RPC_METHOD = AttributeKey.stringKey("rpc.method");
    private static final AttributeKey<Long> RPC_GRPC_STATUS = AttributeKey.longKey("rpc.grpc.status_code");

    private GrpcClientTracing() {
    }

    public static <T> Uni<T> traceUnary(String service, String method, Uni<T> uni) {
        if (service == null || method == null) {
            return uni;
        }
        return Uni.createFrom().deferred(() -> {
            Span span = startSpan(service, method);
            Scope scope = span.makeCurrent();
            return uni.onItemOrFailure().invoke((item, failure) -> {
                endSpan(span, failure);
                scope.close();
            });
        });
    }

    public static <T> Multi<T> traceMulti(String service, String method, Multi<T> multi) {
        if (service == null || method == null) {
            return multi;
        }
        return Multi.createFrom().deferred(() -> {
            Span span = startSpan(service, method);
            Scope scope = span.makeCurrent();
            AtomicReference<Throwable> failureRef = new AtomicReference<>();
            return multi.onFailure().invoke(failureRef::set)
                .onTermination().invoke(() -> {
                    endSpan(span, failureRef.get());
                    scope.close();
                });
        });
    }

    private static Span startSpan(String service, String method) {
        return TRACER.spanBuilder(service + "/" + method)
            .setSpanKind(SpanKind.CLIENT)
            .setAttribute(RPC_SYSTEM, "grpc")
            .setAttribute(RPC_SERVICE, service)
            .setAttribute(RPC_METHOD, method)
            .startSpan();
    }

    private static void endSpan(Span span, Throwable failure) {
        if (span == null) {
            return;
        }
        Status.Code statusCode = Status.Code.OK;
        if (failure != null) {
            span.recordException(failure);
            span.setStatus(StatusCode.ERROR);
            statusCode = Status.fromThrowable(failure).getCode();
        }
        span.setAttribute(RPC_GRPC_STATUS, (long) statusCode.value());
        span.end();
    }
}
