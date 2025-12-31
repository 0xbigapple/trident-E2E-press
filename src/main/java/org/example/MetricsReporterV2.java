package org.example;

import io.micrometer.core.instrument.*;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.List;

@Slf4j(topic = "reporter")
public class MetricsReporterV2 implements AutoCloseable {

  private final MeterRegistry registry;
  private final ScheduledExecutorService scheduler;

  private final AtomicLong lastSuccess = new AtomicLong(0);
  private final AtomicInteger inflight;

  private final long initialDelaySec;
  private final long periodSec;

  public MetricsReporterV2(MeterRegistry registry, AtomicInteger inflight,
      long initialDelaySec, long periodSec) {
    this.registry = registry;
    this.inflight = inflight;
    this.initialDelaySec = initialDelaySec;
    this.periodSec = periodSec;

    this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "metrics-reporter");
      t.setDaemon(true);
      return t;
    });
  }

  public void start() {
    scheduler.scheduleAtFixedRate(this::print, initialDelaySec, periodSec, TimeUnit.SECONDS);
  }

  @Override
  public void close() {
    log.info("Stopping metrics reporter...");
    scheduler.shutdown();
    try {
      scheduler.awaitTermination(5, TimeUnit.SECONDS);
    } catch (InterruptedException ignored) {
    }
    scheduler.shutdownNow();
    log.info("Metrics reporter stopped.");
  }

  private void print() {
    try {
      printApiMetrics();
      printJvmMetrics();
      printGcMetrics();
      printCustomGauges();
    } catch (Throwable t) {
      log.warn("metrics reporter failed", t);
    }
  }

  private void printApiMetrics() {
    Timer latency = registry.find("trident.request.latency").timer();
    Counter successCounter = registry.find("trident.request.success").counter();
    Counter failureCounter = registry.find("trident.request.failure").counter();

    long current = successCounter == null ? 0 : (long) successCounter.count();
    long delta = current - lastSuccess.getAndSet(current);
    double qps = delta / (double) periodSec;

    double avgMs = latency == null ? 0 : latency.mean(TimeUnit.MILLISECONDS);
    double p99Ms = safePercentile(latency, 0.99);
    double p90Ms = safePercentile(latency, 0.90);
    double p50Ms = safePercentile(latency, 0.50);
    long inflightCount = inflight.get();

    log.info("[METRICS][API] qps={}, avgMs={}, p99Ms={}, p90Ms={}, p50Ms={}, inflight={}, failures={}",
        format(qps), format(avgMs), format(p99Ms), format(p90Ms), format(p50Ms),
        inflightCount,
        failureCounter == null ? 0 : (long) failureCounter.count());
  }

  private void printJvmMetrics() {
    Double loadedClass = registry.find("jvm.classes.loaded").gauge() != null ?
        registry.find("jvm.classes.loaded").gauge().value() : 0;
    Double unloadedClass = registry.find("jvm.classes.unloaded").gauge() != null ?
        registry.find("jvm.classes.unloaded").gauge().value() : 0;

    Double heapUsed = registry.find("jvm.memory.used").tag("area","heap").gauge() != null ?
        registry.find("jvm.memory.used").tag("area","heap").gauge().value() : 0;
    Double heapMax = registry.find("jvm.memory.max").tag("area","heap").gauge() != null ?
        registry.find("jvm.memory.max").tag("area","heap").gauge().value() : 0;
    heapMax = heapMax <= 0 ? 0 : heapMax;

    Double threadsLive = registry.find("jvm.threads.live").gauge() != null ?
        registry.find("jvm.threads.live").gauge().value() : 0;
    Double threadsDaemon = registry.find("jvm.threads.daemon").gauge() != null ?
        registry.find("jvm.threads.daemon").gauge().value() : 0;

    Double cpuCount = registry.find("system.cpu.count").gauge() != null ?
        registry.find("system.cpu.count").gauge().value() : 0;
    Double processCpu = registry.find("process.cpu.usage").gauge() != null ?
        registry.find("process.cpu.usage").gauge().value() : 0;

    Double fdCount = registry.find("process.open.fds").gauge() != null ?
        registry.find("process.open.fds").gauge().value() : 0;

    log.info("[METRICS][JVM] loadedClass={}, unloadedClass={}, heapUsed={}MB, heapMax={}MB, threadsLive={}, threadsDaemon={}, cpuCount={}, processCpu={}, fd={}",
        loadedClass.intValue(),
        unloadedClass.intValue(),
        heapUsed / 1024 / 1024,
        heapMax / 1024 / 1024,
        threadsLive.intValue(),
        threadsDaemon.intValue(),
        cpuCount.intValue(),
        processCpu,
        fdCount.intValue());
  }

  private void printGcMetrics() {
    List<Timer> timers = (List<Timer>) registry.find("jvm.gc.pause").timers();
    for (Timer timer : timers) {
      String action = timer.getId().getTag("action");
      String cause = timer.getId().getTag("cause");
      double avg = timer.mean(TimeUnit.MILLISECONDS);
      double p99 = safePercentile(timer, 0.99);
      log.info("[METRICS][GC] {} / {} avg={}ms p99={}ms",
          action, cause, format(avg), format(p99));
    }
  }

  private void printCustomGauges() {
    registry.getMeters().stream()
        .filter(m -> m instanceof Gauge)
        .forEach(m -> {
          Gauge g = (Gauge) m;
          log.info("[METRICS][CUSTOM] {}={} {}", g.getId().getName(), g.value(), g.getId().getTags());
        });
  }

  private double safePercentile(Timer timer, double p) {
    if (timer == null) return 0;
    double val = timer.percentile(p, TimeUnit.MILLISECONDS);
    if (Double.isNaN(val)) return 0;
    return val;
  }

  private String format(double v) {
    return String.format("%.2f", v);
  }
}

