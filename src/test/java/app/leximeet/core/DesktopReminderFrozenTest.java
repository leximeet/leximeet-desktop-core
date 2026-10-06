package app.leximeet.core;

import static app.leximeet.core.DesktopWorkspaceTest.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// 通知直接展示实际冻结题；公共 sentence 例句和出题失败均在真实 SQLite 验证。
class DesktopReminderFrozenTest {
  @TempDir Path directory;

  DesktopWorkspaceTest fixture() {
    var f = new DesktopWorkspaceTest();
    f.directory = directory;
    return f;
  }

  ObjectNode request(String action) {
    return Json.MAPPER.createObjectNode().put("action", action);
  }

  void clozeOnly(LeximeetService s) throws Exception {
    var preferences =
        request("saveReminderPreferences")
            .put("expectedRevision", s.desktop().state().path("profile").path("revision").asInt());
    preferences.putArray("reminderModes").add("cloze");
    s.desktop().command(preferences);
  }

  JsonNode issue(LeximeetService s) throws Exception {
    var input = request("issueReminderQuestion");
    input.putArray("supportedModes").add("cloze");
    return s.desktop().command(input);
  }

  @Test
  void dictionarySentenceExampleProvidesFrozenNotificationAndScoresOnce() throws Exception {
    var f = fixture();
    var index = f.index(directory);
    try (var s = new LeximeetService(directory, CLOCK)) {
      f.mount(s, index);
      f.goal(s, "dictionary");
      clozeOnly(s);
      var notice = issue(s);
      assertEquals("cloze", notice.path("mode").asText());
      assertEquals("_____ appears here.", notice.path("context").asText());
      var frozen =
          s.desktop()
              .query(
                  Json.MAPPER
                      .createObjectNode()
                      .put("kind", "practiceQuestion")
                      .put("wordId", notice.path("wordId").asText())
                      .put("mode", "cloze")
                      .put("attemptId", notice.path("id").asText()));
      for (String field : new String[] {"word", "meaning", "context", "options", "questionId"})
        assertEquals(frozen.path(field), notice.path(field), field);
      assertEquals(0, s.snapshot().path("encounters").size());
      var answer =
          request("answerReminderQuestion")
              .put("questionId", notice.path("id").asText())
              .put("answer", notice.path("word").asText());
      assertTrue(s.desktop().command(answer).path("correct").asBoolean());
      assertEquals(
          12,
          s.desktop()
              .query(
                  Json.MAPPER
                      .createObjectNode()
                      .put("kind", "detail")
                      .put("wordId", notice.path("wordId").asText()))
              .path("familiarity")
              .path("score")
              .asInt());
      assertTrue(s.desktop().command(answer).path("duplicate").asBoolean());
      assertEquals(1, s.desktop().state().path("insights").path("practiceAnswers").asInt());
    }
  }

  @Test
  void unavailableQuestionTriesNextWordWithoutKeepingIncompleteSession() throws Exception {
    var f = fixture();
    var index = f.index(directory);
    try (var db = DriverManager.getConnection("jdbc:sqlite:" + index)) {
      Sql.execute(
          db,
          "UPDATE entries SET payload=json_set(payload,'$.senses[0].examples',json('[]')) WHERE"
              + " id=?",
          ALPHA);
    }
    try (var s = new LeximeetService(directory, CLOCK)) {
      f.mount(s, index);
      f.goal(s, "dictionary");
      clozeOnly(s);
      assertEquals("beta", issue(s).path("word").asText());
      assertEquals(
          1,
          s.lmcp()
              .database
              .read(db -> Sql.first(db, "SELECT COUNT(*) AS n FROM desktop_practice_sessions"))
              .path("n")
              .asInt());
      assertEquals(
          1,
          s.lmcp()
              .database
              .read(db -> Sql.first(db, "SELECT COUNT(*) AS n FROM desktop_questions"))
              .path("n")
              .asInt());
    }
  }

  @Test
  void storageFailureIsNotHiddenAsUnavailableAndRollsBackEntireIssue() throws Exception {
    var f = fixture();
    var index = f.index(directory);
    try (var s = new LeximeetService(directory, CLOCK)) {
      f.mount(s, index);
      f.goal(s, "dictionary");
      clozeOnly(s);
      s.lmcp()
          .database
          .transaction(
              db -> {
                Sql.execute(
                    db,
                    "CREATE TRIGGER reject_question BEFORE INSERT ON desktop_questions BEGIN SELECT"
                        + " RAISE(ABORT,'test storage failure'); END");
                return null;
              });
      assertThrows(SQLException.class, () -> issue(s));
      assertEquals(
          0,
          s.lmcp()
              .database
              .read(db -> Sql.first(db, "SELECT COUNT(*) AS n FROM desktop_practice_sessions"))
              .path("n")
              .asInt());
      assertEquals(
          0,
          s.lmcp()
              .database
              .read(db -> Sql.first(db, "SELECT COUNT(*) AS n FROM desktop_reminder_questions"))
              .path("n")
              .asInt());
    }
  }
}
