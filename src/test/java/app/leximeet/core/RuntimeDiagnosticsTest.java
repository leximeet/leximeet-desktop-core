package app.leximeet.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class RuntimeDiagnosticsTest {
  private RuntimeDiagnostics diagnostics() {
    return new RuntimeDiagnostics(new StartupTiming(System.nanoTime()));
  }

  @Test
  void disabledMonitoringCreatesNoSampleAndReturnsOnlyEnabledFlag() {
    var diagnostics = diagnostics();
    assertNull(diagnostics.begin("/api/words/private-id"));
    assertEquals("{\"enabled\":false}", diagnostics.snapshot().toString());
  }

  @Test
  void arbitraryIdsAndPathsAreNormalizedIntoBoundedCounters() {
    var diagnostics = diagnostics();
    diagnostics.setEnabled(true);
    for (int index = 0; index < 300; index++) {
      String path = "/api/words/private-id-" + index;
      diagnostics.finish(diagnostics.begin(path), "PATCH", path, index < 100 ? 400 : 200);
      String unknown = "/private-path-" + index;
      diagnostics.finish(diagnostics.begin(unknown), "GET", unknown, 404);
    }
    var result = diagnostics.snapshot();
    assertEquals(2, result.path("requests").size());
    assertEquals("PATCH /unmatched", result.path("requests").get(0).path("route").asText());
    assertEquals(300, result.path("requests").get(0).path("count").asInt());
    assertEquals(100, result.path("requests").get(0).path("errorCount").asInt());
    assertFalse(result.toString().contains("private-id"));
    assertFalse(result.toString().contains("private-path"));
    assertNull(diagnostics.begin("/api/diagnostics"));
    assertEquals(2, diagnostics.snapshot().path("requests").size());
    for (String field :
        new String[] {
          "heapUsedBytes",
          "heapCommittedBytes",
          "threads",
          "uptimeMs",
          "processCpuTimeNanos",
          "gcCount",
          "gcTimeMs"
        }) assertTrue(result.path("jvm").path(field).isNumber(), field);
  }

  @Test
  void turningOffClearsCountersAndInvalidatesInflightSamples() {
    var diagnostics = diagnostics();
    diagnostics.setEnabled(true);
    var inFlight = diagnostics.begin("/api/snapshot");
    diagnostics.finish(diagnostics.begin("/health"), "GET", "/health", 200);
    diagnostics.setEnabled(false);
    assertEquals("{\"enabled\":false}", diagnostics.snapshot().toString());
    diagnostics.setEnabled(true);
    diagnostics.finish(inFlight, "GET", "/api/snapshot", 200);
    assertTrue(diagnostics.snapshot().path("requests").isEmpty());
  }
}
