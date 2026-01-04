package org.example;

import io.micrometer.core.instrument.*;
import java.util.Random;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.tron.trident.core.ApiWrapper;
import org.tron.trident.core.exceptions.IllegalException;

@Slf4j(topic = "api")
public class LoadGenerator implements AutoCloseable {

  private final ScheduledExecutorService scheduler;
  private final ExecutorService workers;

  private final AtomicBoolean running = new AtomicBoolean(true);

  // ===== inflight control =====
  private final Semaphore inflightLimiter;
  private final AtomicInteger inflight;

  // ===== metrics =====
  private final Counter success;
  private final Counter failure;
  private final Timer latency;

  // ===== trident client =====
  private final ApiWrapper client;
  private final Random random = new Random();

  public LoadGenerator(
      MeterRegistry registry,
      ApiWrapper client,
      int workerThreads,
      int maxInflight
  ) {
    this.client = client;

    this.scheduler = Executors.newSingleThreadScheduledExecutor(
        r -> new Thread(r, "qps-scheduler")
    );

    this.workers = Executors.newFixedThreadPool(
        workerThreads,
        r -> new Thread(r, "worker")
    );

    this.inflightLimiter = new Semaphore(maxInflight);
    this.inflight = new AtomicInteger(0);

    this.success = registry.counter("trident.request.success");
    this.failure = registry.counter("trident.request.failure");

    this.latency = Timer.builder("trident.request.latency")
        .publishPercentiles(0.5, 0.9, 0.99)
        .register(registry);

    Gauge.builder("trident.request.inflight", inflight, AtomicInteger::get)
        .register(registry);
  }

  public void start(int qps) {
    long intervalNs = 1_000_000_000L / qps;

    scheduler.scheduleAtFixedRate(
        this::submitOnce,
        0,
        intervalNs,
        TimeUnit.NANOSECONDS
    );
  }

  private void submitOnce() {
    if (!running.get()) {
      return;
    }

    // ===== hard inflight limit =====
    if (!inflightLimiter.tryAcquire()) {
      return; // inflight full, drop
    }

    inflight.incrementAndGet();

    workers.submit(() -> {
      long start = System.nanoTime();
      try {
        callOnce();
        success.increment();
      } catch (Exception e) {
        failure.increment();
        log.warn("request failed", e);
      } finally {
        latency.record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        inflight.decrementAndGet();
        inflightLimiter.release();
      }
    });
  }

  private void callOnce() throws IllegalException {
    int r = random.nextInt(5);
    switch (r) {
      case 0 -> client.getNowBlock();
      case 1 -> client.getBlock(false);
      case 2 -> client.getChainParameters();
      case 3 -> client.getBurnTRX();
      case 4 -> client.getContract("TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t");
    }
  }

  @Override
  public void close() {
    stop();
  }

  public void stop() {
    if (!running.compareAndSet(true, false)) {
      return;
    }

    log.info("Stopping load generator...");

    // 1 stop scheduler
    scheduler.shutdown();

    // 2 wait inflight drain
    long start = System.currentTimeMillis();
    while (inflight.get() > 0 && System.currentTimeMillis() - start < 30_000) {
      try {
        Thread.sleep(100);
      } catch (InterruptedException ignored) {
      }
    }

    // 3 stop workers
    workers.shutdown();

    try {
      scheduler.awaitTermination(5, TimeUnit.SECONDS);
      workers.awaitTermination(10, TimeUnit.SECONDS);
    } catch (InterruptedException ignored) {
    }

    scheduler.shutdownNow();
    workers.shutdownNow();

    // 4 close client
    try {
      client.close();
    } catch (Exception e) {
      log.warn("failed to close client", e);
    }

    log.info("Load generator stopped. inflight={}, success={}, failure={}",
        inflight.get(), success.count(), failure.count());
  }
}
