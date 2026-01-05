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

package org.pipelineframework.plugin.persistence;

import jakarta.inject.Inject;

import io.smallrye.mutiny.Uni;
import org.jboss.logging.Logger;
import org.pipelineframework.service.ReactiveSideEffectService;

/**
 * A general-purpose persistence plugin that can persist any entity that has a corresponding
 * PersistenceProvider configured in the system.
 */
public class PersistenceService<T> implements ReactiveSideEffectService<T> {
    private final Logger logger = Logger.getLogger(PersistenceService.class);

    @Inject
    PersistenceManager persistenceManager;

    @Override
    public Uni<T> process(T item) {
        logger.debugf("PersistenceService.process() called with item: %s (class: %s)",
            item != null ? item.toString() : "null",
            item != null ? item.getClass().getName() : "null");
        if (item == null) {
            logger.debug("Received null item to persist, returning null");
            return Uni.createFrom().nullItem();
        }

        logger.debugf("Using persistenceManager: %s to persist item of type: %s",
            persistenceManager != null ? persistenceManager.getClass().getName() : "null",
            item.getClass().getName());

        if (persistenceManager == null) {
            return Uni.createFrom().failure(new IllegalStateException("PersistenceManager is not available"));
        }
        return persistenceManager.persist(item)
            .onItem().invoke(result -> logger.debugf("Successfully persisted entity: %s", result != null ? result.getClass().getName() : "null"))
            .onFailure().invoke(failure -> logger.error("Failed to persist entity", failure))
            .replaceWith(item); // Return the original item as it was just persisted (side effect)
    }
}
