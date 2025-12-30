# The Pipeline Framework Guide

Welcome to The Pipeline Framework guide! This guide will help you understand how to use the framework to build reactive pipeline processing systems that work for your specific use case.

<Callout type="tip" title="Immutable Architecture">
The Pipeline Framework follows an immutable architecture where no database updates occur during pipeline execution - only appends/preserves. This ensures complete data integrity and provides a complete audit trail of all transformations.
</Callout>

<Callout type="tip" title="Rich Processing Patterns">
The framework supports multiple processing patterns including OneToOne, OneToMany, ManyToOne, ManyToMany, SideEffect and blocking variants. Each step type is optimized for specific data transformation scenarios.
</Callout>

## How to Use This Guide by Role

### For Developers
Start here to create your first pipeline applications:

- [Quick Start](/guide/quick-start): Get up and running in minutes with the visual Canvas designer
- [Creating Pipeline Steps](/guide/creating-steps): Build your first pipeline step from scratch
- [Application Structure](/guide/application-structure): Understand how to organize your code
- [Backend Services](/guide/backend-services): Implement the business logic for your steps
- [Mappers and DTOs](/guide/mappers-and-dtos): Handle data transformation between object types
- [Configuration](/guide/configuration): Manage your pipeline configuration settings
- [Plugins and Aspects](/guide/plugins-authoring): How to write plugins for cross-cutting concerns
- [Using Plugins](/guide/using-plugins): How to apply plugins to your pipeline using aspects
- [Error Handling & DLQ](/guide/error-handling): Implement error handling and dead letter queue functionality
- [Observability](/guide/observability): Monitor and observe your pipeline applications
- [Best Practices](/guide/best-practices): Follow recommended approaches for pipeline applications
- [Orchestrator Services](/guide/orchestrator-services): Control the flow of your pipeline

### For QA Engineers
Learn how to test pipeline applications effectively:

- [Testing Strategies](/guide/best-practices#testing): Testing strategies for pipeline applications
- [Observability](/guide/observability): How to monitor and verify pipeline behavior
- [Error Handling & DLQ](/guide/error-handling): Testing error scenarios and recovery

### For Product Owners
Understand the business value and capabilities:

- [Architecture](/reference/architecture): High-level overview of the system design
- [Reference Implementation](/REFERENCE_IMPLEMENTATION): See a real-world example
- [Canvas Guide](/CANVAS_GUIDE): Learn about the visual design tool for non-technical stakeholders

### For Architects
Deep-dive into design patterns and enterprise considerations:

- [Plugin Architecture](/guide/plugins-architecture): Understanding the AOP-style plugin and aspect system

- [Architecture](/reference/architecture): System design patterns and principles
- [Observability](/guide/observability): Enterprise monitoring and tracing strategies
- [Error Handling & DLQ](/guide/error-handling): Resilience and fault-tolerance patterns
- [Configuration](/guide/configuration): Environment-specific settings and deployment strategies
- [Best Practices](/guide/best-practices): Design and implementation best practices

### For CTOs
Get a strategic overview for technical leadership:

- [Architecture](/reference/architecture): System capabilities and technology stack
- [YAML Schema](/YAML_SCHEMA): Infrastructure-as-code configuration options
- [Best Practices](/guide/best-practices): Organizational implementation guidelines
- [Observability](/guide/observability): Enterprise monitoring strategies
- [Configuration](/guide/configuration): Enterprise deployment strategies

## Prerequisites

Before diving into The Pipeline Framework, make sure you have:

- Java 21 or higher
- Maven 3.8+
- A Quarkus-based project (the framework is designed to work with Quarkus)
- Basic understanding of reactive programming concepts

Happy pipelining!