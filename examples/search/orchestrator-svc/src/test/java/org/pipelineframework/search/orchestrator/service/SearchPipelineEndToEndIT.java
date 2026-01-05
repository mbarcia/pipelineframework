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

package org.pipelineframework.search.orchestrator.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import io.quarkus.test.junit.QuarkusIntegrationTest;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;

import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusIntegrationTest
class SearchPipelineEndToEndIT {

    private static final Logger LOG = Logger.getLogger(SearchPipelineEndToEndIT.class);
    private static final Network NETWORK = Network.newNetwork();

    private static final String CRAWL_IMAGE = System.getProperty(
        "search.image.crawl-source", "localhost/search-pipeline/crawl-source-svc:latest");
    private static final String PARSE_IMAGE = System.getProperty(
        "search.image.parse-document", "localhost/search-pipeline/parse-document-svc:latest");
    private static final String TOKENIZE_IMAGE = System.getProperty(
        "search.image.tokenize-content", "localhost/search-pipeline/tokenize-content-svc:latest");
    private static final String INDEX_IMAGE = System.getProperty(
        "search.image.index-document", "localhost/search-pipeline/index-document-svc:latest");

    private static final GenericContainer<?> crawlService =
        new GenericContainer<>(CRAWL_IMAGE)
            .withNetwork(NETWORK)
            .withNetworkAliases("crawl-source-svc")
            .withExposedPorts(8080, 8444)
            .withEnv("QUARKUS_PROFILE", "test")
            .withEnv("QUARKUS_GRPC_SERVER_PORT", "8444")
            .withEnv("QUARKUS_GRPC_SERVER_PLAINTEXT", "true")
            .waitingFor(
                Wait.forHttps("/q/health")
                    .forPort(8444)
                    .allowInsecure()
                    .withStartupTimeout(Duration.ofSeconds(60)));

    private static final GenericContainer<?> parseService =
        new GenericContainer<>(PARSE_IMAGE)
            .withNetwork(NETWORK)
            .withNetworkAliases("parse-document-svc")
            .withExposedPorts(8080, 8445)
            .withEnv("QUARKUS_PROFILE", "test")
            .withEnv("QUARKUS_GRPC_SERVER_PORT", "8445")
            .withEnv("QUARKUS_GRPC_SERVER_PLAINTEXT", "true")
            .waitingFor(
                Wait.forHttps("/q/health")
                    .forPort(8445)
                    .allowInsecure()
                    .withStartupTimeout(Duration.ofSeconds(60)));

    private static final GenericContainer<?> tokenizeService =
        new GenericContainer<>(TOKENIZE_IMAGE)
            .withNetwork(NETWORK)
            .withNetworkAliases("tokenize-content-svc")
            .withExposedPorts(8080, 8446)
            .withEnv("QUARKUS_PROFILE", "test")
            .withEnv("QUARKUS_GRPC_SERVER_PORT", "8446")
            .withEnv("QUARKUS_GRPC_SERVER_PLAINTEXT", "true")
            .waitingFor(
                Wait.forHttps("/q/health")
                    .forPort(8446)
                    .allowInsecure()
                    .withStartupTimeout(Duration.ofSeconds(60)));

    private static final GenericContainer<?> indexService =
        new GenericContainer<>(INDEX_IMAGE)
            .withNetwork(NETWORK)
            .withNetworkAliases("index-document-svc")
            .withExposedPorts(8080, 8447)
            .withEnv("QUARKUS_PROFILE", "test")
            .withEnv("QUARKUS_GRPC_SERVER_PORT", "8447")
            .withEnv("QUARKUS_GRPC_SERVER_PLAINTEXT", "true")
            .waitingFor(
                Wait.forHttps("/q/health")
                    .forPort(8447)
                    .allowInsecure()
                    .withStartupTimeout(Duration.ofSeconds(60)));

    @BeforeAll
    static void startServices() {
        crawlService.start();
        parseService.start();
        tokenizeService.start();
        indexService.start();
    }

    @AfterAll
    static void stopServices() {
        indexService.stop();
        tokenizeService.stop();
        parseService.stop();
        crawlService.stop();
    }

    @Test
    void fullPipelineWorks() throws Exception {
        ProcessResult result = orchestratorTriggerRun("https://example.com");
        assertTrue(result.exitCode == 0, "Expected orchestrator exit code 0, got " + result.exitCode);
        assertTrue(result.output.contains("Pipeline execution completed"), "Expected completion message");
    }

    private ProcessResult orchestratorTriggerRun(String input) throws Exception {
        ProcessBuilder pb =
            new ProcessBuilder(
                "java",
                "--enable-preview",
                "-jar",
                "target/quarkus-app/quarkus-run.jar",
                "-i=" + input);

        pb.environment().put("QUARKUS_PROFILE", "test");
        pb.environment().put("QUARKUS_GRPC_CLIENTS_CRAWL_SOURCE_HOST", crawlService.getHost());
        pb.environment().put("QUARKUS_GRPC_CLIENTS_CRAWL_SOURCE_PORT", String.valueOf(crawlService.getMappedPort(8444)));
        pb.environment().put("QUARKUS_GRPC_CLIENTS_CRAWL_SOURCE_PLAIN_TEXT", "true");
        pb.environment().put("QUARKUS_GRPC_CLIENTS_CRAWL_SOURCE_TLS_ENABLED", "false");

        pb.environment().put("QUARKUS_GRPC_CLIENTS_PARSE_DOCUMENT_HOST", parseService.getHost());
        pb.environment().put("QUARKUS_GRPC_CLIENTS_PARSE_DOCUMENT_PORT", String.valueOf(parseService.getMappedPort(8445)));
        pb.environment().put("QUARKUS_GRPC_CLIENTS_PARSE_DOCUMENT_PLAIN_TEXT", "true");
        pb.environment().put("QUARKUS_GRPC_CLIENTS_PARSE_DOCUMENT_TLS_ENABLED", "false");

        pb.environment().put("QUARKUS_GRPC_CLIENTS_TOKENIZE_CONTENT_HOST", tokenizeService.getHost());
        pb.environment().put("QUARKUS_GRPC_CLIENTS_TOKENIZE_CONTENT_PORT", String.valueOf(tokenizeService.getMappedPort(8446)));
        pb.environment().put("QUARKUS_GRPC_CLIENTS_TOKENIZE_CONTENT_PLAIN_TEXT", "true");
        pb.environment().put("QUARKUS_GRPC_CLIENTS_TOKENIZE_CONTENT_TLS_ENABLED", "false");

        pb.environment().put("QUARKUS_GRPC_CLIENTS_INDEX_DOCUMENT_HOST", indexService.getHost());
        pb.environment().put("QUARKUS_GRPC_CLIENTS_INDEX_DOCUMENT_PORT", String.valueOf(indexService.getMappedPort(8447)));
        pb.environment().put("QUARKUS_GRPC_CLIENTS_INDEX_DOCUMENT_PLAIN_TEXT", "true");
        pb.environment().put("QUARKUS_GRPC_CLIENTS_INDEX_DOCUMENT_TLS_ENABLED", "false");

        pb.redirectErrorStream(true);
        Process process = pb.start();
        String output = readOutput(process);
        boolean finished = process.waitFor(90, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException("Orchestrator did not finish in time");
        }
        int exitCode = process.exitValue();
        LOG.infof("Orchestrator output:%n%s", output);
        return new ProcessResult(exitCode, output);
    }

    private String readOutput(Process process) throws IOException {
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append(System.lineSeparator());
            }
        }
        return builder.toString();
    }

    private record ProcessResult(int exitCode, String output) {
    }
}
