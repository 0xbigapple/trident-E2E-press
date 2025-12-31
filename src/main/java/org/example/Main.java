package org.example;


import io.micrometer.core.instrument.MeterRegistry;
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
    ApiWrapper client =
        new ApiWrapper("127.0.0.1:50051", "127.0.0.1:50061", keyPair.toPrivateKey());

    // 3. load generator
    LoadGenerator generator = new LoadGenerator(registry, client, 16, 32);
    generator.start(1000); // 1000 QPS

    // 4. metrics reporter
//    MetricsReporter reporter = new MetricsReporter(registry);
//    reporter.start();

    AtomicInteger inflight = new AtomicInteger(0);
    MetricsReporterV2 reporter = new MetricsReporterV2(registry, inflight, 30, 30);
    reporter.start();

    log.info("Trident E2E press load test started.");

    // register shutdown hook
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      log.info("Shutdown hook triggered. Closing LoadGenerator...");
      generator.close();
      reporter.close();
      client.close();
      log.info("LoadGenerator closed.");
    }));

    // 5. keep main thread alive
    Thread.currentThread().join();

  }
}
