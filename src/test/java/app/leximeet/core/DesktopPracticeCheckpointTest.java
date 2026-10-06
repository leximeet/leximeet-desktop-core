package app.leximeet.core;

import static app.leximeet.core.DesktopWorkspaceTest.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// 本机前台采用真实冻结队列；提示、自报字段、重启与再开始一轮都在 SQLite 验证。
class DesktopPracticeCheckpointTest {
  @TempDir Path directory;

  DesktopWorkspaceTest fixture() {
    var f = new DesktopWorkspaceTest();
    f.directory = directory;
    return f;
  }

  ObjectNode query(String kind, String scope) {
    return Json.MAPPER.createObjectNode().put("kind", kind).put("scope", scope);
  }

  @Test
  void emptyGoalAndDictionaryAreSeparateFrozenScopes() throws Exception {
    var f = fixture();
    try (var s = new LeximeetService(directory, CLOCK)) {
      f.mount(s, f.index(directory));
      assertEquals(0, s.desktop().query(query("practiceWords", "goal:none")).path("total").asInt());
      assertEquals(
          2, s.desktop().query(query("practiceWords", "dictionary")).path("total").asInt());
      assertEquals(0, s.desktop().query(query("practiceWords", "library")).path("total").asInt());
    }
  }

  @Test
  void emptyLibraryRefreshesAfterCaptureAndResetKeepsEvents() throws Exception {
    var f = fixture();
    try (var s = new LeximeetService(directory, CLOCK)) {
      f.mount(s, f.index(directory));
      assertEquals(0, s.desktop().query(query("practiceWords", "library")).path("total").asInt());
      s.desktop()
          .command(
              Json.MAPPER
                  .createObjectNode()
                  .put("action", "capture")
                  .put("word", "alpha")
                  .put("context", "alpha appears here."));
      assertEquals(1, s.desktop().query(query("practiceWords", "library")).path("total").asInt());
      String old = s.desktop().query(query("practice", "library")).path("sessionId").asText();
      f.goal(s, "dictionary");
      var updated = s.desktop().query(query("practice", "library"));
      assertNotEquals(old, updated.path("sessionId").asText());
      assertEquals(2, s.desktop().query(query("practiceWords", "library")).path("total").asInt());
      assertNotEquals(
          updated.path("sessionId").asText(),
          s.desktop()
              .query(query("practice", "library").put("reset", true))
              .path("sessionId")
              .asText());
      assertEquals(1, s.snapshot().path("encounters").size());
    }
  }

  @Test
  void frozenCheckpointAndPerModeInputSurviveRestart() throws Exception {
    String session;
    var f = fixture();
    Path index = f.index(directory);
    try (var s = new LeximeetService(directory, CLOCK)) {
      f.mount(s, index);
      f.goal(s, "dictionary");
      session =
          s.desktop()
              .query(query("practice", "library").put("mode", "recall"))
              .path("sessionId")
              .asText();
      var save =
          query("unused", "library")
              .removeAll()
              .put("action", "practiceSave")
              .put("scope", "library")
              .put("cursor", 1)
              .put("mode", "recall")
              .put("wordId", ALPHA);
      save.set(
          "draft",
          Json.MAPPER
              .createObjectNode()
              .put("input", "al")
              .put("assisted", true)
              .put("answered", true));
      s.desktop().command(save);
      save.put("mode", "copy").withObject("draft").put("input", "a");
      s.desktop().command(save);
    }
    try (var s = new LeximeetService(directory, CLOCK)) {
      f.mount(s, index);
      var recall =
          s.desktop()
              .query(query("practice", "library").put("mode", "recall").put("wordId", ALPHA));
      assertEquals(session, recall.path("sessionId").asText());
      assertEquals(1, recall.path("cursor").asInt());
      assertEquals("al", recall.path("draft").path("input").asText());
      assertFalse(recall.path("draft").path("assisted").asBoolean());
      assertTrue(recall.path("draft").path("answered").isNull());
      assertEquals(
          "a",
          s.desktop()
              .query(query("practice", "library").put("mode", "copy").put("wordId", ALPHA))
              .path("draft")
              .path("input")
              .asText());
    }
  }

  @Test
  void sixModeCursorsAndResetStayIndependentAcrossRestart() throws Exception {
    var f = fixture();
    Path index = f.index(directory);
    String listeningSession;
    try (var service = new LeximeetService(directory, CLOCK)) {
      f.mount(service, index);
      f.goal(service, "dictionary");
      for (String mode : DesktopPractice.MODES) {
        int cursor = mode.equals("copy") ? 1 : mode.equals("listening") ? 2 : 0;
        service
            .desktop()
            .command(
                Json.MAPPER
                    .createObjectNode()
                    .put("action", "practiceSave")
                    .put("scope", "library")
                    .put("mode", mode)
                    .put("cursor", cursor));
      }
      listeningSession =
          service
              .desktop()
              .query(query("practice", "library").put("mode", "listening"))
              .path("sessionId")
              .asText();
      assertEquals(
          1,
          service
              .desktop()
              .query(query("practice", "library").put("mode", "copy"))
              .path("cursor")
              .asInt());
      service.desktop().query(query("practice", "library").put("mode", "copy").put("reset", true));
      assertEquals(
          0,
          service
              .desktop()
              .query(query("practice", "library").put("mode", "copy"))
              .path("cursor")
              .asInt());
      assertEquals(
          2,
          service
              .desktop()
              .query(query("practice", "library").put("mode", "listening"))
              .path("cursor")
              .asInt());
      assertEquals(
          2,
          service
              .lmcp()
              .database
              .read(db -> Sql.first(db, "SELECT COUNT(*) AS n FROM desktop_practice_items"))
              .path("n")
              .asInt());
    }
    try (var service = new LeximeetService(directory, CLOCK)) {
      f.mount(service, index);
      var listening =
          service.desktop().query(query("practice", "library").put("mode", "listening"));
      assertEquals(listeningSession, listening.path("sessionId").asText());
      assertEquals(2, listening.path("cursor").asInt());
      assertEquals(
          0,
          service
              .desktop()
              .query(query("practice", "library").put("mode", "word-list"))
              .path("cursor")
              .asInt());
    }
  }

  @Test
  void newCycleUsesNewAttemptAndPreservesOldEvents() throws Exception {
    var f = fixture();
    try (var s = new LeximeetService(directory, CLOCK)) {
      f.mount(s, f.index(directory));
      f.goal(s, "dictionary");
      var first =
          s.desktop()
              .query(
                  query("practiceQuestion", "library")
                      .put("wordId", ALPHA)
                      .put("mode", "copy")
                      .put("attemptId", UUID.randomUUID().toString()));
      var command =
          Json.MAPPER
              .createObjectNode()
              .put("action", "practiceRecord")
              .put("wordId", ALPHA)
              .put("scope", "library")
              .put("mode", "copy")
              .put("attemptId", first.path("attemptId").asText())
              .put("submissionId", UUID.randomUUID().toString())
              .put("answer", "alpha");
      assertEquals(1, s.desktop().command(command).path("practiceFeedback").path("delta").asInt());
      var second =
          s.desktop()
              .query(
                  query("practiceQuestion", "library")
                      .put("wordId", ALPHA)
                      .put("mode", "copy")
                      .put("attemptId", UUID.randomUUID().toString()));
      assertNotEquals(first.path("questionId"), second.path("questionId"));
      command
          .put("attemptId", second.path("attemptId").asText())
          .put("submissionId", UUID.randomUUID().toString());
      assertEquals(1, s.desktop().command(command).path("practiceFeedback").path("delta").asInt());
      assertEquals(
          12,
          s.desktop()
              .query(Json.MAPPER.createObjectNode().put("kind", "detail").put("wordId", ALPHA))
              .path("familiarity")
              .path("score")
              .asInt());
    }
  }

  @Test
  void correctingSameAttemptRestoresLatestAnswerWithoutExtraScoreOrCrossModeDraft()
      throws Exception {
    var f = fixture();
    try (var s = new LeximeetService(directory, CLOCK)) {
      f.mount(s, f.index(directory));
      f.goal(s, "dictionary");
      var q =
          s.desktop()
              .query(
                  query("practiceQuestion", "library")
                      .put("wordId", ALPHA)
                      .put("mode", "copy")
                      .put("attemptId", UUID.randomUUID().toString()));
      var answer =
          Json.MAPPER
              .createObjectNode()
              .put("action", "practiceRecord")
              .put("scope", "library")
              .put("wordId", ALPHA)
              .put("mode", "copy")
              .put("attemptId", q.path("attemptId").asText())
              .put("answer", "wrong")
              .put("submissionId", UUID.randomUUID().toString());
      assertFalse(s.desktop().command(answer).path("practiceFeedback").path("correct").asBoolean());
      answer.put("answer", "alpha").put("submissionId", UUID.randomUUID().toString());
      assertTrue(s.desktop().command(answer).path("practiceFeedback").path("correct").asBoolean());
      var restored =
          s.desktop().query(query("practice", "library").put("wordId", ALPHA).put("mode", "copy"));
      assertTrue(restored.path("draft").path("answered").asBoolean());
      assertEquals(
          9,
          s.desktop()
              .query(Json.MAPPER.createObjectNode().put("kind", "detail").put("wordId", ALPHA))
              .path("familiarity")
              .path("score")
              .asInt());
      assertTrue(
          s.desktop()
              .query(query("practice", "library").put("wordId", ALPHA).put("mode", "recall"))
              .path("draft")
              .path("answered")
              .isNull());
      s.desktop().query(query("practice", "library").put("reset", true).put("mode", "copy"));
      assertTrue(
          s.desktop()
              .query(query("practice", "library").put("wordId", ALPHA).put("mode", "copy"))
              .path("draft")
              .path("answered")
              .isNull());
      assertEquals(
          2,
          s.lmcp()
              .database
              .read(db -> Sql.first(db, "SELECT COUNT(*) AS n FROM desktop_practice_facts"))
              .path("n")
              .asInt());
    }
  }

  @Test
  void modeRestartRefreshesCapturedWordsAndBookMembersWhileOtherModeKeepsItsQueue()
      throws Exception {
    var f = fixture();
    try (var service = new LeximeetService(directory, CLOCK)) {
      f.mount(service, f.index(directory));
      f.goal(service, "dictionary");
      assertEquals(
          2,
          service
              .desktop()
              .query(query("practiceWords", "library").put("mode", "listening"))
              .path("total")
              .asInt());
      service
          .desktop()
          .command(
              Json.MAPPER
                  .createObjectNode()
                  .put("action", "practiceSave")
                  .put("scope", "library")
                  .put("mode", "listening")
                  .put("cursor", 1));
      service.desktop().query(query("practice", "library").put("mode", "copy"));
      service
          .desktop()
          .command(
              Json.MAPPER
                  .createObjectNode()
                  .put("action", "capture")
                  .put("word", "gamma")
                  .put("context", "gamma appears here."));
      service.desktop().query(query("practice", "library").put("mode", "copy").put("reset", true));
      assertEquals(
          3,
          service
              .desktop()
              .query(query("practiceWords", "library").put("mode", "copy"))
              .path("total")
              .asInt());
      assertEquals(
          2,
          service
              .desktop()
              .query(query("practiceWords", "library").put("mode", "listening"))
              .path("total")
              .asInt());
      assertEquals(
          1,
          service
              .desktop()
              .query(query("practice", "library").put("mode", "listening"))
              .path("cursor")
              .asInt());
      String scope = "book:" + PersonalLibrary.READING_ID;
      var add = Json.MAPPER.createObjectNode().put("action", "bulkAddToBooks");
      add.putArray("wordIds").add(ALPHA);
      add.putArray("bookIds").add(PersonalLibrary.READING_ID);
      service.desktop().command(add);
      assertEquals(
          1,
          service
              .desktop()
              .query(query("practiceWords", scope).put("mode", "listening"))
              .path("total")
              .asInt());
      service.desktop().query(query("practice", scope).put("mode", "copy"));
      add.putArray("wordIds").add(BETA);
      service.desktop().command(add);
      service.desktop().query(query("practice", scope).put("mode", "copy").put("reset", true));
      assertEquals(
          2,
          service
              .desktop()
              .query(query("practiceWords", scope).put("mode", "copy"))
              .path("total")
              .asInt());
      assertEquals(
          1,
          service
              .desktop()
              .query(query("practiceWords", scope).put("mode", "listening"))
              .path("total")
              .asInt());
    }
  }
}
