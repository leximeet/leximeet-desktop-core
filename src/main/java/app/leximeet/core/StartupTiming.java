package app.leximeet.core;

import java.util.Map;
import org.springframework.boot.SpringBootVersion;

/**
 * 仅保存两个启动阶段的时间，不启动采样线程。 frameworkMs 记录 SpringApplication 创建至容器 ready 的耗时；readyMs 记录从 main 入口到 eager bean
 * 完成且本机服务监听可用的耗时，不包含 JVM 创建、外部 HTTP 往返或首次 snapshot。
 */
final class StartupTiming {
  private final long mainStartedNanos;
  private long frameworkStartedNanos;
  private volatile double frameworkMs;
  private volatile double readyMs;

  StartupTiming(long mainStartedNanos) {
    this.mainStartedNanos = mainStartedNanos;
  }

  void frameworkStarting() {
    frameworkStartedNanos = System.nanoTime();
  }

  void frameworkReady() {
    frameworkMs = milliseconds(System.nanoTime() - frameworkStartedNanos);
  }

  void ready() {
    readyMs = milliseconds(System.nanoTime() - mainStartedNanos);
  }

  Map<String, Object> snapshot() {
    return Map.of(
        "frameworkMs",
        frameworkMs,
        "readyMs",
        readyMs,
        "framework",
        "Spring Boot " + SpringBootVersion.getVersion(),
        "javaVersion",
        System.getProperty("java.version"));
  }

  static double milliseconds(long nanos) {
    return Math.round(nanos / 1_000.0) / 1_000.0;
  }
}
