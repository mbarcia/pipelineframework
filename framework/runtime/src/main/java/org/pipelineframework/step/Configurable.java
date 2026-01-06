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

package org.pipelineframework.step;

import java.time.Duration;

import org.pipelineframework.config.StepConfig;

/**
 * Interface for pipeline steps that can be configured at runtime.
 */
public interface Configurable {
    /**
     * Get the effective configuration for this step.
     *
     * @return the step configuration
     */
    StepConfig effectiveConfig();

    /**
 * Retrieve the configured maximum number of retry attempts for the step.
 *
 * @return the maximum number of retry attempts allowed for the step
 */
    default int retryLimit() { return effectiveConfig().retryLimit(); }
    /**
 * Gets the configured wait duration between retry attempts.
 *
 * @return the duration to wait between retry attempts
 */
default Duration retryWait() { return effectiveConfig().retryWait(); }
    /**
 * Indicates whether the step should attempt recovery when a failure occurs.
 *
 * @return {@code true} if the step should attempt recovery after a failure, {@code false} otherwise.
 */
default boolean recoverOnFailure() { return effectiveConfig().recoverOnFailure(); }
    /**
 * Maximum backoff duration applied between retry attempts.
 *
 * @return the maximum backoff {@link Duration} used when applying retry backoff
 */
default Duration maxBackoff() { return effectiveConfig().maxBackoff(); }
    /**
 * Indicates whether jitter is enabled for retry backoff.
 *
 * @return `true` if jitter is enabled, `false` otherwise.
 */
default boolean jitter() { return effectiveConfig().jitter(); }
    /**
 * Provides the backpressure buffer capacity configured for this step.
 *
 * @return the maximum number of items the backpressure buffer can hold
 */
default int backpressureBufferCapacity() { return effectiveConfig().backpressureBufferCapacity(); }
    /**
 * Get the configured backpressure strategy for the step.
 *
 * @return the backpressure strategy name from the effective configuration
 */
default String backpressureStrategy() { return effectiveConfig().backpressureStrategy(); }
    /**
 * Indicates whether the step should run in parallel.
 *
 * @return `true` if the step should run in parallel, `false` otherwise.
 */
default boolean parallel() { return effectiveConfig().parallel(); }

    /**
 * Determines whether a failure should be retried by this step.
 *
 * @param failure the failure to evaluate
 * @return {@code true} if the failure is retryable, {@code false} otherwise
 */
    default boolean shouldRetry(Throwable failure) {
        if (failure == null) {
            return false;
        }
        Throwable current = failure;
        while (current != null) {
            if (current instanceof NullPointerException) {
                return false;
            }
            if (current instanceof NonRetryableException) {
                return false;
            }
            Throwable next = current.getCause();
            if (next == current) {
                break;
            }
            current = next;
        }
        return true;
    }

    /**
 * Initialises the implementing object using the provided step configuration.
 *
 * Implementations must apply values from the given {@code StepConfig} to configure the step before use.
 *
 * @param config the configuration to apply; serves as the effective configuration for this step
 */
void initialiseWithConfig(StepConfig config);
}
