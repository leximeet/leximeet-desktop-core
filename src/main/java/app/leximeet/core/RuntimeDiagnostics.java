package app.leximeet.core;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

// 显式开启才采集的本机开发诊断。只保存固定路由的累计数字，不保存请求记录。 没有轮询线程、远端上报、JMX 监听端口或 Actuator；关闭后清空累计值。
final class RuntimeDiagnostics {
  private static final int MAX_ROUTE_BUCKETS = 64;
  private static final Set<String> FIXED_ROUTES =
      Set.of(
          "/health",
          "/api/snapshot",
          "/api/export",
          "/api/import",
          "/api/export-archive",
          "/api/restore-archive",
          "/api/settings",
          "/api/desktop/state",
          "/api/desktop/query",
          "/api/desktop/command",
          "/api/lmcp/rpc",
          "/api/lmcp/manage");
  private final StartupTiming startup;
  private final Map<String, RequestMetrics> requests = new LinkedHashMap<>();
  private boolean enabled;
  private long generation;

  RuntimeDiagnostics(StartupTiming startup) {
    this.startup = startup;
  }

  synchronized void setEnabled(boolean enabled) {
    if (this.enabled == enabled) return;
    this.enabled = enabled;
    generation++;
    requests.clear();
  }

  synchronized RequestSample begin(String path) {
    // 默认关闭时不采时间、不构造样本、不读取 JVM MXBean；诊断查询本身也不计入。
    return !enabled || "/api/diagnostics".equals(path)
        ? null
        : new RequestSample(generation, System.nanoTime());
  }

  synchronized void finish(RequestSample sample, String method, String path, int status) {
    if (sample == null || !enabled || sample.generation != generation) return;
    String route = normalize(method, path);
    if (!requests.containsKey(route) && requests.size() >= MAX_ROUTE_BUCKETS) return;
    requests
        .computeIfAbsent(route, ignored -> new RequestMetrics())
        .add(System.nanoTime() - sample.startedNanos, status >= 400);
  }

  synchronized ObjectNode snapshot() {
    ObjectNode output = Json.MAPPER.createObjectNode().put("enabled", enabled);
    if (!enabled) return output;
    var heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
    var os = ManagementFactory.getOperatingSystemMXBean();
    long cpu =
        os instanceof com.sun.management.OperatingSystemMXBean extended
            ? extended.getProcessCpuTime()
            : -1;
    long gcCount = 0, gcTime = 0;
    for (var collector : ManagementFactory.getGarbageCollectorMXBeans()) {
      gcCount += Math.max(0, collector.getCollectionCount());
      gcTime += Math.max(0, collector.getCollectionTime());
    }
    ObjectNode jvm = output.putObject("jvm");
    jvm.put("heapUsedBytes", heap.getUsed());
    jvm.put("heapCommittedBytes", heap.getCommitted());
    jvm.put("threads", ManagementFactory.getThreadMXBean().getThreadCount());
    jvm.put("uptimeMs", ManagementFactory.getRuntimeMXBean().getUptime());
    jvm.put("processCpuTimeNanos", cpu);
    jvm.put("gcCount", gcCount);
    jvm.put("gcTimeMs", gcTime);
    output.set("startup", Json.MAPPER.valueToTree(startup.snapshot()));
    var rows = output.putArray("requests");
    requests.forEach(
        (route, metrics) -> {
          ObjectNode row = rows.addObject().put("route", route);
          row.put("count", metrics.count);
          row.put("errorCount", metrics.errorCount);
          row.put("totalDurationMs", StartupTiming.milliseconds(metrics.totalNanos));
          row.put("maxDurationMs", StartupTiming.milliseconds(metrics.maxNanos));
        });
    return output;
  }

  private static String normalize(String method, String path) {
    String route = FIXED_ROUTES.contains(path) ? path : "/unmatched";
    // ID、查询参数及任意非法路径都不会成为 map key，避免隐私泄露与无界基数。
    return (Set.of("GET", "POST", "PATCH").contains(method) ? method : "OTHER") + " " + route;
  }

  record RequestSample(long generation, long startedNanos) {}

  private static final class RequestMetrics {
    long count, errorCount, totalNanos, maxNanos;

    void add(long nanos, boolean error) {
      count++;
      if (error) errorCount++;
      totalNanos += Math.max(0, nanos);
      maxNanos = Math.max(maxNanos, nanos);
    }
  }
}
