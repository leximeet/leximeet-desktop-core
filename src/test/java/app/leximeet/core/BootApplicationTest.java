package app.leximeet.core;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringBootVersion;

// 真实容器生命周期验证：数据库与 HTTP 监听必须由 Boot 初始化、销毁和失败回滚。
class BootApplicationTest {
  @TempDir Path directory;
  private static final String TOKEN = "boot_test_0123456789abcdefghijklmnopqrstuvwxyz";

  @Test
  void springOwnsLiveServicesAndClosingContextReleasesDatabase() throws Exception {
    CoreOptions options = new CoreOptions(directory, TOKEN, "test", false);
    StartupTiming timing = new StartupTiming(System.nanoTime());
    int port;
    try (var context = CoreApplication.start(options, timing);
        var client = HttpClient.newHttpClient()) {
      assertTrue(context.isActive());
      assertNotNull(context.getBean(Database.class));
      assertNotNull(context.getBean(LeximeetService.class));
      port = context.getBean(CoreServer.class).port();
      var request =
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/snapshot"))
              .timeout(Duration.ofSeconds(3))
              .header("Authorization", "Bearer " + TOKEN)
              .build();
      var response = client.send(request, HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode());
      assertTrue(Json.MAPPER.readTree(response.body()).path("words").isEmpty());
      assertEquals("3.5.16", SpringBootVersion.getVersion());
      assertTrue((double) timing.snapshot().get("frameworkMs") > 0);
      assertTrue(
          (double) timing.snapshot().get("readyMs")
              >= (double) timing.snapshot().get("frameworkMs"));
    }
    // 锁和 SQLite 连接都必须在 context.close() 返回前释放。
    try (var reopened = new LeximeetService(directory, Clock.systemDefaultZone())) {
      assertTrue(reopened.snapshot().path("words").isEmpty());
    }
    try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build()) {
      var request =
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health"))
              .timeout(Duration.ofSeconds(1))
              .build();
      assertThrows(
          java.io.IOException.class,
          () -> client.send(request, HttpResponse.BodyHandlers.ofString()));
    }
  }

  @Test
  void failedGatewayBeanCreationClosesAlreadyCreatedDatabase() throws Exception {
    CoreOptions invalid = new CoreOptions(directory, "invalid", "test", false);
    assertThrows(
        RuntimeException.class,
        () -> CoreApplication.start(invalid, new StartupTiming(System.nanoTime())));
    try (var reopened = new LeximeetService(directory, Clock.systemDefaultZone())) {
      assertTrue(reopened.snapshot().path("words").isEmpty());
    }
  }

  @Test
  void releaseUnitAcceptsOnlyJava21AndRejectsDuplicateOptions() {
    assertDoesNotThrow(() -> CoreOptions.requireJava21(21));
    assertThrows(IllegalArgumentException.class, () -> CoreOptions.requireJava21(25));
    assertThrows(IllegalArgumentException.class, () -> CoreOptions.requireJava21(17));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CoreOptions.parse(
                new String[] {
                  "--data-dir",
                  directory.toString(),
                  "--token",
                  TOKEN,
                  "--profile",
                  "test",
                  "--profile",
                  "demo"
                }));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CoreOptions.parse(
                new String[] {
                  "--data-dir", directory.toString(), "--token", TOKEN, "--seed-capacity", "8"
                }));
    CoreOptions seeded =
        CoreOptions.parse(
            new String[] {
              "--data-dir",
              directory.toString(),
              "--token",
              TOKEN,
              "--profile",
              "test",
              "--seed-capacity",
              "8"
            });
    assertEquals(8, seeded.seedCapacity());
    CoreOptions longNotes =
        CoreOptions.parse(
            new String[] {
              "--data-dir",
              directory.toString(),
              "--token",
              TOKEN,
              "--profile",
              "test",
              "--seed-capacity",
              "10000",
              "--seed-note-length",
              "8000"
            });
    assertEquals(8000, longNotes.seedNoteLength());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CoreOptions.parse(
                new String[] {
                  "--data-dir",
                  directory.toString(),
                  "--token",
                  TOKEN,
                  "--profile",
                  "production",
                  "--seed-capacity",
                  "10000",
                  "--seed-note-length",
                  "8000"
                }));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CoreOptions.parse(
                new String[] {
                  "--data-dir",
                  directory.toString(),
                  "--token",
                  TOKEN,
                  "--profile",
                  "test",
                  "--seed-capacity",
                  "50000",
                  "--seed-note-length",
                  "8000"
                }));
  }
}
