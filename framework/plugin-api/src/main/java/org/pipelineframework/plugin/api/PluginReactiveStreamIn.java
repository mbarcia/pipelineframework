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

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;

/**
 * Plugin interface for reactive stream-in processing operations.
 * This interface is used for plugins that process a stream of inputs and perform a side-effect operation.
 *
 * @param <T> the type of input objects to process
 */
@FunctionalInterface
public interface PluginReactiveStreamIn<T> extends BasePlugin {
    /**
     * Process a stream of input objects asynchronously and return a Uni representing completion.
     *
     * @param items the stream of input objects to process
     * @return a Uni that completes when processing is done
     */
    Uni<Void> processStream(Multi<T> items);
}