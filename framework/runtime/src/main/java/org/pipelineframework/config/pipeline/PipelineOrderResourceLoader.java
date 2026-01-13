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

package org.pipelineframework.config.pipeline;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Loads the pipeline execution order from the generated resource.
 */
public final class PipelineOrderResourceLoader {

    private static final String RESOURCE = "META-INF/pipeline/order.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PipelineOrderResourceLoader() {
    }

    public static Optional<List<String>> loadOrder() {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        if (classLoader == null) {
            classLoader = PipelineOrderResourceLoader.class.getClassLoader();
        }
        try (InputStream stream = classLoader.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                return Optional.empty();
            }
            Map<?, ?> data = MAPPER.readValue(stream, Map.class);
            Object value = data.get("order");
            if (!(value instanceof List<?> list)) {
                throw new IllegalStateException("Pipeline order resource is missing an order array.");
            }
            List<String> order = list.stream()
                .filter(item -> item != null && !item.toString().isBlank())
                .map(Object::toString)
                .toList();
            return Optional.of(order);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read pipeline order resource.", e);
        }
    }
}
