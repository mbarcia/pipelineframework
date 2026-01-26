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

package org.pipelineframework.telemetry;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.metrics.ObservableLongMeasurement;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.quarkus.arc.Unremovable;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import org.pipelineframework.config.ParallelismPolicy;
import org.pipelineframework.config.PipelineStepConfig;

/**
 * Records pipeline-level spans and metrics for step execution.
 */
@ApplicationScoped
@Unremovable
public class PipelineTelemetry {

    private static final AttributeKey<String> INPUT_KIND = AttributeKey.stringKey("tpf.input");
    private static final AttributeKey<String> STEP_CLASS = AttributeKey.stringKey("tpf.step.class");
    private static final AttributeKey<Long> ITEM_COUNT = AttributeKey.longKey("tpf.item.count");
    private static final AttributeKey<Double> ITEM_AVG_MS = AttributeKey.doubleKey("tpf.item.avg_ms");
    private static final AttributeKey<Double> ITEMS_PER_MIN = AttributeKey.doubleKey("tpf.items.per_min");
    private static final AttributeKey<Long> PARALLEL_MAX_IN_FLIGHT =
        AttributeKey.longKey("tpf.parallel.max_in_flight");
    private static final AttributeKey<Double> PARALLEL_AVG_IN_FLIGHT =
        AttributeKey.doubleKey("tpf.parallel.avg_in_flight");
    private static final AttributeKey<Boolean> KILL_SWITCH_TRIGGERED =
        AttributeKey.booleanKey("tpf.kill_switch.triggered");
    private static final AttributeKey<String> KILL_SWITCH_REASON =
        AttributeKey.stringKey("tpf.kill_switch.reason");
    private static final AttributeKey<String> KILL_SWITCH_STEP =
        AttributeKey.stringKey("tpf.kill_switch.step");
    private static final AttributeKey<Double> KILL_SWITCH_INFLIGHT_SLOPE =
        AttributeKey.doubleKey("tpf.kill_switch.inflight_slope");
    private static final AttributeKey<Double> KILL_SWITCH_RETRY_RATE =
        AttributeKey.doubleKey("tpf.kill_switch.retry_rate");
    private static final AttributeKey<Double> KILL_SWITCH_INFLIGHT_SLOPE_THRESHOLD =
        AttributeKey.doubleKey("tpf.kill_switch.inflight_slope_threshold");
    private static final AttributeKey<Double> KILL_SWITCH_RETRY_RATE_THRESHOLD =
        AttributeKey.doubleKey("tpf.kill_switch.retry_rate_threshold");
    private static final String RETRY_AMPLIFICATION_REASON = "retry_amplification";

    private final boolean enabled;
    private final boolean metricsEnabled;
    private final boolean tracingEnabled;
    private final boolean perItemSpans;
    private final boolean retryAmplificationEnabled;
    private final Duration retryAmplificationWindow;
    private final double inflightSlopeThreshold;
    private final double retryRateThreshold;
    private final RetryAmplificationGuardMode retryAmplificationMode;
    private final Duration retryAmplificationSampleInterval;
    private final ScheduledExecutorService retryAmplificationScheduler;
    private final Tracer tracer;
    private final Meter meter;
    private final LongCounter pipelineRunCounter;
    private final LongCounter pipelineRunErrorCounter;
    private final LongCounter itemCounter;
    private final LongCounter stepErrorCounter;
    private final LongCounter stepRetryCounter;
    private final LongCounter killSwitchCounter;
    private final DoubleHistogram pipelineRunDuration;
    private final DoubleHistogram stepDuration;
    private final ConcurrentMap<String, AtomicLong> inflightByStep;
    private final ConcurrentMap<String, LongAdder> retryByStep;
    private final AtomicLong maxConcurrency;
    private final RetryAmplificationGuard retryAmplificationGuard;
    private final AtomicReference<RetryAmplificationMonitor> activeRetryAmplificationMonitor;
    private static final AtomicReference<PipelineTelemetry> ACTIVE = new AtomicReference<>();

    /**
     * Create a telemetry helper from the configured pipeline settings.
     *
     * @param stepConfig pipeline configuration mapping
     */
    @Inject
    public PipelineTelemetry(PipelineStepConfig stepConfig) {
        PipelineStepConfig.TelemetryConfig telemetry = stepConfig.telemetry();
        PipelineStepConfig.RetryAmplificationGuardConfig guardConfig = null;
        PipelineStepConfig.KillSwitchConfig killSwitchConfig = stepConfig.killSwitch();
        if (killSwitchConfig != null) {
            guardConfig = killSwitchConfig.retryAmplification();
        }
        this.enabled = telemetry != null && Boolean.TRUE.equals(telemetry.enabled());
        this.tracingEnabled = enabled && Boolean.TRUE.equals(telemetry.tracing().enabled());
        this.perItemSpans = tracingEnabled && Boolean.TRUE.equals(telemetry.tracing().perItem());
        this.metricsEnabled = enabled && Boolean.TRUE.equals(telemetry.metrics().enabled());
        this.retryAmplificationEnabled = guardConfig != null && Boolean.TRUE.equals(guardConfig.enabled());
        this.retryAmplificationWindow = guardConfig != null && guardConfig.window() != null
            ? guardConfig.window()
            : Duration.ofSeconds(30);
        this.inflightSlopeThreshold = guardConfig != null && guardConfig.inflightSlopeThreshold() != null
            ? guardConfig.inflightSlopeThreshold()
            : 10d;
        this.retryRateThreshold = guardConfig != null && guardConfig.retryRateThreshold() != null
            ? guardConfig.retryRateThreshold()
            : 5d;
        this.retryAmplificationMode = RetryAmplificationGuardMode.fromConfig(
            guardConfig != null ? guardConfig.mode() : null);
        this.retryAmplificationSampleInterval = resolveSampleInterval(this.retryAmplificationWindow);
        this.retryAmplificationScheduler = retryAmplificationEnabled
            ? Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "tpf-retry-amplification-guard");
                thread.setDaemon(true);
                return thread;
            })
            : null;
        this.tracer = GlobalOpenTelemetry.getTracer("org.pipelineframework");
        this.meter = GlobalOpenTelemetry.getMeter("org.pipelineframework");
        this.inflightByStep = new ConcurrentHashMap<>();
        this.retryByStep = new ConcurrentHashMap<>();
        this.maxConcurrency = new AtomicLong();
        this.retryAmplificationGuard = new RetryAmplificationGuard();
        this.activeRetryAmplificationMonitor = new AtomicReference<>();
        ACTIVE.set(this);
        if (metricsEnabled) {
            this.pipelineRunCounter = meter.counterBuilder("tpf.pipeline.run.count")
                .setDescription("Pipeline runs")
                .setUnit("1")
                .build();
            this.pipelineRunErrorCounter = meter.counterBuilder("tpf.pipeline.run.errors")
                .setDescription("Pipeline run errors")
                .setUnit("1")
                .build();
            this.itemCounter = meter.counterBuilder("tpf.pipeline.item.count")
                .setDescription("Pipeline input items")
                .setUnit("1")
                .build();
            this.stepErrorCounter = meter.counterBuilder("tpf.step.errors")
                .setDescription("Pipeline step errors")
                .setUnit("1")
                .build();
            this.stepRetryCounter = meter.counterBuilder("tpf.step.retry.count")
                .setDescription("Pipeline step retries")
                .setUnit("1")
                .build();
            this.killSwitchCounter = meter.counterBuilder("tpf.pipeline.kill_switch.triggered")
                .setDescription("Pipeline kill switch triggers")
                .setUnit("1")
                .build();
            this.pipelineRunDuration = meter.histogramBuilder("tpf.pipeline.run.duration")
                .setDescription("Pipeline run duration")
                .setUnit("ms")
                .build();
            this.stepDuration = meter.histogramBuilder("tpf.step.duration")
                .setDescription("Pipeline step duration")
                .setUnit("ms")
                .build();
            meter.gaugeBuilder("tpf.step.inflight")
                .setDescription("In-flight items per step")
                .setUnit("items")
                .ofLongs()
                .buildWithCallback(this::recordInflightGauge);
            meter.gaugeBuilder("tpf.pipeline.max_concurrency")
                .setDescription("Configured max concurrency for the pipeline run")
                .setUnit("items")
                .ofLongs()
                .buildWithCallback(this::recordMaxConcurrencyGauge);
        } else {
            this.pipelineRunCounter = null;
            this.pipelineRunErrorCounter = null;
            this.itemCounter = null;
            this.stepErrorCounter = null;
            this.stepRetryCounter = null;
            this.killSwitchCounter = null;
            this.pipelineRunDuration = null;
            this.stepDuration = null;
        }
    }

    /**
     * Start a pipeline run and return the telemetry context.
     *
     * @param input pipeline input
     * @param stepCount number of steps
     * @param policy parallelism policy
     * @param maxConcurrency max concurrency
     * @return telemetry run context
     */
    public RunContext startRun(Object input, int stepCount, ParallelismPolicy policy, int maxConcurrency) {
        if (!enabled && !retryAmplificationEnabled) {
            return RunContext.disabled();
        }
        boolean multiInput = input instanceof Multi<?>;
        Attributes attributes = Attributes.of(INPUT_KIND, multiInput ? "multi" : "uni");
        if (metricsEnabled) {
            pipelineRunCounter.add(1, attributes);
            this.maxConcurrency.set(Math.max(1, maxConcurrency));
        }
        Span span = null;
        Context context = Context.current();
        if (tracingEnabled) {
            span = tracer.spanBuilder("tpf.pipeline.run")
                .setSpanKind(SpanKind.INTERNAL)
                .setAttribute("tpf.steps.count", stepCount)
                .setAttribute("tpf.parallelism", policy == null ? "AUTO" : policy.name())
                .setAttribute("tpf.max_concurrency", maxConcurrency)
                .setAttribute("tpf.input", multiInput ? "multi" : "uni")
                .startSpan();
            context = context.with(span);
        }
        RunContext runContext = new RunContext(
            context,
            span,
            System.nanoTime(),
            attributes,
            enabled || retryAmplificationEnabled,
            new AtomicLong(),
            new AtomicLong(),
            new AtomicLong(),
            new LongAdder(),
            new LongAdder());
        startRetryAmplificationMonitor(runContext);
        return runContext;
    }

    /**
     * Instrument pipeline input to count items.
     *
     * @param input input Uni or Multi
     * @param runContext telemetry context
     * @return instrumented input
     */
    public Object instrumentInput(Object input, RunContext runContext) {
        if (!metricsEnabled || runContext == null || !runContext.enabled()) {
            return input;
        }
        if (input instanceof Uni<?> uni) {
            return uni.onItem().invoke(item -> {
                itemCounter.add(1, runContext.attributes());
                runContext.itemCount().incrementAndGet();
            });
        }
        if (input instanceof Multi<?> multi) {
            return multi.onItem().invoke(item -> {
                itemCounter.add(1, runContext.attributes());
                runContext.itemCount().incrementAndGet();
            });
        }
        return input;
    }

    /**
     * Attach completion hooks to a Uni or Multi to close the run.
     *
     * @param current Uni or Multi
     * @param runContext telemetry context
     * @return instrumented publisher
     */
    public Object instrumentRunCompletion(Object current, RunContext runContext) {
        if (runContext == null || !runContext.enabled()) {
            return current;
        }
        if (current instanceof Uni<?> uni) {
            return uni.onItemOrFailure().invoke((item, failure) -> endRun(runContext, failure));
        }
        if (current instanceof Multi<?> multi) {
            AtomicReference<Throwable> failureRef = new AtomicReference<>();
            return multi.onFailure().invoke(failureRef::set)
                .onTermination().invoke(() -> endRun(runContext, failureRef.get()));
        }
        return current;
    }

    /**
     * Instrument a step execution that returns a Uni.
     *
     * @param stepClass step class
     * @param uni step result
     * @param runContext telemetry context
     * @param perItemOperation true when called per item
     * @param <T> output type
     * @return instrumented Uni
     */
    public <T> Uni<T> instrumentStepUni(
        Class<?> stepClass,
        Uni<T> uni,
        RunContext runContext,
        boolean perItemOperation) {
        if (runContext == null || !runContext.enabled()) {
            return uni;
        }
        Span span = startStepSpan(stepClass, runContext, perItemOperation);
        long startNanos = System.nanoTime();
            onItemStart(stepClass, runContext);
            return uni.onItemOrFailure().invoke((item, failure) -> {
                recordStepOutcome(stepClass, startNanos, failure);
                onItemEnd(stepClass, runContext);
                endSpan(span, failure);
            });
    }

    /**
     * Instrument a step execution that returns a Multi.
     *
     * @param stepClass step class
     * @param multi step result
     * @param runContext telemetry context
     * @param perItemOperation true when called per item
     * @param <T> output type
     * @return instrumented Multi
     */
    public <T> Multi<T> instrumentStepMulti(
        Class<?> stepClass,
        Multi<T> multi,
        RunContext runContext,
        boolean perItemOperation) {
        if (runContext == null || !runContext.enabled()) {
            return multi;
        }
        Span span = startStepSpan(stepClass, runContext, perItemOperation);
        long startNanos = System.nanoTime();
        AtomicReference<Throwable> failureRef = new AtomicReference<>();
        onItemStart(stepClass, runContext);
        return multi.onFailure().invoke(failureRef::set)
            .onTermination().invoke(() -> {
                recordStepOutcome(stepClass, startNanos, failureRef.get());
                onItemEnd(stepClass, runContext);
                endSpan(span, failureRef.get());
            });
    }

    private Span startStepSpan(Class<?> stepClass, RunContext runContext, boolean perItemOperation) {
        if (!tracingEnabled || runContext == null || !runContext.enabled()) {
            return null;
        }
        if (perItemOperation && !perItemSpans) {
            return null;
        }
        return tracer.spanBuilder("tpf.step")
            .setParent(runContext.context())
            .setSpanKind(SpanKind.INTERNAL)
            .setAttribute("tpf.step.class", stepClass.getName())
            .startSpan();
    }

    private void recordStepOutcome(Class<?> stepClass, long startNanos, Throwable failure) {
        if (!metricsEnabled) {
            return;
        }
        double durationMs = nanosToMillis(startNanos);
        Attributes attributes = Attributes.of(STEP_CLASS, stepClass.getName());
        stepDuration.record(durationMs, attributes);
        if (failure != null) {
            stepErrorCounter.add(1, attributes);
        }
    }

    private void endRun(RunContext runContext, Throwable failure) {
        if (!runContext.enabled()) {
            return;
        }
        stopRetryAmplificationMonitor();
        if (metricsEnabled) {
            double durationMs = nanosToMillis(runContext.startNanos());
            pipelineRunDuration.record(durationMs, runContext.attributes());
            if (failure != null) {
                pipelineRunErrorCounter.add(1, runContext.attributes());
            }
        }
        if (tracingEnabled && runContext.span() != null) {
            long items = runContext.itemCount().get();
            double durationMs = nanosToMillis(runContext.startNanos());
            double avgMs = items > 0 ? durationMs / items : 0.0;
            double perMin = durationMs > 0 ? (items * 60_000d) / durationMs : 0.0;
            runContext.span().setAttribute(ITEM_COUNT, items);
            runContext.span().setAttribute(ITEM_AVG_MS, avgMs);
            runContext.span().setAttribute(ITEMS_PER_MIN, perMin);
            long samples = runContext.inflightSamples().sum();
            double inflightAvg = samples > 0 ? runContext.inflightSum().sum() / (double) samples : 0.0;
            runContext.span().setAttribute(PARALLEL_MAX_IN_FLIGHT, runContext.inflightMax().get());
            runContext.span().setAttribute(PARALLEL_AVG_IN_FLIGHT, inflightAvg);
        }
        endSpan(runContext.span(), failure);
    }

    private void onItemStart(Class<?> stepClass, RunContext runContext) {
        if (runContext == null || !runContext.enabled()) {
            return;
        }
        long current = runContext.inflightCurrent().incrementAndGet();
        runContext.inflightSamples().increment();
        runContext.inflightSum().add(current);
        runContext.inflightMax().accumulateAndGet(current, Math::max);
        if (metricsEnabled || retryAmplificationEnabled) {
            inflightByStep
                .computeIfAbsent(stepClass.getName(), key -> new AtomicLong())
                .incrementAndGet();
        }
    }

    private void onItemEnd(Class<?> stepClass, RunContext runContext) {
        if (runContext == null || !runContext.enabled()) {
            return;
        }
        long current = runContext.inflightCurrent().decrementAndGet();
        runContext.inflightSamples().increment();
        runContext.inflightSum().add(Math.max(current, 0));
        if (metricsEnabled || retryAmplificationEnabled) {
            AtomicLong currentStep = inflightByStep.get(stepClass.getName());
            if (currentStep != null) {
                currentStep.updateAndGet(value -> Math.max(0, value - 1));
            }
        }
    }

    private void recordInflightGauge(ObservableLongMeasurement measurement) {
        inflightByStep.forEach((step, count) -> {
            measurement.record(count.get(), Attributes.of(STEP_CLASS, step));
        });
    }

    private void recordMaxConcurrencyGauge(ObservableLongMeasurement measurement) {
        measurement.record(maxConcurrency.get());
    }

    /**
     * Record a retry for a step.
     *
     * @param stepClass step class
     */
    public static void recordRetry(Class<?> stepClass) {
        PipelineTelemetry telemetry = ACTIVE.get();
        if (telemetry != null) {
            telemetry.recordRetryInternal(stepClass);
        }
    }

    public boolean retryAmplificationGuardEnabled() {
        return retryAmplificationEnabled;
    }

    public RetryAmplificationGuardMode retryAmplificationMode() {
        return retryAmplificationMode;
    }

    public Duration retryAmplificationCheckInterval() {
        return retryAmplificationSampleInterval;
    }

    public java.util.Optional<RetryAmplificationGuard.Trigger> retryAmplificationTrigger() {
        RetryAmplificationMonitor monitor = activeRetryAmplificationMonitor.get();
        if (monitor == null) {
            return java.util.Optional.empty();
        }
        return monitor.triggered();
    }

    private void recordRetryInternal(Class<?> stepClass) {
        if (stepClass == null || !(metricsEnabled || retryAmplificationEnabled)) {
            return;
        }
        String step = stepClass.getName();
        retryByStep.computeIfAbsent(step, key -> new LongAdder()).increment();
        if (metricsEnabled) {
            stepRetryCounter.add(1, Attributes.of(STEP_CLASS, step));
        }
    }

    private void startRetryAmplificationMonitor(RunContext runContext) {
        if (!retryAmplificationEnabled || retryAmplificationScheduler == null || runContext == null) {
            return;
        }
        RetryAmplificationMonitor monitor = new RetryAmplificationMonitor(runContext);
        RetryAmplificationMonitor previous = activeRetryAmplificationMonitor.getAndSet(monitor);
        if (previous != null) {
            previous.stop();
        }
        monitor.start();
    }

    private void stopRetryAmplificationMonitor() {
        RetryAmplificationMonitor monitor = activeRetryAmplificationMonitor.getAndSet(null);
        if (monitor != null) {
            monitor.stop();
        }
    }

    private void endSpan(Span span, Throwable failure) {
        if (span == null) {
            return;
        }
        if (failure != null) {
            span.recordException(failure);
            span.setStatus(StatusCode.ERROR, failure.getMessage());
        }
        span.end();
    }

    private double nanosToMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000d;
    }

    @PreDestroy
    void shutdownRetryAmplificationScheduler() {
        if (retryAmplificationScheduler != null) {
            retryAmplificationScheduler.shutdownNow();
        }
    }

    private Duration resolveSampleInterval(Duration window) {
        long windowMillis = window == null ? 30_000L : Math.max(1_000L, window.toMillis());
        long intervalMillis = Math.max(1_000L, Math.min(windowMillis / 6, 5_000L));
        return Duration.ofMillis(intervalMillis);
    }

    private void recordKillSwitchTriggered(RunContext runContext, RetryAmplificationGuard.Trigger trigger) {
        if (metricsEnabled && killSwitchCounter != null) {
            killSwitchCounter.add(1, Attributes.of(
                KILL_SWITCH_REASON, RETRY_AMPLIFICATION_REASON,
                KILL_SWITCH_STEP, trigger.step()));
        }
        if (tracingEnabled && runContext != null && runContext.span() != null) {
            runContext.span().addEvent(
                "tpf.kill_switch.triggered",
                Attributes.of(
                    KILL_SWITCH_TRIGGERED, true,
                    KILL_SWITCH_REASON, RETRY_AMPLIFICATION_REASON,
                    KILL_SWITCH_STEP, trigger.step(),
                    KILL_SWITCH_INFLIGHT_SLOPE, trigger.inflightSlope(),
                    KILL_SWITCH_RETRY_RATE, trigger.retryRate(),
                    KILL_SWITCH_INFLIGHT_SLOPE_THRESHOLD, trigger.inflightSlopeThreshold(),
                    KILL_SWITCH_RETRY_RATE_THRESHOLD, trigger.retryRateThreshold()));
        }
    }

    private final class RetryAmplificationMonitor {
        private final RunContext runContext;
        private final ConcurrentMap<String, Deque<RetryAmplificationGuard.Sample>> samplesByStep;
        private final ConcurrentMap<String, Long> retryBaselineByStep;
        private final AtomicReference<RetryAmplificationGuard.Trigger> trigger;
        private ScheduledFuture<?> future;

        private RetryAmplificationMonitor(RunContext runContext) {
            this.runContext = runContext;
            this.samplesByStep = new ConcurrentHashMap<>();
            this.retryBaselineByStep = new ConcurrentHashMap<>();
            this.trigger = new AtomicReference<>();
        }

        void start() {
            future = retryAmplificationScheduler.scheduleAtFixedRate(
                this::sample,
                0L,
                retryAmplificationSampleInterval.toMillis(),
                TimeUnit.MILLISECONDS);
        }

        void stop() {
            if (future != null) {
                future.cancel(false);
            }
        }

        java.util.Optional<RetryAmplificationGuard.Trigger> triggered() {
            return java.util.Optional.ofNullable(trigger.get());
        }

        private void sample() {
            if (trigger.get() != null) {
                return;
            }
            long now = System.nanoTime();
            Set<String> steps = new HashSet<>(inflightByStep.keySet());
            steps.addAll(retryByStep.keySet());
            for (String step : steps) {
                long inflight = 0L;
                AtomicLong inflightCount = inflightByStep.get(step);
                if (inflightCount != null) {
                    inflight = inflightCount.get();
                }
                LongAdder retryCount = retryByStep.get(step);
                long retries = retryCount != null ? retryCount.sum() : 0L;
                long baseline = retryBaselineByStep.computeIfAbsent(step, key -> retries);
                long relativeRetries = Math.max(0L, retries - baseline);

                Deque<RetryAmplificationGuard.Sample> deque =
                    samplesByStep.computeIfAbsent(step, key -> new ArrayDeque<>());
                Deque<RetryAmplificationGuard.Sample> snapshot;
                synchronized (deque) {
                    deque.addLast(new RetryAmplificationGuard.Sample(now, inflight, relativeRetries));
                    trimSamples(deque);
                    snapshot = new ArrayDeque<>(deque);
                }
                retryAmplificationGuard
                    .evaluate(step, snapshot, retryAmplificationWindow, inflightSlopeThreshold, retryRateThreshold)
                    .ifPresent(this::trigger);
            }
        }

        private void trigger(RetryAmplificationGuard.Trigger triggerInfo) {
            if (!trigger.compareAndSet(null, triggerInfo)) {
                return;
            }
            recordKillSwitchTriggered(runContext, triggerInfo);
            stop();
        }

        private void trimSamples(Deque<RetryAmplificationGuard.Sample> deque) {
            int maxSamples = Math.max(2, (int) (retryAmplificationWindow.toMillis()
                / Math.max(1L, retryAmplificationSampleInterval.toMillis())) + 2);
            while (deque.size() > maxSamples) {
                deque.removeFirst();
            }
        }
    }

    /**
     * Immutable run context used for pipeline telemetry.
     *
     * @param context parent context
     * @param span run span
     * @param startNanos start time
     * @param attributes run attributes
     * @param enabled whether telemetry is enabled
     * @param itemCount items observed during the run
     * @param inflightCurrent current in-flight item count
     * @param inflightMax max in-flight item count
     * @param inflightSamples number of in-flight samples taken
     * @param inflightSum sum of in-flight samples
     */
    public record RunContext(
        Context context,
        Span span,
        long startNanos,
        Attributes attributes,
        boolean enabled,
        AtomicLong itemCount,
        AtomicLong inflightCurrent,
        AtomicLong inflightMax,
        LongAdder inflightSamples,
        LongAdder inflightSum) {

        static RunContext disabled() {
            return new RunContext(
                Context.current(),
                null,
                0L,
                Attributes.empty(),
                false,
                new AtomicLong(),
                new AtomicLong(),
                new AtomicLong(),
                new LongAdder(),
                new LongAdder());
        }
    }
}
