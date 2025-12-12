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

package org.pipelineframework.plugin.runtime;

import io.quarkus.arc.Unremovable;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.inject.Singleton;
import org.pipelineframework.plugin.api.PluginReactiveStreamIn;
import org.pipelineframework.plugin.api.PluginReactiveUnary;
import org.pipelineframework.plugin.api.PluginReactiveUnaryReply;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Plugin engine that handles runtime resolution of plugin implementations.
 * This class discovers available plugins at runtime rather than at build time,
 * preventing CDI validation failures when plugins are not present.
 */
@Singleton
@Unremovable  // Prevent Quarkus from removing this bean even if it seems unused
public class PluginEngine {

    private static final Logger LOGGER = LoggerFactory.getLogger(PluginEngine.class);

    private final Instance<PluginReactiveUnary> pluginReactiveUnaryInstance;
    private final Instance<PluginReactiveUnaryReply> pluginReactiveUnaryReplyInstance;
    private final Instance<PluginReactiveStreamIn> pluginReactiveStreamInInstance;

    public PluginEngine() {
        // Initialize Instance objects
        this.pluginReactiveUnaryInstance = CDI.current().select(PluginReactiveUnary.class);
        this.pluginReactiveUnaryReplyInstance = CDI.current().select(PluginReactiveUnaryReply.class);
        this.pluginReactiveStreamInInstance = CDI.current().select(PluginReactiveStreamIn.class);
    }

    /**
     * Resolves a PluginReactiveUnary implementation by payload type.
     *
     * @param payloadType The class of the payload type
     * @param <T> The payload type
     * @return The plugin implementation
     * @throws PluginResolutionException if no matching plugin is found
     */
    @SuppressWarnings("unchecked")
    public <T> PluginReactiveUnary<T> resolveReactiveUnary(Class<T> payloadType) {
        // Iterate through available plugins to find one matching the type
        for (PluginReactiveUnary plugin : pluginReactiveUnaryInstance) {
            if (isAssignableFrom(plugin, payloadType)) {
                LOGGER.debug("Resolved PluginReactiveUnary for payloadType={}", payloadType.getName());
                return (PluginReactiveUnary<T>) plugin;
            }
        }

        throw new PluginResolutionException(
            String.format("No PluginReactiveUnary<%s> found", payloadType.getName())
        );
    }

    /**
     * Resolves a PluginReactiveUnaryReply implementation by payload/response types.
     *
     * @param payloadType The class of the input payload type
     * @param responseType The class of the response type
     * @param <T> The payload type
     * @param <R> The response type
     * @return The plugin implementation
     * @throws PluginResolutionException if no matching plugin is found
     */
    @SuppressWarnings("unchecked")
    public <T, R> PluginReactiveUnaryReply<T, R> resolveReactiveUnaryReply(Class<T> payloadType, Class<R> responseType) {
        // Iterate through available plugins to find one matching the types
        for (PluginReactiveUnaryReply plugin : pluginReactiveUnaryReplyInstance) {
            // Check if the plugin accepts this payload type and returns the expected response type
            if (isAssignableFromForReply(plugin, payloadType, responseType)) {
                LOGGER.debug("Resolved PluginReactiveUnaryReply for payloadType={}, responseType={}",
                    payloadType.getName(), responseType.getName());
                return (PluginReactiveUnaryReply<T, R>) plugin;
            }
        }

        throw new PluginResolutionException(
            String.format("No PluginReactiveUnaryReply<%s, %s> found",
                payloadType.getName(), responseType.getName())
        );
    }

    /**
     * Resolves a PluginReactiveStreamIn implementation by payload type.
     *
     * @param payloadType The class of the payload type
     * @param <T> The payload type
     * @return The plugin implementation
     * @throws PluginResolutionException if no matching plugin is found
     */
    @SuppressWarnings("unchecked")
    public <T> PluginReactiveStreamIn<T> resolveReactiveStreamIn(Class<T> payloadType) {
        // Iterate through available plugins to find one matching the type
        for (PluginReactiveStreamIn plugin : pluginReactiveStreamInInstance) {
            if (isAssignableFrom(plugin, payloadType)) {
                LOGGER.debug("Resolved PluginReactiveStreamIn for payloadType={}", payloadType.getName());
                return (PluginReactiveStreamIn<T>) plugin;
            }
        }

        throw new PluginResolutionException(
            String.format("No PluginReactiveStreamIn<%s> found", payloadType.getName())
        );
    }

    /**
     * Checks if a plugin's generic type matches the given payload type.
     * Uses reflection to check the generic type parameters at runtime.
     */
    private boolean isAssignableFrom(Object plugin, Class<?> payloadType) {
        Class<?> pluginType = getGenericParameterType(plugin, PluginReactiveUnary.class);
        return pluginType != null && payloadType.isAssignableFrom(pluginType);
    }

    /**
     * Checks if a plugin's generic types match the given payload and response types.
     * Uses reflection to check the generic type parameters at runtime.
     */
    private boolean isAssignableFromForReply(Object plugin, Class<?> payloadType, Class<?> responseType) {
        Class<?>[] types = getGenericParameterTypes(plugin, PluginReactiveUnaryReply.class);
        return types != null && types.length >= 2 && 
               payloadType.isAssignableFrom(types[0]) && 
               responseType.isAssignableFrom(types[1]);
    }

    /**
     * Gets the generic type parameter for a plugin implementation.
     */
    private Class<?> getGenericParameterType(Object plugin, Class<?> interfaceType) {
        try {
            Class<?> clazz = plugin.getClass();
            for (java.lang.reflect.Type type : clazz.getGenericInterfaces()) {
                if (type instanceof java.lang.reflect.ParameterizedType) {
                    java.lang.reflect.ParameterizedType pType = (java.lang.reflect.ParameterizedType) type;
                    if (pType.getRawType().equals(interfaceType)) {
                        java.lang.reflect.Type[] actualTypeArguments = pType.getActualTypeArguments();
                        if (actualTypeArguments.length > 0) {
                            java.lang.reflect.Type actualType = actualTypeArguments[0];
                            if (actualType instanceof Class<?>) {
                                return (Class<?>) actualType;
                            }
                        }
                    }
                }
            }
            
            // Check if it's a superclass instead of interface
            java.lang.reflect.Type genericSuperclass = clazz.getGenericSuperclass();
            if (genericSuperclass instanceof java.lang.reflect.ParameterizedType) {
                java.lang.reflect.ParameterizedType pType = (java.lang.reflect.ParameterizedType) genericSuperclass;
                if (pType.getRawType().equals(interfaceType)) {
                    java.lang.reflect.Type[] actualTypeArguments = pType.getActualTypeArguments();
                    if (actualTypeArguments.length > 0) {
                        java.lang.reflect.Type actualType = actualTypeArguments[0];
                        if (actualType instanceof Class<?>) {
                            return (Class<?>) actualType;
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Could not determine generic parameter type for plugin: {}", plugin.getClass().getName(), e);
        }
        return null;
    }

    /**
     * Gets the generic type parameters for a plugin reply implementation.
     */
    private Class<?>[] getGenericParameterTypes(Object plugin, Class<?> interfaceType) {
        try {
            Class<?> clazz = plugin.getClass();
            for (java.lang.reflect.Type type : clazz.getGenericInterfaces()) {
                if (type instanceof java.lang.reflect.ParameterizedType) {
                    java.lang.reflect.ParameterizedType pType = (java.lang.reflect.ParameterizedType) type;
                    if (pType.getRawType().equals(interfaceType)) {
                        java.lang.reflect.Type[] actualTypeArguments = pType.getActualTypeArguments();
                        Class<?>[] result = new Class<?>[actualTypeArguments.length];
                        for (int i = 0; i < actualTypeArguments.length; i++) {
                            if (actualTypeArguments[i] instanceof Class<?>) {
                                result[i] = (Class<?>) actualTypeArguments[i];
                            } else {
                                return null; // Can't handle non-class types
                            }
                        }
                        return result;
                    }
                }
            }
            
            // Check if it's a superclass instead of interface
            java.lang.reflect.Type genericSuperclass = clazz.getGenericSuperclass();
            if (genericSuperclass instanceof java.lang.reflect.ParameterizedType) {
                java.lang.reflect.ParameterizedType pType = (java.lang.reflect.ParameterizedType) genericSuperclass;
                if (pType.getRawType().equals(interfaceType)) {
                    java.lang.reflect.Type[] actualTypeArguments = pType.getActualTypeArguments();
                    Class<?>[] result = new Class<?>[actualTypeArguments.length];
                    for (int i = 0; i < actualTypeArguments.length; i++) {
                        if (actualTypeArguments[i] instanceof Class<?>) {
                            result[i] = (Class<?>) actualTypeArguments[i];
                        } else {
                            return null; // Can't handle non-class types
                        }
                    }
                    return result;
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Could not determine generic parameter types for plugin: {}", plugin.getClass().getName(), e);
        }
        return null;
    }
}