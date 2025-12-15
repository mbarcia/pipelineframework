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

package org.pipelineframework.grpc;

import io.smallrye.mutiny.Uni;
import org.pipelineframework.service.ReactiveService;
import org.pipelineframework.service.throwStatusRuntimeExceptionFunction;

/**
 * Adapter for gRPC reactive services that handle 1-1 (one-to-one) cardinality.
 * This adapter takes a single input message and returns a single output message, suitable
 * for unary gRPC scenarios.
 *
 * @param <GrpcIn> the gRPC input message type
 * @param <GrpcOut> the gRPC output message type
 * @param <DomainIn> the domain input object type
 * @param <DomainOut> the domain output object type
 */
public abstract class GrpcReactiveServiceAdapter<GrpcIn, GrpcOut, DomainIn, DomainOut> extends ReactiveServiceAdapterBase {

  /**
   * Default constructor for GrpcReactiveServiceAdapter.
   */
  public GrpcReactiveServiceAdapter() {
  }

  /**
   * Provides the reactive service responsible for processing domain inputs into domain outputs.
   *
   * @return the ReactiveService that processes DomainIn to produce DomainOut.
   */
  protected abstract ReactiveService<DomainIn, DomainOut> getService();

  /**
   * Convert a gRPC input object into the corresponding domain input representation.
   *
   * @param grpcIn the gRPC input object to convert
   * @return the resulting domain input object
   */
  protected abstract DomainIn fromGrpc(GrpcIn grpcIn);

  /**
   * Convert a domain-layer output value into its gRPC representation.
   *
   * @param domainOut the domain-layer result to convert
   * @return the corresponding gRPC output instance
   */
  protected abstract GrpcOut toGrpc(DomainOut domainOut);

  /**
   * Process a gRPC request through the reactive domain service.
   * <p>
   * Converts the provided gRPC request to a domain input, invokes the underlying reactive service,
   * and converts the resulting domain output back to a gRPC response.
   *
   * @param grpcRequest the incoming gRPC request to convert and process
   * @return the gRPC response message corresponding to the processed domain result
   * @throws io.grpc.StatusRuntimeException if processing fails; failures are mapped to an appropriate gRPC status
   */
  public Uni<GrpcOut> remoteProcess(GrpcIn grpcRequest) {
    DomainIn entity = fromGrpc(grpcRequest);
    // Process the entity using the domain service
    Uni<DomainOut> processedResult = getService().process(entity);

    return processedResult
            .onItem().transform(this::toGrpc)
            .onFailure().transform(new throwStatusRuntimeExceptionFunction());
  }
}