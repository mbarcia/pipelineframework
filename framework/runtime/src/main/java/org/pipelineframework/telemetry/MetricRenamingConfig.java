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

import java.util.ArrayList;
import java.util.List;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.config.MeterFilter;
import io.quarkus.runtime.Startup;

@ApplicationScoped
public class MetricRenamingConfig {

    @Produces
    @Startup
    public MeterFilter grpcRenameFilter() {
        return new MeterFilter() {
            @Override
            public Meter.Id map(Meter.Id id) {
                String name = id.getName();
                if (name == null || !name.startsWith("grpc.server.")) {
                    return id;
                }
                String mappedName = mapGrpcName(name);
                List<Tag> renamedTags = renameGrpcTags(id.getTags());
                return id.withName(mappedName).replaceTags(renamedTags);
            }
        };
    }

    private static String mapGrpcName(String name) {
        return switch (name) {
            case "grpc.server.processing.duration" -> "rpc.server.duration";
            case "grpc.server.processing.duration.max" -> "rpc.server.duration.max";
            case "grpc.server.requests.received" -> "rpc.server.request.count";
            case "grpc.server.responses.sent" -> "rpc.server.response.count";
            default -> name;
        };
    }

    private static List<Tag> renameGrpcTags(List<Tag> tags) {
        List<Tag> renamed = new ArrayList<>(tags.size() + 1);
        boolean hasRpcSystem = false;
        for (Tag tag : tags) {
            String key = tag.getKey();
            String value = tag.getValue();
            if ("service".equals(key)) {
                key = "rpc.service";
            } else if ("method".equals(key)) {
                key = "rpc.method";
            } else if ("grpc.status".equals(key)) {
                key = "rpc.grpc.status_code";
            } else if ("rpc.system".equals(key)) {
                hasRpcSystem = true;
            }
            renamed.add(Tag.of(key, value));
        }
        if (!hasRpcSystem) {
            renamed.add(Tag.of("rpc.system", "grpc"));
        }
        return renamed;
    }
}
