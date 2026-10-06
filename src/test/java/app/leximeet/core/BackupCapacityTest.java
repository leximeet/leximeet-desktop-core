package app.leximeet.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// 使用实际 SQLite 的一万活动词验证当前备份能完整恢复，而不是只检查导出成功。
class BackupCapacityTest {
  @TempDir Path directory;
  private static final String TOKEN = "backup_capacity_test_token_0123456789";

  @Test
  void tenThousandPersonalWordsSurviveHttpBackupRoundTrip() throws Exception {
    try (var source = new LeximeetService(directory.resolve("source"), Clock.systemUTC());
        var restored = new LeximeetService(directory.resolve("restored"), Clock.systemUTC());
        var sourceServer = new CoreServer(source, TOKEN, false);
        var restoredServer = new CoreServer(restored, TOKEN, false);
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
      source.seedCapacity(10_000);
      sourceServer.start();
      restoredServer.start();
      var exported =
          client.send(
              request(sourceServer, "/api/export").GET().build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, exported.statusCode());
      ObjectNode backup = Json.object(JsonBackup.MAPPER.readTree(exported.body()));
      assertEquals(100, backup.path("version").asInt());
      int bytes = exported.body().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
      assertTrue(bytes > CoreServer.MAX_BODY_BYTES, "一万词备份应穿过独立的备份容量边界，实际字节数=" + bytes);
      var imported =
          client.send(
              request(restoredServer, "/api/import")
                  .header("Content-Type", "application/json")
                  .POST(HttpRequest.BodyPublishers.ofString(exported.body()))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, imported.statusCode(), imported.body());
      assertEquals(10_000, Json.MAPPER.readTree(imported.body()).path("words").size());
      assertEquals(10_000, restored.snapshot().path("words").size());
    }
  }

  @Test
  void importCannotBypassTenThousandActiveWordLimit() throws Exception {
    try (var source = new LeximeetService(directory.resolve("source"), Clock.systemUTC());
        var restored = new LeximeetService(directory.resolve("restored"), Clock.systemUTC())) {
      source.seedCapacity(10_000);
      ObjectNode backup = source.exportBackup();
      try (PortableBackup.Archive archive = JsonBackup.decode(backup);
          var db = java.sql.DriverManager.getConnection("jdbc:sqlite:" + archive.file())) {
        Sql.execute(
            db,
            "INSERT INTO words(id,word,normalized,meaning,created_at,updated_at) VALUES(?,?,?,?,?,?)",
            UUID.randomUUID().toString(),
            "unique-extra-capacity-word",
            "unique-extra-capacity-word",
            "extra",
            "2026-10-04T00:00:00Z",
            "2026-10-04T00:00:00Z");
        backup = JsonBackup.encode(archive.file(), "2026-10-04T00:00:00Z");
      }
      final ObjectNode forged = backup;
      ApiException error = assertThrows(ApiException.class, () -> restored.importBackup(forged));
      assertEquals(400, error.status());
      assertTrue(error.getMessage().contains("活动词条"));
      assertEquals(0, restored.snapshot().path("words").size(), "超过上限时整笔导入必须回滚");
    }
  }

  private static HttpRequest.Builder request(CoreServer server, String path) {
    return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
        .timeout(Duration.ofSeconds(90))
        .header("Authorization", "Bearer " + TOKEN);
  }
}
