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

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import org.pipelineframework.PipelineExecutionService;
import org.pipelineframework.search.grpc.CrawlSourceSvc;

@Path("/pipeline")
@ApplicationScoped
public class PipelineRunResource {

    @Inject
    PipelineExecutionService pipelineExecutionService;

    @POST
    @Path("/run")
    @Consumes(MediaType.TEXT_PLAIN)
    @Produces(MediaType.TEXT_PLAIN)
    public Uni<String> run(String input) {
        if (input == null || input.isBlank()) {
            return Uni.createFrom().item("Input parameter is required");
        }

        String stableId = UUID.nameUUIDFromBytes(input.getBytes(StandardCharsets.UTF_8)).toString();
        CrawlSourceSvc.CrawlRequest request = CrawlSourceSvc.CrawlRequest.newBuilder()
            .setDocId(stableId)
            .setSourceUrl(input)
            .build();

        return pipelineExecutionService.executePipeline(Multi.createFrom().item(request))
            .collect().asList()
            .onItem().transform(results -> "Pipeline execution completed: " + results.size());
    }

    @GET
    @Path("/health")
    @Produces(MediaType.TEXT_PLAIN)
    public Uni<String> health() {
        PipelineExecutionService.StartupHealthState state = pipelineExecutionService.getStartupHealthState();
        String message;
        switch (state) {
            case HEALTHY -> message = "Pipeline orchestrator healthy";
            case PENDING -> message = "Pipeline orchestrator health checks are still running";
            case UNHEALTHY -> message = "Dependent services are unhealthy";
            case ERROR -> message = "Health check failed with an error";
            default -> message = "Unknown health status";
        }
        return Uni.createFrom().item(message);
    }
}
