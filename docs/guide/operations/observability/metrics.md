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

## LGTM Metrics Pipeline

LGTM Dev Services ship an OTLP collector and Prometheus. Grafana's built-in dashboards read
from the Prometheus datasource, so Prometheus scraping must be enabled even if OTLP export
is configured. For OTLP-first dashboards, you need a Grafana datasource that reads OTLP
metrics storage (for example Mimir) instead of Prometheus.

## Parallelism and Backpressure

TPF emits additional metrics and span attributes to showcase parallelism and buffer pressure:

Metrics (OTel/Micrometer):
- `tpf.step.inflight` (gauge): in-flight items per step (`tpf.step.class` attribute)
- `tpf.step.buffer.queued` (gauge): queued items in the backpressure buffer (`tpf.step.class` attribute)
- `tpf.step.buffer.capacity` (gauge): configured backpressure buffer capacity per step (`tpf.step.class` attribute)
- `tpf.pipeline.max_concurrency` (gauge): configured max concurrency for the pipeline run

Prometheus exports these as `*_items` because the unit is set to `items`.

Run-level span attributes (on `tpf.pipeline.run`):
- `tpf.parallel.max_in_flight`
- `tpf.parallel.avg_in_flight`
- `tpf.item.count`
- `tpf.item.avg_ms`
- `tpf.items.per_min`

These are designed for batch-style pipelines where throughput should be measured while the pipeline is running.

Tip: gauges report the instantaneous value, so after a run finishes they will return to 0.
When querying, use a max over time window to surface the peak:

```text
max(tpf_step_inflight_items) by (tpf_step_class)
max(tpf_step_buffer_queued_items) by (tpf_step_class)
```

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
