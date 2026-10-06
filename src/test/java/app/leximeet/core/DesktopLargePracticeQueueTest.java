package app.leximeet.core;

import static app.leximeet.core.DesktopWorkspaceTest.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// 大型真实 SQLite 索引复现冷范围切换；计时只覆盖冻结事务，不包括生成测试索引。
class DesktopLargePracticeQueueTest {
  @TempDir Path directory;

  private Path index(int size) throws Exception {
    Path file = new DesktopWorkspaceTest().index(directory);
    try (var db = DriverManager.getConnection("jdbc:sqlite:" + file)) {
      Sql.execute(
          db,
          "WITH RECURSIVE n(i) AS (SELECT 2 UNION ALL SELECT i+1 FROM n WHERE i<?)"
              + " INSERT INTO entries SELECT printf('20000000-0000-4000-8000-%012d',i),"
              + "'word-'||i,'word-'||i,'测试释义',"
              + "json_object('entry_id',printf('20000000-0000-4000-8000-%012d',i),"
              + "'headword','word-'||i,'lookup_key','word-'||i,'schema_version','leximeet.entry.v2',"
              + "'senses',json_array(json_object('short_gloss','测试释义'))),i FROM n",
          size - 1);
      Sql.execute(db, "CREATE INDEX entries_normalized ON entries(normalized)");
      Sql.execute(
          db, "UPDATE metadata SET payload=json_set(payload,'$.entryCount',?) WHERE id=1", size);
    }
    return file;
  }

  private void mount(LeximeetService service, Path file, int size) throws Exception {
    service
        .desktop()
        .mount(
            Json.MAPPER
                .createObjectNode()
                .put("file", file.toString())
                .put("edition", "core-text")
                .put("version", "0.0.3")
                .put("manifestSha", SHA)
                .put("entryCount", size));
  }

  private ObjectNode checkpoint(LeximeetService service, String scope, String mode)
      throws Exception {
    return service
        .desktop()
        .query(
            Json.MAPPER
                .createObjectNode()
                .put("kind", "practice")
                .put("scope", scope)
                .put("mode", mode));
  }

  @Test
  void coreSizedColdScopesKeepPublicOrderPersonalIdentityAndTrashExclusion() throws Exception {
    int size = 117902;
    Path file = index(size);
    try (var database = new Database(directory);
        var service =
            new LeximeetService(
                database, CLOCK, new RuntimeDiagnostics(new StartupTiming(System.nanoTime())))) {
      // 构造已关联但个人主键不同的合法引用，批量冻结不能直接以公共 ID 替代它。
      service
          .desktop()
          .command(Json.MAPPER.createObjectNode().put("action", "collect").put("word", "alpha"));
      String personalId = service.snapshot().path("words").get(0).path("id").asText();
      assertNotEquals(ALPHA, personalId);
      mount(service, file, size);
      database.transaction(
          db -> {
            Sql.execute(
                db, "UPDATE desktop_word_links SET entry_id=? WHERE word_id=?", ALPHA, personalId);
            Sql.execute(
                db,
                "UPDATE desktop_word_identity SET payload=? WHERE word_id=?",
                DesktopDataModel.wordRef(ALPHA, "", "alpha", "0.0.3").toString(),
                personalId);
            return null;
          });
      new DesktopWorkspaceTest().goal(service, "dictionary");
      service
          .desktop()
          .command(Json.MAPPER.createObjectNode().put("action", "trash").put("wordId", BETA));
      for (String scope : new String[] {"library", "dictionary"}) {
        long started = System.nanoTime();
        assertTimeout(Duration.ofSeconds(15), () -> checkpoint(service, scope, "copy"));
        System.out.printf(
            "cold-practice %s %d entries: %.3fs%n",
            scope, size, (System.nanoTime() - started) / 1e9);
        database.read(
            db -> {
              String base =
                  Sql.first(
                          db, "SELECT session_id FROM desktop_practice_scopes WHERE scope=?", scope)
                      .path("session_id")
                      .asText();
              assertEquals(
                  size - 1,
                  Sql.first(
                          db,
                          "SELECT COUNT(*) AS n FROM desktop_practice_items WHERE session_id=?",
                          base)
                      .path("n")
                      .asInt());
              var first =
                  Sql.first(
                      db,
                      "SELECT * FROM desktop_practice_items WHERE session_id=? AND position=0",
                      base);
              assertEquals(personalId, first.path("word_id").asText());
              assertEquals(
                  DesktopDataModel.wordRef(ALPHA, "", "alpha", "0.0.3"),
                  Json.MAPPER.readTree(first.path("word_ref").asText()));
              assertEquals(
                  String.format("20000000-0000-4000-8000-%012d", size - 1),
                  Sql.first(
                          db,
                          "SELECT word_id FROM desktop_practice_items WHERE session_id=? AND position=?",
                          base,
                          size - 2)
                      .path("word_id")
                      .asText());
              return null;
            });
      }
      assertEquals(0, service.snapshot().path("encounters").size());
      assertEquals(0, service.desktop().state().path("insights").path("learned").asInt());
    }
  }

  @Test
  void catalogOrderAndSmallestLinkedPersonalIdRemainStable() throws Exception {
    Path file = new DesktopWorkspaceTest().index(directory);
    try (var database = new Database(directory);
        var service =
            new LeximeetService(
                database, CLOCK, new RuntimeDiagnostics(new StartupTiming(System.nanoTime())))) {
      for (String word : new String[] {"custom-one", "custom-two"})
        service
            .desktop()
            .command(Json.MAPPER.createObjectNode().put("action", "collect").put("word", word));
      mount(service, file, 2);
      database.transaction(
          db -> {
            Sql.execute(db, "UPDATE desktop_word_links SET entry_id=?", ALPHA);
            Sql.execute(
                db,
                "UPDATE desktop_word_identity SET payload=?",
                DesktopDataModel.wordRef(ALPHA, "", "alpha", "0.0.3").toString());
            return null;
          });
      new DesktopWorkspaceTest().goal(service, "exam:test");
      checkpoint(service, "goal:exam:test", "copy");
      database.read(
          db -> {
            String base =
                Sql.first(
                        db,
                        "SELECT session_id FROM desktop_practice_scopes WHERE scope='goal:exam:test'")
                    .path("session_id")
                    .asText();
            var items =
                Sql.rows(
                    db,
                    "SELECT * FROM desktop_practice_items WHERE session_id=? ORDER BY position",
                    base);
            assertEquals(2, items.size());
            // 测试词书的成员顺序为 beta → alpha，与公共全局顺序相反。
            assertEquals(BETA, items.get(0).path("word_id").asText());
            assertEquals(
                Sql.first(
                        db,
                        "SELECT MIN(word_id) AS id FROM desktop_word_links WHERE entry_id=?",
                        ALPHA)
                    .path("id")
                    .asText(),
                items.get(1).path("word_id").asText());
            assertEquals(
                ALPHA,
                Json.MAPPER
                    .readTree(items.get(1).path("word_ref").asText())
                    .path("entryId")
                    .asText());
            return null;
          });
    }
  }

  @Test
  void fullSizedColdDictionaryAndSixModesReuseOneFrozenQueue() throws Exception {
    int size = 811092;
    Path file = index(size);
    try (var database = new Database(directory);
        var service =
            new LeximeetService(
                database, CLOCK, new RuntimeDiagnostics(new StartupTiming(System.nanoTime())))) {
      mount(service, file, size);
      long started = System.nanoTime();
      assertTimeout(Duration.ofSeconds(15), () -> checkpoint(service, "dictionary", "copy"));
      System.out.printf(
          "cold-practice dictionary %d entries: %.3fs%n",
          size, (System.nanoTime() - started) / 1e9);
      for (String mode : DesktopPractice.MODES) checkpoint(service, "dictionary", mode);
      database.read(
          db -> {
            assertEquals(
                size,
                Sql.first(db, "SELECT COUNT(*) AS n FROM desktop_practice_items")
                    .path("n")
                    .asInt());
            assertEquals(
                size - 1,
                Sql.first(db, "SELECT MAX(position) AS n FROM desktop_practice_items")
                    .path("n")
                    .asInt());
            return null;
          });
    }
  }
}
