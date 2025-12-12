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

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;
import org.pipelineframework.persistence.PersistenceManager;
import org.pipelineframework.plugin.api.PluginReactiveUnary;

/**
 * Plugin implementation that persists entities using the PersistenceManager.
 * This plugin receives domain objects and persists them using the configured persistence provider.
 *
 * @param <T> the type of entity to persist
 */
@ApplicationScoped
public class PersistencePlugin<T> implements PluginReactiveUnary<T> {
    private static final Logger LOG = Logger.getLogger(PersistencePlugin.class);

    private final PersistenceManager persistenceManager;

    public PersistencePlugin(PersistenceManager persistenceManager) {
        this.persistenceManager = persistenceManager;
    }

    @Override
    public Uni<Void> process(T item) {
        LOG.debugf("About to persist entity: %s", item != null ? item.getClass().getName() : "null");
        if (item == null) {
            LOG.warn("Received null item to persist, returning completed Uni");
            return Uni.createFrom().voidItem();
        }

        return persistenceManager.persist(item)
            .onItem().invoke(result -> LOG.debugf("Successfully persisted entity: %s", result != null ? result.getClass().getName() : "null"))
            .onFailure().invoke(failure -> LOG.error("Failed to persist entity", failure))
            .replaceWithVoid();
    }
}