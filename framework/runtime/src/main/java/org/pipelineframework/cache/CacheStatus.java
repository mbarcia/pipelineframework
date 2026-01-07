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

package org.pipelineframework.cache;

/**
 * Cache status values reported by cache plugin steps.
 */
public enum CacheStatus {
    HIT,
    MISS,
    BYPASS,
    WRITE;

    public static CacheStatus fromHeader(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase();
        for (CacheStatus status : values()) {
            if (status.name().equals(normalized)) {
                return status;
            }
        }
        return null;
    }
}
