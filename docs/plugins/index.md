# The Pipeline Framework Plugin System

The Pipeline Framework provides a plugin system that allows external components to participate in pipelines as side-effect steps with typed gRPC endpoints. Plugins are implemented using simple interfaces without dependencies on DTOs, mappers, or protos.

## Overview

The plugin system enables:

- **Typed gRPC endpoints** for external plugins to participate in pipelines
- **Generic plugin code** that doesn't depend on DTOs/mappers/protos
- **CDI-friendly interfaces** that work with Quarkus
- **Mutiny-based reactive programming** with Uni/Multi
- **Automatic adapter generation** via the annotation processor

## Plugin Interfaces

### PluginReactiveUnary<T>

The primary interface for plugins that process a single input item and perform a side-effect:

```java
public interface PluginReactiveUnary<T> extends BasePlugin {
    Uni<Void> process(T item);
}
```

Example usage:

```java
@ApplicationScoped
public class LoggingPlugin implements PluginReactiveUnary<String> {
    private static final Logger LOG = Logger.getLogger(LoggingPlugin.class);

    @Override
    public Uni<Void> process(String message) {
        LOG.info("Processing message: " + message);
        return Uni.createFrom().voidItem();
    }
}
```

### PluginReactiveUnaryReply<T, R>

For plugins that process input and return a result:

```java
public interface PluginReactiveUnaryReply<T, R> extends BasePlugin {
    Uni<R> process(T item);
}
```

### PluginReactiveStreamIn<T>

For plugins that process a stream of inputs:

```java
public interface PluginReactiveStreamIn<T> extends BasePlugin {
    Uni<Void> processStream(Multi<T> items);
}
```

## Plugin Implementation

Plugin implementations are standard CDI beans that implement one of the plugin interfaces:

```java
@ApplicationScoped
public class PersistencePlugin<T> implements PluginReactiveUnary<T> {
    private final PersistenceManager persistenceManager;

    public PersistencePlugin(PersistenceManager persistenceManager) {
        this.persistenceManager = persistenceManager;
    }

    @Override
    public Uni<Void> process(T item) {
        return persistenceManager.persist(item)
            .replaceWithVoid();
    }
}
```

## Plugin Registration and Usage

Plugin implementations are discovered through CDI. The generated plugin adapters automatically inject the appropriate plugin implementation based on the DTO type.

When you annotate a service with `@PipelineStep`, if it's not marked as `local=true`, the annotation processor automatically generates:

1. A **plugin adapter** (`<ServiceName>PluginAdapter`) that:
   - Converts gRPC messages to DTOs using the configured mapper
   - Injects the appropriate plugin implementation
   - Registers itself with the `PluginEngine`

2. A **plugin reactive service** (`<ServiceName>PluginReactiveService`) that implements the standard service interface and delegates to the adapter

## Plugin Container Structure

A plugin deployable is a container composed of:

- Generated step-specific adapters (proto→DTO→plugin)
- Generated step-specific `PluginReactiveService`
- Plugin implementation JAR(s)
- `plugin-api` module
- `plugin-runtime` module
- Optional: persistence module (first built-in plugin)

Plugin authors depend only on `plugin-api` and optionally the persistence module, never on adapters.

## Build Process

The annotation processor generates plugin components at build time:

1. Discover all `@PipelineStep` annotations
2. Generate plugin adapters and services for non-local steps
3. Register adapters with the `PluginEngine` at startup

The generated adapters and plugin reactive services are packaged in a separate JAR artifact ("adapters JAR") published by CI after the pipeline build.

## Plugin Engine

The `PluginEngine` handles registration and invocation of plugin adapters:

```java
@ApplicationScoped
public class PluginEngine {
    public void registerAdapter(String stepName, AdapterInvoker adapterInvoker);
    public Uni<?> invoke(String stepName, Message protoMessage);
}
```

## Configuration

Plugins work automatically with existing `@PipelineStep` configurations. The plugin system integrates seamlessly with the existing step infrastructure.