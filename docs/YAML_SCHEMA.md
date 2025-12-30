# Pipeline YAML Configuration Schema

## Overview

The Pipeline Framework uses YAML configuration files to define pipeline applications. This document details the complete schema and available options, including support for multiple processing patterns (OneToOne, OneToMany, ManyToOne, ManyToMany, SideEffect and blocking variants) and immutable architecture with auto-persistence capabilities.

## Root Level Properties

### `appName` (string, required)
The name of the application to generate.

Example:
```yaml
appName: "Payment Processing Pipeline"
```

### `basePackage` (string, required)
The base package name for the generated Java code.

Example:
```yaml
basePackage: "com.example.payments"
```

### `steps` (array of objects, required)
List of pipeline steps to generate.

## Step Properties

Each step in the `steps` array has the following properties:

### `name` (string, required)
The name of the step.

### `cardinality` (string, required)
The cardinality of the step. Available options:
- `ONE_TO_ONE`: Single input to single output
- `EXPANSION`: Single input to multiple outputs
- `REDUCTION`: Multiple inputs to single output
- `SIDE_EFFECT`: Side-effect processing (input=output)

### `inputTypeName` (string, required)
Name of the input type.

### `inputFields` (array of field objects, required)
List of fields in the input type.

### `outputTypeName` (string, required)
Name of the output type.

### `outputFields` (array of field objects, required)
List of fields in the output type.

### `parallel` (boolean, optional)
Enable concurrency for processing individual items within a single stream. When set to true, allows
processing multiple items from the same input stream concurrently. For StepOneToOne steps, enables concurrent
processing of multiple input items instead of sequential processing. For StepOneToMany steps, enables concurrent
processing of the output streams produced by each item. Default is false (no parallelism).

Example:
```yaml
parallel: true
```



### Additional Generated Properties
The following properties are automatically generated from the step name:
- `serviceName`: Lowercase, hyphen-separated service name (e.g., "process-payment-svc")
- `serviceNameCamel`: CamelCase service name (e.g., "processPayment")

## Field Properties

Each field in `inputFields` and `outputFields` arrays has the following properties:

### `name` (string, required)
The name of the field.

### `type` (string, required)
Java type of the field. Most conversions are handled automatically by MapStruct, with the following types requiring custom converters:

**Automatically handled by MapStruct:**
- `String`, `Integer`, `Long`, `Double`, `Boolean`
- `UUID`, `BigDecimal`, `BigInteger`
- Java 8 Time API: `LocalDate`, `LocalDateTime`, `LocalTime`, `LocalTime`, `OffsetDateTime`, `ZonedDateTime`, `Instant`, `Duration`, `Period`
- `URI`, `URL`, `File`, `Path`

**Requiring custom converters in CommonConverters:**
- `Currency`
- `AtomicInteger`, `AtomicLong`
- `List<String>` (custom list serialization)

### `protoType` (string, required)
Protobuf type of the field. Available options:
- `string`
- `int32`
- `int64`
- `double`
- `bool`

## Complete Example

```yaml
---
appName: "Order Processing Pipeline"
basePackage: "com.example.orders"
steps:
  # Step 1: Process Order
- name: "Process Order"
  cardinality: "ONE_TO_ONE"
  inputTypeName: "OrderInput"
  inputFields:
  - name: "orderId"
    type: "String"
    protoType: "string"
  - name: "customerId"
    type: "UUID"
    protoType: "string"
  - name: "amount"
    type: "BigDecimal"
    protoType: "string"
  - name: "currency"
    type: "String"
    protoType: "string"
  outputTypeName: "OrderProcessed"
  outputFields:
  - name: "orderId"
    type: "String"
    protoType: "string"
  - name: "customerId"
    type: "UUID"
    protoType: "string"
  - name: "status"
    type: "String"
    protoType: "string"
  - name: "processedAt"
    type: "String"
    protoType: "string"
  - name: "totalAmount"
    type: "BigDecimal"
    protoType: "string"

  # Step 2: Validate Payment (expansion step)
- name: "Validate Payment"
  cardinality: "EXPANSION"
  inputTypeName: "OrderProcessed"  # Automatically uses previous step's output
  inputFields:  # Same as OrderProcessed output fields
  - name: "orderId"
    type: "String"
    protoType: "string"
  - name: "customerId"
    type: "UUID"
    protoType: "string"
  - name: "status"
    type: "String"
    protoType: "string"
  - name: "processedAt"
    type: "String"
    protoType: "string"
  - name: "totalAmount"
    type: "BigDecimal"
    protoType: "string"
  outputTypeName: "PaymentValidationRequest"
  outputFields:
  - name: "paymentId"
    type: "UUID"
    protoType: "string"
  - name: "amount"
    type: "BigDecimal"
    protoType: "string"
  - name: "validationStatus"
    type: "String"
    protoType: "string"

  # Step 3: Aggregate Results (reduction step)
- name: "Aggregate Results"
  cardinality: "REDUCTION"
  inputTypeName: "PaymentValidationRequest"
  inputFields:  # Same as PaymentValidationRequest output fields
  - name: "paymentId"
    type: "UUID"
    protoType: "string"
  - name: "amount"
    type: "BigDecimal"
    protoType: "string"
  - name: "validationStatus"
    type: "String"
    protoType: "string"
  outputTypeName: "AggregatedResult"
  outputFields:
  - name: "summaryId"
    type: "UUID"
    protoType: "string"
  - name: "totalValidated"
    type: "Long"
    protoType: "int64"
  - name: "successCount"
    type: "Long"
    protoType: "int64"
  - name: "failureCount"
    type: "Long"
    protoType: "int64"
  - name: "timestamp"
    type: "String"
    protoType: "string"

  # Step 4: Log Results (side-effect step)
- name: "Log Results"
  cardinality: "SIDE_EFFECT"
  inputTypeName: "AggregatedResult"
  inputFields:  # Same as AggregatedResult output fields
  - name: "summaryId"
    type: "UUID"
    protoType: "string"
  - name: "totalValidated"
    type: "Long"
    protoType: "int64"
  - name: "successCount"
    type: "Long"
    protoType: "int64"
  - name: "failureCount"
    type: "Long"
    protoType: "int64"
  - name: "timestamp"
    type: "String"
    protoType: "string"
  outputTypeName: "AggregatedResult"  # Side-effect step returns same type as input
  outputFields:
  - name: "summaryId"
    type: "UUID"
    protoType: "string"
  - name: "totalValidated"
    type: "Long"
    protoType: "int64"
  - name: "successCount"
    type: "Long"
    protoType: "int64"
  - name: "failureCount"
    type: "Long"
    protoType: "int64"
  - name: "timestamp"
    type: "String"
    protoType: "string"
```

## Type Dependencies

When steps are connected sequentially:
1. The output type of one step automatically becomes the input type of the next step
2. Field types and structures are inherited automatically
3. The framework handles inter-step type dependencies

## Field Numbering

In the generated proto files:
- Input fields are numbered from 1 to n
- Output fields continue numbering from n+1 to m
- Field numbers are automatically assigned based on the order in the configuration

## Generation Process

When you use the template generator with a YAML configuration:

1. **Validate**: The configuration is validated against the JSON schema
2. **Process**: Additional properties are generated from step names
3. **Generate**: Complete Maven multi-module project with all components
4. **Output**: Ready-to-build application in the specified output directory
