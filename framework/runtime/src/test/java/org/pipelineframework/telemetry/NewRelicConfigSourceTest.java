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

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NewRelicConfigSourceTest {

    @Test
    void returnsEmptyPropertiesWithoutLicenseKey() {
        NewRelicConfigSource source = new NewRelicConfigSource(Map.of());
        assertTrue(source.getProperties().isEmpty());
    }

    @Test
    void buildsPropertiesFromEnvironment() {
        NewRelicConfigSource source = new NewRelicConfigSource(Map.of(
            "NEW_RELIC_LICENSE_KEY", "nr-key",
            "NEW_RELIC_OTLP_ENDPOINT", "https://otlp.us.nr-data.net:443",
            "NEW_RELIC_METRIC_EXPORT_INTERVAL", "30s"));

        assertEquals("true", source.getValue("quarkus.otel.enabled"));
        assertEquals("https://otlp.us.nr-data.net:443", source.getValue("quarkus.otel.exporter.otlp.endpoint"));
        assertEquals("30s", source.getValue("quarkus.otel.metric.export.interval"));
        assertEquals("api-key=nr-key", source.getValue("quarkus.otel.exporter.otlp.headers"));
        assertEquals("false", source.getValue("quarkus.observability.lgtm.enabled"));
    }
}
