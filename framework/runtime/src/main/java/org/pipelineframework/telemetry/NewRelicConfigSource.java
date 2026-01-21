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

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.eclipse.microprofile.config.spi.ConfigSource;

public class NewRelicConfigSource implements ConfigSource {

    private static final String LICENSE_ENV = "NEW_RELIC_LICENSE_KEY";
    private static final String ENDPOINT_ENV = "NEW_RELIC_OTLP_ENDPOINT";
    private static final String DEFAULT_ENDPOINT = "https://otlp.eu01.nr-data.net:443";

    private final Map<String, String> properties;

    /**
     * Initialises the config source and populates its properties from the environment.
     *
     * The properties map is constructed from the NEW_RELIC_LICENSE_KEY and, optionally,
     * NEW_RELIC_OTLP_ENDPOINT environment variables to configure OpenTelemetry/OTLP settings.
     */
    public NewRelicConfigSource() {
        this.properties = buildProperties();
    }

    /**
     * Provide all configuration properties exposed by this config source.
     *
     * @return an unmodifiable map of configuration property names to their string values
     */
    @Override
    public Map<String, String> getProperties() {
        return properties;
    }

    /**
     * Provide the set of property names exposed by this config source.
     *
     * @return the set of property names available from this config source.
     */
    @Override
    public Set<String> getPropertyNames() {
        return this.properties.keySet();
    }

    /**
     * Retrieve the configured value for a given property name.
     *
     * @param propertyName the configuration property key to look up
     * @return the property value, or `null` if the property is not present
     */
    @Override
    public String getValue(String propertyName) {
        return properties.get(propertyName);
    }

    /**
     * Provide the name of this configuration source.
     *
     * @return the config source name "pipelineframework-newrelic-config"
     */
    @Override
    public String getName() {
        return "pipelineframework-newrelic-config";
    }

    /**
     * Provide the config source ordinal used to determine precedence.
     *
     * @return the ordinal value for this ConfigSource (200)
     */
    @Override
    public int getOrdinal() {
        return 200;
    }

    /**
     * Builds the configuration properties used to enable and configure Quarkus OpenTelemetry/OTLP export to New Relic when a license key is available.
     *
     * @return an unmodifiable map of Quarkus OpenTelemetry/OTLP configuration properties when the New Relic license key environment variable is set and non-blank; an empty map otherwise
     */
    private Map<String, String> buildProperties() {
        String licenseKey = System.getenv(LICENSE_ENV);
        if (licenseKey == null || licenseKey.isBlank()) {
            return Collections.emptyMap();
        }
        Map<String, String> values = new HashMap<>();
        values.put("quarkus.otel.enabled", "true");
        values.put("quarkus.otel.traces.enabled", "true");
        values.put("quarkus.otel.metrics.enabled", "true");
        values.put("quarkus.otel.metric.export.interval", "5s");
        values.put("quarkus.otel.traces.sampler", "parentbased_traceidratio");
        values.put("quarkus.otel.traces.sampler.arg", "0.1");
        values.put("quarkus.otel.exporter.otlp.endpoint", resolveEndpoint());
        values.put("quarkus.otel.exporter.otlp.protocol", "http/protobuf");
        values.put("quarkus.otel.exporter.otlp.compression", "gzip");
        values.put("quarkus.otel.exporter.otlp.metrics.temporality.preference", "delta");
        values.put("quarkus.otel.exporter.otlp.headers", "api-key=" + licenseKey);
        values.put("quarkus.observability.lgtm.enabled", "false");
        return Collections.unmodifiableMap(values);
    }

    /**
     * Resolve the OTLP endpoint URL used for exporting telemetry to New Relic.
     *
     * Reads the {@code NEW_RELIC_OTLP_ENDPOINT} environment variable and falls back to the default
     * endpoint when the variable is unset or contains only whitespace.
     *
     * @return {@code DEFAULT_ENDPOINT} if the environment variable is unset or blank, otherwise the
     *         endpoint string provided by the environment variable
     */
    private String resolveEndpoint() {
        String endpoint = System.getenv(ENDPOINT_ENV);
        if (endpoint == null || endpoint.isBlank()) {
            return DEFAULT_ENDPOINT;
        }
        return endpoint;
    }
}