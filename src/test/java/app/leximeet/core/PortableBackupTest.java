package app.leximeet.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// 只使用临时 SQLite 与动态端口，证明大容量文件备份保留事实但不携带设备授权。
class PortableBackupTest {
  @TempDir Path directory;
  private static final String TOKEN = "portable_backup_test_token_0123456789";

  @Test
  void archiveExceedsJsonLimitAndRestoresLegalLongNotes() throws Exception {
    Path source = directory.resolve("source");
    Path archive = directory.resolve("backup.sqlite");
    try (var database = new Database(source);
        var service =
            new LeximeetService(
                database,
                Clock.systemUTC(),
                new RuntimeDiagnostics(new StartupTiming(System.nanoTime())));
        var server = new CoreServer(service, TOKEN, false)) {
      service.seedCapacity(10_000, 8_000);
      server.start();
      var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
      var response =
          client.send(
              HttpRequest.newBuilder(
                      URI.create("http://127.0.0.1:" + server.port() + "/api/export-archive"))
                  .timeout(Duration.ofSeconds(120))
                  .header("Authorization", "Bearer " + TOKEN)
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofFile(archive));
      assertEquals(200, response.statusCode());
      assertTrue(Files.size(archive) > CoreServer.MAX_BACKUP_BYTES);
    }
    Path restored = directory.resolve("restored");
    try (var db = new Database(restored);
        var service =
            new LeximeetService(
                db,
                Clock.systemUTC(),
                new RuntimeDiagnostics(new StartupTiming(System.nanoTime())));
        var server = new CoreServer(service, TOKEN, false)) {
      server.start();
      var response =
          HttpClient.newBuilder()
              .connectTimeout(Duration.ofSeconds(5))
              .build()
              .send(
                  HttpRequest.newBuilder(
                          URI.create("http://127.0.0.1:" + server.port() + "/api/restore-archive"))
                      .timeout(Duration.ofSeconds(180))
                      .header("Authorization", "Bearer " + TOKEN)
                      .header("Content-Type", "application/vnd.sqlite3")
                      .POST(HttpRequest.BodyPublishers.ofFile(archive))
                      .build(),
                  HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode(), response.body());
      assertEquals(10_000, Json.MAPPER.readTree(response.body()).path("words").asInt());
      db.read(
          connection -> {
            var facts =
                Sql.first(
                    connection, "SELECT count(*) AS words,min(length(note)) AS minNote FROM words");
            assertEquals(10_000, facts.path("words").asInt());
            assertEquals(8_000, facts.path("minNote").asInt());
            return null;
          });
    }
  }

  @Test
  void historicalEncountersPracticeFactsAndNotebookRelationsSurviveCapacityRestore()
      throws Exception {
    Path source = directory.resolve("source-history");
    Path archive = directory.resolve("history.sqlite");
    try (var db = new Database(source);
        var service =
            new LeximeetService(
                db,
                Clock.systemUTC(),
                new RuntimeDiagnostics(new StartupTiming(System.nanoTime())))) {
      service.seedCapacity(10_000);
      db.transaction(
          connection -> {
            String deviceId = UUID.randomUUID().toString();
            String book = PersonalLibrary.READING_ID;
            // 复用预编译语句并分批落库，验收目标是历史事实的恢复，而不是 SQL 解析开销。
            try (PreparedStatement encounter =
                    connection.prepareStatement(
                        """
                        INSERT INTO encounters(id,word_id,context,source_title,source_url,created_at,is_demo)
                        VALUES(?,?,?,?,?,?,0)
                        """);
                PreparedStatement review =
                    connection.prepareStatement(
                        """
                        INSERT INTO desktop_practice_facts(id,submission_id,word_id,mode,correct,created_at,signal,assisted,study_day,attempt_id,rule_version,device_id,device_seq,logical_clock,zone,evidence,undone_at)
                        VALUES(?,?,?,'copy',?,?,?,0,?,?,'leximeet.learning/2',?,?,?,'UTC','{}',?)
                        """);
                PreparedStatement wordBook =
                    connection.prepareStatement(
                        "INSERT OR IGNORE INTO word_books(word_id,book_id) VALUES(?,?)")) {
              int wordIndex = 0;
              for (var row : Sql.rows(connection, "SELECT id FROM words ORDER BY id")) {
                String wordId = row.path("id").asText();
                wordBook.setString(1, wordId);
                wordBook.setString(2, book);
                wordBook.addBatch();
                for (int event = 0; event < 10; event++) {
                  String instant = String.format("2026-09-%02dT12:00:00Z", 16 + event);
                  encounter.setString(1, UUID.randomUUID().toString());
                  encounter.setString(2, wordId);
                  encounter.setString(3, "第 " + event + " 次遇见词条 " + wordIndex);
                  encounter.setString(4, "容量阅读");
                  encounter.setString(5, "https://example.org/article/" + event);
                  encounter.setString(6, instant);
                  encounter.addBatch();
                  review.setString(1, UUID.randomUUID().toString());
                  review.setString(2, UUID.randomUUID().toString());
                  review.setString(3, wordId);
                  review.setInt(4, event % 2 == 0 ? 1 : 0);
                  review.setString(5, instant);
                  review.setString(6, "answer");
                  review.setString(7, instant.substring(0, 10));
                  review.setString(8, UUID.randomUUID().toString());
                  review.setString(9, deviceId);
                  review.setString(10, String.valueOf(wordIndex * 10 + event + 1));
                  review.setString(11, String.valueOf(wordIndex * 10 + event + 1));
                  review.setString(12, event == 9 ? instant : null);
                  review.addBatch();
                }
                if (++wordIndex % 500 == 0) {
                  wordBook.executeBatch();
                  encounter.executeBatch();
                  review.executeBatch();
                }
              }
              wordBook.executeBatch();
              encounter.executeBatch();
              review.executeBatch();
            }
            return null;
          });
      assertHistoryCounts(db);
      try (var backup = service.exportPortableBackup()) {
        Files.copy(backup.file(), archive);
      }
    }
    Path restored = directory.resolve("restored-history");
    try (var db = new Database(restored);
        var service =
            new LeximeetService(
                db,
                Clock.systemUTC(),
                new RuntimeDiagnostics(new StartupTiming(System.nanoTime())));
        var server = new CoreServer(service, TOKEN, false)) {
      server.start();
      var response = upload(server, archive);
      assertEquals(200, response.statusCode(), response.body());
      assertEquals(10_000, Json.MAPPER.readTree(response.body()).path("words").asInt());
      assertEquals(100_000, Json.MAPPER.readTree(response.body()).path("encounters").asInt());
      assertEquals(100_000, Json.MAPPER.readTree(response.body()).path("practiceFacts").asInt());
      assertHistoryCounts(db);
    }
    try (var restarted = new Database(restored)) {
      assertHistoryCounts(restarted);
    }
  }

  private static void assertHistoryCounts(Database database) throws Exception {
    database.read(
        db -> {
          assertEquals(10_000, Sql.first(db, "SELECT COUNT(*) AS n FROM words").path("n").asInt());
          assertEquals(
              100_000, Sql.first(db, "SELECT COUNT(*) AS n FROM encounters").path("n").asInt());
          assertEquals(
              100_000,
              Sql.first(db, "SELECT COUNT(*) AS n FROM desktop_practice_facts").path("n").asInt());
          assertEquals(
              10_000, Sql.first(db, "SELECT COUNT(*) AS n FROM word_books").path("n").asInt());
          assertEquals(
              10_000,
              Sql.first(
                      db,
                      "SELECT COUNT(*) AS n FROM desktop_practice_facts WHERE undone_at IS NOT NULL")
                  .path("n")
                  .asInt());
          assertEquals(
              10_000,
              Sql.first(db, "SELECT COUNT(DISTINCT word_id) AS n FROM encounters")
                  .path("n")
                  .asInt());
          assertEquals(
              10_000,
              Sql.first(db, "SELECT COUNT(DISTINCT word_id) AS n FROM desktop_practice_facts")
                  .path("n")
                  .asInt());
          assertEquals(
              10_000,
              Sql.first(db, "SELECT COUNT(DISTINCT word_id) AS n FROM word_books")
                  .path("n")
                  .asInt());
          return null;
        });
  }

  @Test
  void archiveOmitsCredentialsAndResetsDeviceConsentWithoutChangingSource() throws Exception {
    Path source = directory.resolve("source");
    String credential = "portable-secret-credential-hash-unique-0123456789";
    Path file;
    try (var database = new Database(source);
        var service =
            new LeximeetService(
                database,
                Clock.systemUTC(),
                new RuntimeDiagnostics(new StartupTiming(System.nanoTime())))) {
      database.transaction(
          db -> {
            Sql.execute(
                db,
                "INSERT INTO"
                    + " lmcp_pairings(pairing_id,client_id,display_name,origin,token_hash,epoch,revoked,created_at)"
                    + " VALUES(?,?,?,?,?,?,?,?)",
                "00000000-0000-4000-8000-000000000001",
                UUID.randomUUID().toString(),
                "测试插件",
                "chrome-extension://test",
                credential,
                1,
                0,
                "2026-09-24T00:00:00Z");
            Sql.execute(
                db,
                "INSERT INTO lmcp_meta VALUES(?,?)",
                "invitation:test-client",
                "{\"tokenHash\":\"portable-secret-invitation-hash\"}");
            var settings =
                Json.object(
                    Json.MAPPER.readTree(
                        Sql.first(db, "SELECT payload FROM settings WHERE id=1")
                            .path("payload")
                            .asText()));
            settings.put("translationEnabled", true);
            settings.put("globalShortcutEnabled", true);
            Sql.execute(db, "UPDATE settings SET payload=? WHERE id=1", settings.toString());
            return null;
          });
      try (var archive = service.exportPortableBackup()) {
        file = directory.resolve("exported.sqlite");
        Files.copy(archive.file(), file);
      }
      database.read(
          db -> {
            assertEquals(
                1, Sql.first(db, "SELECT count(*) AS n FROM lmcp_pairings").path("n").asInt());
            return null;
          });
    }
    try (var copy = DriverManager.getConnection("jdbc:sqlite:" + file)) {
      assertEquals(0, Sql.first(copy, "SELECT count(*) AS n FROM lmcp_pairings").path("n").asInt());
      assertEquals(
          0,
          Sql.first(
                  copy,
                  "SELECT count(*) AS n FROM lmcp_meta WHERE key='invitationSecret' OR key LIKE"
                      + " 'invitation:%' OR key LIKE 'connection:%'")
              .path("n")
              .asInt());
      assertEquals(0, Sql.first(copy, "SELECT count(*) AS n FROM lmcp_sessions").path("n").asInt());
      var settings =
          Json.MAPPER.readTree(
              Sql.first(copy, "SELECT payload FROM settings WHERE id=1").path("payload").asText());
      assertFalse(settings.path("translationEnabled").asBoolean());
      assertFalse(settings.path("globalShortcutEnabled").asBoolean());
    }
    assertFalse(
        new String(Files.readAllBytes(file), java.nio.charset.StandardCharsets.ISO_8859_1)
            .contains(credential));
    assertFalse(
        new String(Files.readAllBytes(file), java.nio.charset.StandardCharsets.ISO_8859_1)
            .contains("portable-secret-invitation-hash"));
  }

  @Test
  void invalidFileAndForeignSchemaLeaveExistingDataAndIdentityUntouched() throws Exception {
    Path source = directory.resolve("source-validate");
    Path archive = directory.resolve("valid.sqlite");
    try (var db = new Database(source);
        var service =
            new LeximeetService(
                db,
                Clock.systemUTC(),
                new RuntimeDiagnostics(new StartupTiming(System.nanoTime())));
        var backup = service.exportPortableBackup()) {
      Files.copy(backup.file(), archive);
    }
    Path target = directory.resolve("target-validate");
    try (var db = new Database(target);
        var service =
            new LeximeetService(
                db,
                Clock.systemUTC(),
                new RuntimeDiagnostics(new StartupTiming(System.nanoTime())));
        var server = new CoreServer(service, TOKEN, false)) {
      CoreTestData.collect(
          service, Json.MAPPER.createObjectNode().put("word", "keepme").put("note", "保留"));
      String generation = db.read(connection -> Sql.meta(connection, "databaseGeneration"));
      server.start();
      Path badHeader = directory.resolve("bad-header.sqlite");
      Files.writeString(badHeader, "not a sqlite backup");
      assertEquals(400, upload(server, badHeader).statusCode());
      Path altered = directory.resolve("altered.sqlite");
      Files.copy(archive, altered);
      try (var copy = DriverManager.getConnection("jdbc:sqlite:" + altered)) {
        Sql.execute(
            copy, "CREATE TRIGGER extra_after_word AFTER INSERT ON words BEGIN SELECT 1; END");
      }
      assertEquals(400, upload(server, altered).statusCode());
      db.read(
          connection -> {
            assertEquals(
                1,
                Sql.first(connection, "SELECT COUNT(*) AS count FROM words").path("count").asInt());
            assertEquals(generation, Sql.meta(connection, "databaseGeneration"));
            return null;
          });
    }
  }

  @Test
  void restoreReplacesFactsAndRevokesLocalAuthorizations() throws Exception {
    Path source = directory.resolve("source-replace");
    Path archive = directory.resolve("replace.sqlite");
    try (var db = new Database(source);
        var service =
            new LeximeetService(
                db,
                Clock.systemUTC(),
                new RuntimeDiagnostics(new StartupTiming(System.nanoTime())));
        var backup = service.exportPortableBackup()) {
      // 此备份保留默认词本和设置，另一次测试验证大量个人词条的完整往返。
      Files.copy(backup.file(), archive);
    }
    Path target = directory.resolve("target-replace");
    String before;
    try (var db = new Database(target);
        var service =
            new LeximeetService(
                db,
                Clock.systemUTC(),
                new RuntimeDiagnostics(new StartupTiming(System.nanoTime())));
        var server = new CoreServer(service, TOKEN, false)) {
      CoreTestData.collect(
          service, Json.MAPPER.createObjectNode().put("word", "remove-me").put("note", "原资料"));
      db.transaction(
          connection -> {
            Sql.execute(
                connection,
                "INSERT INTO"
                    + " lmcp_pairings(pairing_id,client_id,display_name,origin,token_hash,epoch,revoked,created_at)"
                    + " VALUES(?,?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                "原插件",
                "chrome-extension://old",
                "old-secret-hash",
                1,
                0,
                "2026-09-24T00:00:00Z");
            return null;
          });
      before = db.read(connection -> Sql.meta(connection, "databaseGeneration"));
      server.start();
      var response = upload(server, archive);
      assertEquals(200, response.statusCode(), response.body());
      assertTrue(Json.MAPPER.readTree(response.body()).path("restored").asBoolean());
      db.read(
          connection -> {
            assertEquals(
                0,
                Sql.first(connection, "SELECT COUNT(*) AS count FROM words").path("count").asInt());
            assertEquals(
                0,
                Sql.first(connection, "SELECT COUNT(*) AS count FROM lmcp_pairings")
                    .path("count")
                    .asInt());
            assertNotEquals(before, Sql.meta(connection, "databaseGeneration"));
            return null;
          });
    }
    try (var db = new Database(target)) {
      db.read(
          connection -> {
            assertEquals(
                0,
                Sql.first(connection, "SELECT COUNT(*) AS count FROM words").path("count").asInt());
            assertEquals(
                0,
                Sql.first(connection, "SELECT COUNT(*) AS count FROM lmcp_pairings")
                    .path("count")
                    .asInt());
            assertNotEquals(before, Sql.meta(connection, "databaseGeneration"));
            return null;
          });
    }
  }

  @Test
  void failedCopyRollsBackAllDeletedRows() throws Exception {
    Path source = directory.resolve("source-rollback");
    Path archive = directory.resolve("rollback.sqlite");
    try (var db = new Database(source);
        var service =
            new LeximeetService(
                db,
                Clock.systemUTC(),
                new RuntimeDiagnostics(new StartupTiming(System.nanoTime())));
        var backup = service.exportPortableBackup()) {
      Files.copy(backup.file(), archive);
    }
    try (var db = new Database(directory.resolve("target-rollback"));
        var service =
            new LeximeetService(
                db,
                Clock.systemUTC(),
                new RuntimeDiagnostics(new StartupTiming(System.nanoTime())))) {
      CoreTestData.collect(
          service, Json.MAPPER.createObjectNode().put("word", "survivor").put("note", "不可丢失"));
      String generation = db.read(connection -> Sql.meta(connection, "databaseGeneration"));
      // 临时触发器只在目标连接存在，逼出复制中途失败，验证 DELETE 也整体回滚。
      db.read(
          connection -> {
            Sql.execute(
                connection,
                "CREATE TEMP TRIGGER reject_restore BEFORE INSERT ON main.books "
                    + "BEGIN SELECT RAISE(ABORT,'test restore failure'); END");
            return null;
          });
      assertThrows(Exception.class, () -> db.restoreFrom(archive));
      db.read(
          connection -> {
            assertEquals(
                1,
                Sql.first(connection, "SELECT COUNT(*) AS count FROM words").path("count").asInt());
            assertEquals(
                "survivor", Sql.first(connection, "SELECT word FROM words").path("word").asText());
            assertEquals(generation, Sql.meta(connection, "databaseGeneration"));
            return null;
          });
    }
  }

  @Test
  void limitedSqlitePagesLeaveOriginalFactsAndIdentityAfterRestoreFailure() throws Exception {
    Path archive = directory.resolve("larger-backup.sqlite");
    try (var source = new Database(directory.resolve("source-limited-pages"));
        var service =
            new LeximeetService(
                source,
                Clock.systemUTC(),
                new RuntimeDiagnostics(new StartupTiming(System.nanoTime())))) {
      service.seedCapacity(1_200);
      // 原始备份比目标库大，恢复阶段需要分配新的 SQLite 页面。
      try (var backup = service.exportPortableBackup()) {
        Files.copy(backup.file(), archive);
      }
    }
    Path target = directory.resolve("target-limited-pages");
    try (var db = new Database(target);
        var service =
            new LeximeetService(
                db,
                Clock.systemUTC(),
                new RuntimeDiagnostics(new StartupTiming(System.nanoTime())));
        var server = new CoreServer(service, TOKEN, false)) {
      CoreTestData.collect(
          service, Json.MAPPER.createObjectNode().put("word", "survivor").put("note", "原资料"));
      String generation = db.read(connection -> Sql.meta(connection, "databaseGeneration"));
      // 每例只限制本例 SQLite 文件的最大页面数，不占满宿主磁盘或触碰正式资料。
      db.read(
          connection -> {
            try (var sql = connection.createStatement()) {
              int ceiling;
              try (var pages = sql.executeQuery("PRAGMA page_count")) {
                assertTrue(pages.next());
                ceiling = pages.getInt(1) + 2;
              }
              try (var accepted = sql.executeQuery("PRAGMA max_page_count=" + ceiling)) {
                assertTrue(accepted.next());
                assertEquals(ceiling, accepted.getInt(1));
              }
            }
            return null;
          });
      server.start();
      var response = upload(server, archive);
      assertEquals(507, response.statusCode(), response.body());
      assertEquals(
          "STORAGE_FULL",
          Json.MAPPER.readTree(response.body()).path("error").path("code").asText(),
          "必须确实触发 SQLite 容量耗尽，而不是其他恢复错误");
      assertFalse(response.body().contains("survivor"));
      assertFalse(response.body().contains(target.toString()));
      db.read(
          connection -> {
            assertEquals(
                "survivor", Sql.first(connection, "SELECT word FROM words").path("word").asText());
            assertEquals(generation, Sql.meta(connection, "databaseGeneration"));
            return null;
          });
    }
    try (var reopened = new Database(target)) {
      reopened.read(
          connection -> {
            assertEquals(
                "survivor", Sql.first(connection, "SELECT word FROM words").path("word").asText());
            return null;
          });
    }
  }

  private static HttpResponse<String> upload(CoreServer server, Path file) throws Exception {
    return HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()
        .send(
            HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + server.port() + "/api/restore-archive"))
                .timeout(Duration.ofSeconds(180))
                .header("Authorization", "Bearer " + TOKEN)
                .header("Content-Type", "application/vnd.sqlite3")
                .POST(HttpRequest.BodyPublishers.ofFile(file))
                .build(),
            HttpResponse.BodyHandlers.ofString());
  }
}
