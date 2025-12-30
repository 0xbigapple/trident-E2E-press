package org.example;

import com.sun.management.UnixOperatingSystemMXBean;
import java.lang.management.ManagementFactory;

public class FdMetrics {

  public static long openFdCount() {
    try {
      Object os = ManagementFactory.getOperatingSystemMXBean();
      if (os instanceof com.sun.management.UnixOperatingSystemMXBean) {
        return ((UnixOperatingSystemMXBean) os).getOpenFileDescriptorCount();
      }
    } catch (Throwable ignored) {}
    return -1;
  }
}

