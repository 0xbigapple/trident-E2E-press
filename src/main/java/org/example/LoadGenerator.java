package org.example;

import io.micrometer.core.instrument.*;
import java.util.Random;
import java.util.concurrent.*;
import org.tron.trident.core.ApiWrapper;

public class LoadGenerator {

  private final ScheduledExecutorService scheduler =
      Executors.newScheduledThreadPool(8);

  private final ExecutorService workers =
      Executors.newFixedThreadPool(64);

  private final Counter success;
  private final Counter failure;
  private final Timer latency;

  // ===== trident client =====
  private final ApiWrapper client;

  public LoadGenerator(MeterRegistry registry, ApiWrapper client) {
    this.client = client;
    this.success = registry.counter("trident.request.success");
    this.failure = registry.counter("trident.request.failure");
    this.latency = Timer.builder("trident.request.latency")
        .publishPercentiles(0.5, 0.9, 0.99)
        .register(registry);
  }

  public void start(int qps) {
    long intervalNs = 1_000_000_000L / qps;
    Random random = new Random();

    scheduler.scheduleAtFixedRate(() -> {
      workers.submit(() -> {
        latency.record(() -> {
          try {
            client.getNowBlock();
            int r = random.nextInt(2);
            switch (r) {
              case 0 -> client.getNowBlock();
              case 1 -> client.getBlock(false);
//              case 2 -> client.getChainParameters();
//              case 3 -> client.getBurnTRX();

//              case 4 -> client.getContract("TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t");
            }
            success.increment();
          } catch (Exception e) {
            System.out.println(e.getMessage());
            failure.increment();
          }
        });
      });
    }, 0, intervalNs, TimeUnit.NANOSECONDS);
  }
}

