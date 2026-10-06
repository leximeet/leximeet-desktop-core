package app.leximeet.core;

import static app.leximeet.core.DesktopWorkspaceTest.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// 整库状态筛选使用真实事件和 SQLite，覆盖跨页、边界、旧库迁移与公共虚拟词。
class DesktopWordFilterTest {
  @TempDir Path directory;

  DesktopWorkspaceTest fixture() {
    var fixture = new DesktopWorkspaceTest();
    fixture.directory = directory;
    return fixture;
  }

  ObjectNode query(String status) {
    return Json.MAPPER.createObjectNode().put("scope", "dictionary").put("learningStatus", status);
  }

  ObjectNode signal(String word, String signal) {
    return Json.MAPPER
        .createObjectNode()
        .put("action", "practiceRecord")
        .put("wordId", word)
        .put("mode", "word-list")
        .put("signal", signal)
        .put("submissionId", UUID.randomUUID().toString());
  }

  @Test
  void scoreFiltersIncludeUntouchedWordsAndAreIndependentOfLegacyStatus() throws Exception {
    var fixture = fixture();
    var index = fixture.index(directory);
    try (var service = new LeximeetService(directory, CLOCK)) {
      fixture.mount(service, index);
      assertEquals(2, service.desktop().query(query("new")).path("total").asInt());
      assertTrue(service.snapshot().path("words").isEmpty(), "筛选不能物化公共词条");
      var event = signal(ALPHA, "familiar");
      service.desktop().command(event);
      service.desktop().command(event); // 重试只计一次分。
      var learning = service.desktop().query(query("learning"));
      assertEquals(1, learning.path("total").asInt());
      assertEquals(11, learning.path("words").get(0).path("familiarity").path("score").asInt());
      assertEquals("learning", learning.path("words").get(0).path("learningStatus").asText());
      assertEquals(1, service.desktop().query(query("new")).path("total").asInt());
      service.desktop().command(signal(ALPHA, "reveal"));
      var review = service.desktop().query(query("new"));
      assertEquals(1, review.path("total").asInt());
      assertEquals(1, service.desktop().query(query("learning")).path("total").asInt());
      assertEquals(0, service.desktop().query(query("mastered")).path("total").asInt());
      assertThrows(ApiException.class, () -> service.desktop().query(query("due")));
    }
    // 旧字段只能保留历史，不能绕过学习事件把单词算成 20 分。
    try (var db =
        DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("leximeet.sqlite"))) {
      Sql.execute(db, "UPDATE words SET status='mastered'");
    }
    try (var service = new LeximeetService(directory, CLOCK)) {
      fixture.mount(service, index);
      assertEquals(0, service.desktop().query(query("mastered")).path("total").asInt());
      assertEquals(1, service.desktop().query(query("new")).path("total").asInt());
    }
  }

  @Test
  void thirtyIsMasteredAndOneSetbackKeepsResting() throws Exception {
    var fixture = fixture();
    var index = fixture.index(directory);
    for (int day = 0; day < 5; day++) {
      try (var service =
          new LeximeetService(directory, Clock.offset(CLOCK, Duration.ofDays(day)))) {
        fixture.mount(service, index);
        for (int n = 0; n < 4; n++) service.desktop().command(signal(ALPHA, "familiar"));
        assertEquals(
            day == 4 ? 1 : 0, service.desktop().query(query("mastered")).path("total").asInt());
      }
    }
    try (var service = new LeximeetService(directory, Clock.offset(CLOCK, Duration.ofDays(5)))) {
      fixture.mount(service, index);
      var mastered = service.desktop().query(query("mastered"));
      assertEquals(30, mastered.path("words").get(0).path("familiarity").path("score").asInt());
      service.desktop().command(signal(ALPHA, "unfamiliar"));
      assertEquals(1, service.desktop().query(query("mastered")).path("total").asInt());
      assertEquals(
          29,
          service
              .desktop()
              .query(query("mastered"))
              .path("words")
              .get(0)
              .path("familiarity")
              .path("score")
              .asInt());
    }
  }

  @Test
  void filterRunsBeforePaginationAndCombinesWithScopeAndSearch() throws Exception {
    var fixture = fixture();
    var index = fixture.index(directory);
    try (var service = new LeximeetService(directory, CLOCK)) {
      fixture.mount(service, index);
      for (int n = 0; n < 45; n++) {
        String word = "sample" + (char) ('a' + n / 26) + (char) ('a' + n % 26);
        service
            .desktop()
            .command(
                Json.MAPPER
                    .createObjectNode()
                    .put("action", "capture")
                    .put("word", word)
                    .put("context", "Context for " + word));
      }
      service.desktop().command(signal("samplebs", "familiar")); // 在原始列表的第 45 行。
      var filter = query("learning").put("scope", "manual").put("limit", 2);
      var page = service.desktop().query(filter);
      assertEquals(1, page.path("total").asInt());
      assertEquals("samplebs", page.path("words").get(0).path("word").asText());
      assertEquals(
          0, service.desktop().query(filter.put("search", "samplea")).path("total").asInt());
      var review =
          service
              .desktop()
              .query(query("new").put("scope", "manual").put("offset", 40).put("limit", 10));
      assertEquals(44, review.path("total").asInt());
      assertEquals(4, review.path("words").size());
      assertEquals(
          2,
          service
              .desktop()
              .query(query("new").put("scope", "catalog").put("catalogId", "exam:test"))
              .path("total")
              .asInt());
    }
  }

  @Test
  void fileRestoreRebuildsScoreCacheFromEvents() throws Exception {
    var fixture = fixture();
    var index = fixture.index(directory);
    Path archive = directory.resolve("backup.sqlite");
    try (var db = new Database(directory);
        var service =
            new LeximeetService(
                db, CLOCK, new RuntimeDiagnostics(new StartupTiming(System.nanoTime())))) {
      fixture.mount(service, index);
      service.desktop().command(signal(ALPHA, "familiar"));
      db.vacuumInto(archive);
    }
    try (var db = DriverManager.getConnection("jdbc:sqlite:" + archive)) {
      Sql.execute(db, "UPDATE desktop_familiarity SET score=20");
    }
    try (var db = new Database(directory.resolve("restored"));
        var service =
            new LeximeetService(
                db, CLOCK, new RuntimeDiagnostics(new StartupTiming(System.nanoTime())))) {
      db.restoreFrom(archive);
      fixture.mount(service, fixture.index(directory.resolve("restored")));
      assertEquals(0, service.desktop().query(query("mastered")).path("total").asInt());
      assertEquals(1, service.desktop().query(query("learning")).path("total").asInt());
    }
  }
}
