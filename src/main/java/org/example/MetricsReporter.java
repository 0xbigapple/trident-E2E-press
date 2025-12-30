package org.example;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j(topic = "press")
public class MetricsReporter {

  private final MeterRegistry registry;
  private final ScheduledExecutorService scheduler;
  private final int reportIntervalSeconds = 10;

  private final AtomicLong lastSuccess = new AtomicLong(0);

  public MetricsReporter(MeterRegistry registry) {
    this.registry = registry;
    this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "metrics-reporter");
      t.setDaemon(true);
      return t;
    });
  }

  public void start() {
    scheduler.scheduleAtFixedRate(this::print, reportIntervalSeconds, reportIntervalSeconds, TimeUnit.SECONDS);
  }

  private void print() {
    try {
      Timer latency = registry.find("trident.request.latency").timer();
      Counter success = registry.find("trident.request.success").counter();
      Gauge inflightGauge = registry.find("trident.request.inflight").gauge();

      long current = success == null ? 0 : (long) success.count();
      long delta = current - lastSuccess.getAndSet(current);
      double qps = (double) delta / reportIntervalSeconds;

      double avgMs = latency == null ? 0 : latency.mean(TimeUnit.MILLISECONDS);
      double p99Ms = latency == null ? 0 : latency.percentile(0.99, TimeUnit.MILLISECONDS);
      double p90Ms = latency == null ? 0 : latency.percentile(0.90, TimeUnit.MILLISECONDS);
      double p50Ms = latency == null ? 0 : latency.percentile(0.50, TimeUnit.MILLISECONDS);
      double inflight = inflightGauge == null ? 0 : inflightGauge.value();

      log.info(
          "[METRICS][API] qps={}, avgMs={}, p99Ms={}, p90Ms={}, p50Ms={}, inflight={}",
          format(qps),
          format(avgMs),
          format(p99Ms),
          format(p90Ms),
          format(p50Ms),
          (long) inflight
      );

    } catch (Throwable t) {
      log.warn("metrics reporter failed", t);
    }
    printJvmMetrics();
  }

  private void printJvmMetrics() {
    try {
      // ClassLoader Metrics
      double loadedClassCount = registry.find("jvm.classes.loaded").gauge() == null ? 0.0 :
          registry.find("jvm.classes.loaded").gauge().value();
      double unloadedClassCount = registry.find("jvm.classes.unloaded").gauge() == null ? 0.0 :
          registry.find("jvm.classes.unloaded").gauge().value();

      // JVM Memory Metrics
      double heapUsed = registry.find("jvm.memory.used").tag("area", "heap").gauge() == null ? 0.0 :
          registry.find("jvm.memory.used").tag("area", "heap").gauge().value();
      double heapMax = registry.find("jvm.memory.max").tag("area", "heap").gauge() == null ? 0.0 :
          registry.find("jvm.memory.max").tag("area", "heap").gauge().value();
      double heapMaxMB = heapMax <= 0 ? 0 : heapMax / 1024 / 1024;


      // JVM Threads
      double threadCount = registry.find("jvm.threads.live").gauge() == null ? 0.0 :
          registry.find("jvm.threads.live").gauge().value();
      double daemonCount = registry.find("jvm.threads.daemon").gauge() == null ? 0.0 :
          registry.find("jvm.threads.daemon").gauge().value();

      // Processor Metrics
      double cpuCount = registry.find("system.cpu.count").gauge() == null ? 0.0 :
          registry.find("system.cpu.count").gauge().value();
      Double processCpu = registry.find("process.cpu.usage").gauge() == null ? 0.0 :
          registry.find("process.cpu.usage").gauge().value();

      // JVM GC Metrics
      registry.find("jvm.gc.pause").timers().forEach(timer -> {
        log.info("[METRICS][GC] {} avg={}ms p99={}ms",
            timer.getId().getTag("action") + "/" + timer.getId().getTag("cause"),
            timer.mean(TimeUnit.MILLISECONDS),
            timer.percentile(0.99, TimeUnit.MILLISECONDS)
        );
      });

      Gauge fdGauge = registry.find("process.open.fds").gauge();
      double openFds = fdGauge == null ? 0 : fdGauge.value();
      // 打印 JVM / 系统信息
      log.info("[METRICS][JVM] loadedClass={}, unloadedClass={}, heapUsed={}MB, heapMax={}MB, threadsLive={}, threadsDaemon={}, cpuCount={}, processCpu={}, fd={}",
          (int) loadedClassCount,
          (int) unloadedClassCount,
          heapUsed / 1024 / 1024,
          heapMaxMB,
          (int) threadCount,
          (int) daemonCount,
          (int) cpuCount,
          processCpu,
          (long) openFds
      );

    } catch (Throwable t) {
      log.warn("jvm metrics reporter failed", t);
    }
  }


  private String format(double v) {
    return String.format("%.2f", v);
  }
}
