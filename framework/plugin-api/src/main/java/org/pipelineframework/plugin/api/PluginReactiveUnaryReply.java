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

package org.pipelineframework.plugin.api;

import io.smallrye.mutiny.Uni;

/**
 * Plugin interface for reactive unary processing operations that return a result.
 * This interface is used for plugins that process a single input and return a result.
 *
 * @param <T> the type of input object to process
 * @param <R> the type of result to return
 */
@FunctionalInterface
public interface PluginReactiveUnaryReply<T, R> extends BasePlugin {
    /**
     * Process the input object asynchronously and return a Uni containing the result.
     *
     * @param item the input object to process
     * @return a Uni that emits the processed result
     */
    Uni<R> process(T item);
}