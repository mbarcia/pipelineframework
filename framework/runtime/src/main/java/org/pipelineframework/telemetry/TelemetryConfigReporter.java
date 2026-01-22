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

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

import io.quarkus.runtime.StartupEvent;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;

@ApplicationScoped
public class TelemetryConfigReporter {

    private static final Logger logger = Logger.getLogger(TelemetryConfigReporter.class);
    private static final String LICENSE_ENV = "NEW_RELIC_LICENSE_KEY";

    void onStart(@Observes StartupEvent event) {
        String licenseKey = System.getenv(LICENSE_ENV);
        if (licenseKey == null || licenseKey.isBlank()) {
            return;
        }
        Config config = ConfigProvider.getConfig();
        String enabled = config.getOptionalValue("quarkus.otel.enabled", String.class).orElse("<unset>");
        String endpoint = config.getOptionalValue("quarkus.otel.exporter.otlp.endpoint", String.class).orElse("<unset>");
        String headers = config.getOptionalValue("quarkus.otel.exporter.otlp.headers", String.class).orElse("<unset>");
        String headersHint = headers.isBlank() || "<unset>".equals(headers) ? "<unset>" : "<redacted>";

        logger.infof(
            "NR telemetry config: quarkus.otel.enabled=%s, otlp.endpoint=%s, otlp.headers=%s",
            enabled,
            endpoint,
            headersHint
        );
    }
}
