# Metrics

The framework exposes metrics through Quarkus and Micrometer, giving step-level visibility into throughput, latency, and failures.

## Built-in Metrics

Typical metrics you can expect to expose:

1. Execution duration per step
2. Success and failure counts
3. End-to-end pipeline latency
4. Throughput and backpressure signals
5. Error rates by step and error type

## Micrometer Integration

Micrometer is the default metrics façade. You can export to Prometheus or other backends supported by Quarkus.

```properties
quarkus.micrometer.export.prometheus.enabled=true
quarkus.micrometer.export.prometheus.path=/q/metrics
```

## Dashboards

Pair metrics with Grafana dashboards that show:

1. Step latency percentiles (p95/p99)
2. Throughput per step
3. Error rate by step
4. Pipeline end-to-end latency

## Parallelism and Backpressure

TPF emits additional metrics and span attributes to showcase parallelism and queue pressure:

Metrics (OTel/Micrometer):
- `tpf.step.inflight` (gauge): in-flight items per step (`tpf.step.class` attribute)
- `tpf.step.pending` (gauge): queued + in-flight items per step (`tpf.step.class` attribute); useful as a backpressure proxy

Run-level span attributes (on `tpf.pipeline.run`):
- `tpf.parallel.max_in_flight`
- `tpf.parallel.avg_in_flight`
- `tpf.pending.max`
- `tpf.pending.avg`
- `tpf.item.count`
- `tpf.item.avg_ms`
- `tpf.items.per_min`

These are designed for batch-style pipelines where throughput should be measured while the pipeline is running.

## Custom Metrics

Use Micrometer to add counters and timers inside your services:

```java
@Inject
MeterRegistry registry;

Timer timer = registry.timer("payment.processing.duration");
Counter success = registry.counter("payment.processing.success");

return timer.recordCallable(() -> processPayment(record));
```

## Design Tips

1. Prefer low-cardinality labels
2. Track user-visible latency
3. Align metrics with SLIs/SLOs
4. Measure queue depth if you use streaming steps
