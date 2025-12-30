package org.example;


import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;


import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.tron.trident.core.ApiWrapper;
import org.tron.trident.core.key.KeyPair;

@Slf4j(topic = "press")
public class Main {

  public static void main(String[] args) throws InterruptedException {

    // 1. metrics registry
    MeterRegistry registry = MetricsBootstrap.init();

    // 2. trident client
    KeyPair keyPair = KeyPair.generate();
    // ApiWrapper client = new ApiWrapper("127.0.0.1:50051", "127.0.0.1:50061", keyPair.toPrivateKey());
    ApiWrapper client = new ApiWrapper("127.0.0.1:16669", "127.0.0.1:16670", keyPair.toPrivateKey());
    // 3. load generator
    LoadGenerator generator = new LoadGenerator(registry, client);
    generator.start(1000); // 1000 QPS

    // 4. metrics reporter
//    MetricsReporter reporter = new MetricsReporter(registry);
//    reporter.start();

    AtomicInteger inflight = new AtomicInteger(0);
    MetricsReporterV2 reporter = new MetricsReporterV2(registry, inflight, 30, 30);
    reporter.start();

    log.info("Trident E2E press load test started.");

    // 5. keep main thread alive
    Thread.currentThread().join();

  }
}
