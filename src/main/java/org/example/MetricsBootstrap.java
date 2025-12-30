package org.example;

import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.binder.jvm.*;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

public class MetricsBootstrap {

  public static MeterRegistry init() {
    MeterRegistry registry =  new SimpleMeterRegistry();
    Metrics.globalRegistry.add(registry);

    new ClassLoaderMetrics().bindTo(registry);
    new JvmMemoryMetrics().bindTo(registry);
    new JvmThreadMetrics().bindTo(registry);
    new JvmGcMetrics().bindTo(registry);
    new ProcessorMetrics().bindTo(registry);

    // FD
    Gauge.builder("process.open.fds", FdMetrics::openFdCount)
        .description("Open file descriptors")
        .register(registry);

    return registry;
  }
}

