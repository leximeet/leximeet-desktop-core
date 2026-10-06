package app.leximeet.core;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// 用真实 SQLite 与固定时钟验证跨目标、重启、撤销和读写边界，不使用内存业务替身。
class DesktopWorkspaceTest {
  @TempDir Path directory;
  static final String SHA = "a".repeat(64),
      ALPHA = "10000000-0000-4000-8000-000000000001",
      BETA = "10000000-0000-4000-8000-000000000002";
  static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-10-01T12:00:00Z"), ZoneId.of("Asia/Shanghai"));

  static JsonNode json(String value) throws Exception {
    return Json.MAPPER.readTree(value);
  }

  Path index(Path root) throws Exception {
    Path file = root.resolve("dictionaries/test.sqlite");
    Files.createDirectories(file.getParent());
    try (var db = DriverManager.getConnection("jdbc:sqlite:" + file);
        var sql = db.createStatement()) {
      sql.execute(
          "CREATE TABLE metadata(id INTEGER PRIMARY KEY,payload TEXT); CREATE TABLE ignored(x)");
      sql.execute(
          "CREATE TABLE entries(id TEXT PRIMARY KEY,headword TEXT,normalized TEXT,meaning"
              + " TEXT,payload TEXT,position INTEGER)");
      sql.execute(
          "CREATE TABLE catalogs(id TEXT PRIMARY KEY,category TEXT,title TEXT,payload TEXT)");
      sql.execute("CREATE TABLE members(catalog_id TEXT,entry_id TEXT,position INTEGER)");
      Sql.execute(
          db,
          "INSERT INTO metadata VALUES(1,?)",
          "{\"version\":\"0.0.3\",\"edition\":\"core-text\",\"entryCount\":2,\"manifestSha\":\""
              + SHA
              + "\"}");
      Sql.execute(
          db,
          "INSERT INTO catalogs"
              + " VALUES('exam:test','exam','测试词库','{\"catalog_id\":\"exam:test\",\"title_zh\":\"测试词库\",\"entry_count\":2,\"category\":\"exam\"}')");
      for (int i = 0; i < 2; i++) {
        String id = i == 0 ? ALPHA : BETA, word = i == 0 ? "alpha" : "beta";
        Sql.execute(
            db,
            "INSERT INTO entries VALUES(?,?,?,?,?,?)",
            id,
            word,
            word,
            "中文" + i,
            "{\"entry_id\":\""
                + id
                + "\",\"headword\":\""
                + word
                + "\",\"lookup_key\":\""
                + word
                + "\",\"schema_version\":\"leximeet.entry.v2\",\"senses\":[{\"short_gloss\":\"中文"
                + i
                + "\",\"examples\":[{\"sentence\":\""
                + word
                + " appears here.\"}]}]}",
            i);
        Sql.execute(db, "INSERT INTO members VALUES('exam:test',?,?)", id, 1 - i);
      }
    }
    return file;
  }

  void mount(LeximeetService service, Path file) throws Exception {
    // 路径由 JSON 节点保存，避免 Windows 反斜杠被当成 JSON 转义符。
    service
        .desktop()
        .mount(
            Json.MAPPER
                .createObjectNode()
                .put("file", file.toString())
                .put("edition", "core-text")
                .put("version", "0.0.3")
                .put("manifestSha", SHA)
                .put("entryCount", 2));
  }

  void goal(LeximeetService service, String value) throws Exception {
    int revision = service.desktop().state().path("profile").path("revision").asInt();
    service
        .desktop()
        .command(
            json(
                "{\"action\":\"setGoal\",\"goal\":\""
                    + value
                    + "\",\"expectedRevision\":"
                    + revision
                    + "}"));
    int planRevision = service.desktop().state().path("profile").path("revision").asInt();
    service
        .desktop()
        .command(
            json(
                "{\"action\":\"savePlan\",\"planEnabled\":true,\"expectedRevision\":"
                    + planRevision
                    + "}"));
  }

  JsonNode review(LeximeetService service, String id, int round, String rating) throws Exception {
    return service
        .desktop()
        .command(
            json(
                "{\"action\":\"review\",\"wordId\":\""
                    + id
                    + "\",\"round\":"
                    + round
                    + ",\"rating\":\""
                    + rating
                    + "\",\"kind\":\"new\",\"submissionId\":\""
                    + UUID.randomUUID()
                    + "\"}"));
  }

  @Test
  void publicWordNoteAndRelationsAreSavedAtomicallyWithOptimisticVersion() throws Exception {
    Path index = index(directory);
    try (var service = new LeximeetService(directory, CLOCK)) {
      mount(service, index);
      service
          .desktop()
          .command(
              json(
                  "{\"action\":\"saveNote\",\"wordId\":\""
                      + ALPHA
                      + "\",\"note\":\"这是我自己的理解\",\"bookIds\":[],\"expectedRevision\":0}"));
      JsonNode saved =
          service.desktop().query(json("{\"kind\":\"detail\",\"wordId\":\"" + ALPHA + "\"}"));
      assertEquals("这是我自己的理解", saved.path("note").asText());
      assertTrue(saved.path("manualActive").asBoolean());
      assertTrue(saved.path("books").isEmpty());
      int revision = saved.path("revision").asInt();
      assertThrows(
          ApiException.class,
          () ->
              service
                  .desktop()
                  .command(
                      json(
                          "{\"action\":\"saveNote\",\"wordId\":\""
                              + ALPHA
                              + "\",\"note\":\"迟到草稿\",\"bookIds\":[],\"expectedRevision\":"
                              + (revision - 1)
                              + "}")));
      assertEquals(
          "这是我自己的理解",
          service
              .desktop()
              .query(json("{\"kind\":\"detail\",\"wordId\":\"" + ALPHA + "\"}"))
              .path("note")
              .asText());
    }
  }

  @Test
  void deletingSecondaryBookInvalidatesOldDraftWithoutDeletingWord() throws Exception {
    try (var service = new LeximeetService(directory, CLOCK)) {
      mount(service, index(directory));
      String primary = createRelation(service, "createBook", "主词本", "books");
      String secondary = createRelation(service, "createBook", "次词本", "books");
      var draft =
          Json.MAPPER
              .createObjectNode()
              .put("action", "saveNote")
              .put("wordId", ALPHA)
              .put("note", "保留个人理解")
              .put("expectedRevision", 0);
      draft.putArray("bookIds").add(primary).add(secondary);
      service.desktop().command(draft);
      int revision = wordDetail(service, ALPHA).path("revision").asInt();
      service
          .desktop()
          .command(
              Json.MAPPER.createObjectNode().put("action", "deleteBook").put("bookId", secondary));
      JsonNode afterBook = wordDetail(service, ALPHA);
      assertEquals(revision + 1, afterBook.path("revision").asInt());
      assertEquals(1, afterBook.path("books").size());
      assertEquals(primary, afterBook.path("books").path(0).path("id").asText());
      draft.put("expectedRevision", revision);
      assertEquals(
          409, assertThrows(ApiException.class, () -> service.desktop().command(draft)).status());
      assertEquals(1, service.snapshot().path("words").size());
    }
  }

  @Test
  void relationshipOnlyEditsValidateAtomicallyAndRetainReorderedPrimaryBook() throws Exception {
    try (var service = new LeximeetService(directory, CLOCK)) {
      mount(service, index(directory));
      String first = createRelation(service, "createBook", "第一个", "books");
      String second = createRelation(service, "createBook", "第二个", "books");
      var edit =
          Json.MAPPER
              .createObjectNode()
              .put("action", "saveNote")
              .put("wordId", ALPHA)
              .put("expectedRevision", 0);
      edit.putArray("bookIds").add(first).add(second);
      service.desktop().command(edit);
      int revision = wordDetail(service, ALPHA).path("revision").asInt();
      edit.put("expectedRevision", revision);
      edit.putArray("bookIds").add(second).add(first);
      service.desktop().command(edit);
      JsonNode reordered = wordDetail(service, ALPHA);
      assertEquals(revision + 1, reordered.path("revision").asInt());
      assertEquals(2, reordered.path("books").size());
      edit.put("expectedRevision", revision + 1).put("note", "失败保存不可写入");
      edit.putArray("bookIds").add(UUID.randomUUID().toString());
      assertEquals(
          404, assertThrows(ApiException.class, () -> service.desktop().command(edit)).status());
      assertEquals("", wordDetail(service, ALPHA).path("note").asText());
      assertEquals(revision + 1, wordDetail(service, ALPHA).path("revision").asInt());
      edit.putArray("bookIds").add(first).add(first);
      assertEquals(
          400, assertThrows(ApiException.class, () -> service.desktop().command(edit)).status());
      assertEquals(2, wordDetail(service, ALPHA).path("books").size());
    }
  }

  String createRelation(LeximeetService service, String action, String name, String collection)
      throws Exception {
    JsonNode state =
        service
            .desktop()
            .command(Json.MAPPER.createObjectNode().put("action", action).put("name", name));
    for (JsonNode item : state.path(collection))
      if (name.equals(item.path("name").asText())) return item.path("id").asText();
    throw new AssertionError("缺少新建关系: " + name);
  }

  JsonNode wordDetail(LeximeetService service, String id) throws Exception {
    return service
        .desktop()
        .query(Json.MAPPER.createObjectNode().put("kind", "detail").put("wordId", id));
  }

  @Test
  void goalMembersStayVirtualAndManualEncounterUnionsWithoutDuplicates() throws Exception {
    Path index = index(directory);
    try (var service = new LeximeetService(directory, CLOCK)) {
      mount(service, index);
      assertTrue(service.desktop().state().path("profile").path("goal").isNull());
      goal(service, "exam:test");
      assertEquals(
          BETA,
          service
              .desktop()
              .query(json("{\"scope\":\"library\"}"))
              .path("words")
              .path(0)
              .path("id")
              .asText());
      assertEquals(
          BETA,
          service.desktop().state().path("queue").path("planned").path(0).path("id").asText());
      assertEquals(
          2, service.desktop().query(json("{\"scope\":\"library\"}")).path("total").asInt());
      assertEquals(0, service.snapshot().path("words").size());
      service.desktop().command(json("{\"action\":\"collect\",\"wordId\":\"" + ALPHA + "\"}"));
      assertEquals(0, service.snapshot().path("encounters").size());
      service
          .desktop()
          .command(
              json(
                  "{\"action\":\"capture\",\"wordId\":\""
                      + ALPHA
                      + "\",\"context\":\"Alpha appears in this sentence.\"}"));
      assertEquals(1, service.snapshot().path("encounters").size());
      assertEquals(2, service.desktop().state().path("queue").path("tasks").size());
      assertEquals(1, service.desktop().state().path("queue").path("encounters").size());
      assertEquals(1, service.desktop().state().path("queue").path("planned").size());
      service.desktop().command(json("{\"action\":\"trash\",\"wordId\":\"" + ALPHA + "\"}"));
      assertEquals(
          1, service.desktop().query(json("{\"scope\":\"library\"}")).path("total").asInt());
      assertThrows(
          ApiException.class,
          () ->
              service
                  .desktop()
                  .command(json("{\"action\":\"capture\",\"word\":\"beta\",\"context\":\"\"}")));
    }
  }

  @Test
  void boundedPagesKeepUnionOrderPublicMeaningAndTrashSemantics() throws Exception {
    try (var service = new LeximeetService(directory, CLOCK)) {
      mount(service, index(directory));
      CoreTestData.collect(service, json("{\"word\":\"alpha\",\"note\":\"保留笔记\"}"));
      service.desktop().command(json("{\"action\":\"collect\",\"wordId\":\"" + BETA + "\"}"));
      service
          .desktop()
          .command(json("{\"action\":\"collect\",\"word\":\"gamma\",\"note\":\"second needle\"}"));
      goal(service, "exam:test");
      JsonNode all = service.desktop().query(json("{\"scope\":\"library\"}"));
      assertEquals(3, all.path("total").asInt());
      assertEquals(3, all.path("words").size());
      assertEquals("beta", all.path("words").path(0).path("word").asText());
      JsonNode middle =
          service.desktop().query(json("{\"scope\":\"library\",\"offset\":1,\"limit\":1}"));
      assertEquals(3, middle.path("total").asInt());
      assertEquals(1, middle.path("words").size());
      assertEquals("alpha", middle.path("words").path(0).path("word").asText());
      assertEquals("保留笔记", middle.path("words").path(0).path("note").asText());
      assertEquals(
          1,
          service
              .desktop()
              .query(json("{\"scope\":\"library\",\"search\":\"gamma\"}"))
              .path("total")
              .asInt());
      assertEquals(
          1,
          service
              .desktop()
              .query(json("{\"scope\":\"dictionary\",\"search\":\"中文0\"}"))
              .path("total")
              .asInt());
      assertEquals(
          0,
          service
              .desktop()
              .query(json("{\"scope\":\"library\",\"search\":\"%\"}"))
              .path("total")
              .asInt());
      assertTrue(
          service
              .desktop()
              .query(json("{\"scope\":\"library\",\"offset\":100,\"limit\":50}"))
              .path("words")
              .isEmpty());
      String personal = wordDetail(service, ALPHA).path("id").asText();
      service
          .desktop()
          .command(Json.MAPPER.createObjectNode().put("action", "trash").put("wordId", personal));
      assertEquals(
          2, service.desktop().query(json("{\"scope\":\"library\"}")).path("total").asInt());
      assertEquals(1, service.desktop().query(json("{\"scope\":\"trash\"}")).path("total").asInt());
      goal(service, "");
      assertEquals(
          2, service.desktop().query(json("{\"scope\":\"library\"}")).path("total").asInt());
    }
  }

  @Test
  void confirmedEncountersDoNotDisappearWhenPlannedNewQuotaIsUsed() throws Exception {
    try (var service = new LeximeetService(directory, CLOCK)) {
      mount(service, index(directory));
      goal(service, "exam:test");
      service
          .desktop()
          .command(
              json(
                  "{\"action\":\"savePlan\",\"dailyNew\":1,\"dailyReview\":1,\"studyTime\":\"20:30\",\"expectedRevision\":3}"));
      for (String id : java.util.List.of(ALPHA, BETA))
        service
            .desktop()
            .command(
                json(
                    "{\"action\":\"capture\",\"wordId\":\""
                        + id
                        + "\",\"context\":\"alpha beta 真实语境\"}"));
      JsonNode queue = service.desktop().state().path("queue");
      assertEquals(2, queue.path("encounters").size());
      assertEquals(0, queue.path("planned").size());
      assertEquals(2, queue.path("tasks").path(0).path("sources").size());
      review(service, ALPHA, 1, "good");
      assertEquals(2, service.desktop().state().path("queue").path("encounters").size());
      review(service, BETA, 1, "good");
      JsonNode state = service.desktop().state();
      assertEquals(0, state.path("queue").path("newDone").asInt());
      assertEquals(1, state.path("queue").path("newRemaining").asInt());
      assertEquals(0, state.path("goalProgress").path("learned").asInt());
      assertEquals(2, state.path("goalProgress").path("remaining").asInt());
      service.desktop().command(json("{\"action\":\"collect\",\"word\":\"gamma\"}"));
      String gamma =
          service
              .desktop()
              .query(json("{\"scope\":\"manual\",\"search\":\"gamma\"}"))
              .path("words")
              .path(0)
              .path("id")
              .asText();
      assertThrows(ApiException.class, () -> review(service, gamma, 1, "good"));
    }
  }

  @Test
  void feedbackQuotaSurvivesGoalChangeRestartAndUndoWithReplay() throws Exception {
    Path index = index(directory);
    String latest;
    try (var service = new LeximeetService(directory, CLOCK)) {
      mount(service, index);
      goal(service, "exam:test");
      review(service, ALPHA, 1, "again");
      assertEquals(0, service.desktop().state().path("queue").path("newDone").asInt());
      JsonNode state = review(service, ALPHA, 2, "good");
      assertEquals(0, state.path("queue").path("newDone").asInt());
      latest = state.path("queue").path("lastReviewId").asText();
      goal(service, "dictionary");
      assertEquals(0, service.desktop().state().path("queue").path("newDone").asInt());
      assertThrows(ApiException.class, () -> review(service, ALPHA, 2, "good"));
    }
    try (var service = new LeximeetService(directory, CLOCK)) {
      mount(service, index);
      assertEquals(0, service.desktop().state().path("queue").path("newDone").asInt());
      service
          .desktop()
          .command(json("{\"action\":\"undoReview\",\"reviewId\":\"" + latest + "\"}"));
      assertEquals(0, service.desktop().state().path("queue").path("newDone").asInt());
    }
    try (var db =
        DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("leximeet.sqlite"))) {
      DesktopReviews.verify(db);
    }
  }

  @Test
  void midnightAndStrictnessUseActualCompletionFacts() throws Exception {
    Path index = index(directory);
    try (var service = new LeximeetService(directory, CLOCK)) {
      mount(service, index);
      goal(service, "dictionary");
      service.updateSettings(json("{\"reviewStrictness\":\"strict\"}"));
      review(service, ALPHA, 1, "good");
      assertEquals(0, service.desktop().state().path("queue").path("newDone").asInt());
      review(service, ALPHA, 2, "easy");
      assertEquals(0, service.desktop().state().path("queue").path("newDone").asInt());
    }
    Clock tomorrow = Clock.fixed(Instant.parse("2026-10-01T16:01:00Z"), CLOCK.getZone());
    try (var service = new LeximeetService(directory, tomorrow)) {
      mount(service, index);
      assertEquals("2026-10-02", service.desktop().state().path("today").asText());
      assertEquals(0, service.desktop().state().path("queue").path("newDone").asInt());
    }
  }

  @Test
  void practiceSixModesKeepIndependentCursorsAndDrafts() throws Exception {
    Path index = index(directory);
    try (var service = new LeximeetService(directory, CLOCK)) {
      mount(service, index);
      goal(service, "dictionary");
      for (String mode : DesktopPractice.MODES) {
        service
            .desktop()
            .command(
                json(
                    "{\"action\":\"practiceSave\",\"scope\":\"library\",\"cursor\":1,\"mode\":\""
                        + mode
                        + "\",\"wordId\":\""
                        + ALPHA
                        + "\",\"draft\":{\"input\":\"alpha\"}}"));
        String attempt = UUID.randomUUID().toString();
        var q =
            service
                .desktop()
                .query(
                    Json.MAPPER
                        .createObjectNode()
                        .put("kind", "practiceQuestion")
                        .put("wordId", ALPHA)
                        .put("mode", mode)
                        .put("attemptId", attempt));
        var answer =
            Json.MAPPER
                .createObjectNode()
                .put("action", "practiceRecord")
                .put("wordId", ALPHA)
                .put("mode", mode)
                .put("attemptId", attempt)
                .put("submissionId", UUID.randomUUID().toString());
        if (mode.equals("word-list")) answer.put("signal", "familiar");
        else if (mode.equals("meaning-choice")) {
          for (JsonNode option : q.path("options"))
            if (option.path("text").asText().equals("中文0"))
              answer.put("choiceId", option.path("id").asText());
        } else answer.put("answer", "alpha");
        service.desktop().command(answer);
      }
      assertEquals(6, service.desktop().state().path("insights").path("practice").asInt());
      assertEquals(0, service.desktop().state().path("insights").path("learned").asInt());
      assertEquals(
          "alpha",
          service
              .desktop()
              .query(
                  json(
                      "{\"kind\":\"practice\",\"scope\":\"library\",\"mode\":\"copy\",\"wordId\":\""
                          + ALPHA
                          + "\"}"))
              .path("draft")
              .path("input")
              .asText());
    }
  }

  @Test
  void onboardingStartsWithSidebarAndOptionalSetupThenUsesActualPractice() throws Exception {
    Path index = index(directory);
    try (var service = new LeximeetService(directory, CLOCK)) {
      mount(service, index);
      service.desktop().command(json("{\"action\":\"guideStart\"}"));
      service.desktop().command(json("{\"action\":\"guideNext\",\"step\":\"sidebar\"}"));
      // 整个目标与计划都跳过，不制造公共词条或计划。
      service.desktop().command(json("{\"action\":\"guideNext\",\"step\":\"goal\"}"));
      service.desktop().command(json("{\"action\":\"guideNext\",\"step\":\"plan\"}"));
      assertFalse(service.desktop().state().path("profile").path("planEnabled").asBoolean());
      assertTrue(service.desktop().state().path("profile").path("goal").isNull());
      service
          .desktop()
          .command(
              json(
                  "{\"action\":\"practiceRecord\",\"wordId\":\""
                      + ALPHA
                      + "\",\"mode\":\"word-list\",\"signal\":\"familiar\",\"submissionId\":\""
                      + UUID.randomUUID()
                      + "\"}"));
      assertEquals(4, service.desktop().state().path("guide").path("completed").size());
      service.desktop().nativeEvent(json("{\"event\":\"audio\"}"));
      service
          .desktop()
          .command(
              json(
                  "{\"action\":\"practiceRecord\",\"wordId\":\""
                      + ALPHA
                      + "\",\"mode\":\"copy\",\"answer\":\"alpha\",\"submissionId\":\""
                      + UUID.randomUUID()
                      + "\"}"));
      for (String step :
          java.util.List.of(
              "clipboard",
              "trash",
              "dictionary",
              "insights",
              "theme",
              "audioSettings",
              "captureSettings"))
        service.desktop().command(json("{\"action\":\"guideNext\",\"step\":\"" + step + "\"}"));
      var result = service.desktop().state();
      assertEquals(DesktopGuide.STEPS.size(), result.path("guide").path("completed").size());
      assertFalse(result.path("guide").path("active").asBoolean());
      assertEquals(0, result.path("insights").path("encounters").asInt());
      assertEquals(0, result.path("insights").path("trash").asInt());
      assertFalse(result.has("tags"));
    }
  }

  @Test
  void frozenSessionSurvivesPauseRestartDuplicateAndUndo() throws Exception {
    Path file = index(directory);
    JsonNode submission =
        json(
            "{\"action\":\"review\",\"wordId\":\""
                + BETA
                + "\",\"kind\":\"new\",\"rating\":\"good\",\"round\":1,\"submissionId\":\""
                + UUID.randomUUID()
                + "\"}");
    try (var service = new LeximeetService(directory, CLOCK)) {
      mount(service, file);
      goal(service, "exam:test");
      service.desktop().command(json("{\"action\":\"studyStart\"}"));
      assertThrows(
          ApiException.class, () -> service.desktop().command(json("{\"action\":\"studyStart\"}")));
      assertThrows(ApiException.class, () -> review(service, ALPHA, 1, "good"));
      service.desktop().command(submission);
      service.desktop().command(submission);
      assertEquals(1, service.desktop().state().path("session").path("cursor").asInt());
      service.desktop().command(json("{\"action\":\"studyPause\"}"));
    }
    try (var service = new LeximeetService(directory, CLOCK)) {
      mount(service, file);
      assertEquals("paused", service.desktop().state().path("session").path("phase").asText());
      service.desktop().command(json("{\"action\":\"studyResume\"}"));
      String latest = service.desktop().state().path("queue").path("lastReviewId").asText();
      service
          .desktop()
          .command(json("{\"action\":\"undoReview\",\"reviewId\":\"" + latest + "\"}"));
      assertEquals(0, service.desktop().state().path("session").path("cursor").asInt());
      assertEquals(0, service.desktop().state().path("queue").path("newDone").asInt());
      assertThrows(ApiException.class, () -> service.desktop().command(submission));
    }
    try (var service =
        new LeximeetService(directory, Clock.offset(CLOCK, java.time.Duration.ofDays(1)))) {
      mount(service, file);
      assertThrows(
          ApiException.class,
          () -> service.desktop().command(json("{\"action\":\"studyResume\"}")));
      service.desktop().command(json("{\"action\":\"studyEnd\"}"));
      assertEquals(
          "running",
          service
              .desktop()
              .command(json("{\"action\":\"studyStart\"}"))
              .path("session")
              .path("phase")
              .asText());
    }
  }

  @Test
  void explicitCustomIdentitySurvivesDictionaryMountWithoutBecomingPublic() throws Exception {
    String personal;
    try (var service = new LeximeetService(directory, CLOCK)) {
      personal =
          CoreTestData.collect(service, json("{\"word\":\"alpha\",\"note\":\"原来的笔记\"}"))
              .path("words")
              .path(0)
              .path("id")
              .asText();
      Path file = index(directory);
      mount(service, file);
      goal(service, "dictionary");
      JsonNode detail =
          service.desktop().query(json("{\"kind\":\"detail\",\"wordId\":\"" + ALPHA + "\"}"));
      assertEquals(personal, detail.path("id").asText());
      assertEquals("原来的笔记", detail.path("note").asText());
      try (var db =
          DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("leximeet.sqlite"))) {
        Sql.execute(db, "ATTACH DATABASE ? AS dictionary_test", file.toUri() + "?mode=ro");
        assertThrows(
            java.sql.SQLException.class,
            () -> Sql.execute(db, "DELETE FROM dictionary_test.entries"));
      }
      assertThrows(
          ApiException.class,
          () ->
              service
                  .desktop()
                  .mount(
                      json(
                          "{\"file\":\""
                              + file
                              + "\",\"edition\":\"full-text\",\"version\":\"0.0.3\",\"manifestSha\":\""
                              + SHA
                              + "\",\"entryCount\":2}")));
      assertFalse(service.desktop().state().path("dictionary").path("ready").asBoolean());
      mount(service, file);
      assertEquals(
          3, service.desktop().query(json("{\"scope\":\"library\"}")).path("total").asInt());
    }
  }

  @Test
  void prereleaseDatabaseIsRejectedWithoutRewritingOriginal() throws Exception {
    try (var service = new LeximeetService(directory, CLOCK)) {
      service.snapshot();
    }
    Path file = directory.resolve("leximeet.sqlite");
    try (var db = DriverManager.getConnection("jdbc:sqlite:" + file)) {
      Sql.execute(db, "PRAGMA user_version=13");
    }
    byte[] before = Files.readAllBytes(file);
    assertThrows(IllegalArgumentException.class, () -> new LeximeetService(directory, CLOCK));
    assertArrayEquals(before, Files.readAllBytes(file));
  }

  @Test
  void tickingClockFeedbackRemainsReplayableAfterBackupRestoreAndUndo() throws Exception {
    var ticks = new java.util.concurrent.atomic.AtomicLong();
    Clock ticking =
        new Clock() {
          public ZoneId getZone() {
            return CLOCK.getZone();
          }

          public Clock withZone(ZoneId zone) {
            return Clock.fixed(instant(), zone);
          }

          public Instant instant() {
            return CLOCK.instant().plusNanos(ticks.incrementAndGet());
          }
        };
    try (var service = new LeximeetService(directory, ticking)) {
      mount(service, index(directory));
      goal(service, "dictionary");
      review(service, ALPHA, 1, "again");
      String latest = review(service, ALPHA, 2, "good").path("queue").path("lastReviewId").asText();
      try (var backup = service.exportPortableBackup()) {
        review(service, BETA, 1, "good");
        service.restorePortableBackup(backup.file());
        assertEquals(0, service.desktop().state().path("queue").path("newDone").asInt());
      }
      service
          .desktop()
          .command(json("{\"action\":\"undoReview\",\"reviewId\":\"" + latest + "\"}"));
      assertEquals(0, service.desktop().state().path("queue").path("newDone").asInt());
      try (var db =
          DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("leximeet.sqlite"))) {
        DesktopReviews.verify(db);
      }
    }
  }

  @Test
  void portableArchiveRestoresNewFactsAndRejectsTamperedMemory() throws Exception {
    Path index = index(directory);
    try (var service = new LeximeetService(directory, CLOCK)) {
      mount(service, index);
      goal(service, "dictionary");
      review(service, ALPHA, 1, "good");
      try (var backup = service.exportPortableBackup()) {
        review(service, BETA, 1, "good");
        service.restorePortableBackup(backup.file());
        assertEquals(0, service.desktop().state().path("insights").path("learned").asInt());
        try (var db = DriverManager.getConnection("jdbc:sqlite:" + backup.file())) {
          Sql.execute(db, "UPDATE desktop_cards SET due_at='2099-01-01T00:00:00Z'");
        }
        assertThrows(ApiException.class, () -> service.restorePortableBackup(backup.file()));
        assertEquals(0, service.desktop().state().path("insights").path("learned").asInt());
      }
    }
  }
}
