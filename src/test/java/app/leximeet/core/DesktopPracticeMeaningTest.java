package app.leximeet.core;

import static app.leximeet.core.DesktopWorkspaceTest.*;
import static app.leximeet.core.LmcpServiceTest.object;
import static app.leximeet.core.LmcpServiceTest.uuid;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// 发布词条的真实多义资料与冻结判题回归；不把词典第一义项假设为常用释义。
class DesktopPracticeMeaningTest {
  @TempDir Path directory;

  DesktopWorkspaceTest fixture() {
    var f = new DesktopWorkspaceTest();
    f.directory = directory;
    return f;
  }

  Path index() throws Exception {
    Path index = fixture().index(directory);
    JsonNode data;
    try (var in = getClass().getResourceAsStream("/practice-real-entries.json")) {
      data = Json.MAPPER.readTree(in);
    }
    try (var db = DriverManager.getConnection("jdbc:sqlite:" + index)) {
      int position = 2;
      for (JsonNode entry : data.path("entries")) {
        Sql.execute(
            db,
            "INSERT INTO entries VALUES(?,?,?,?,?,?)",
            entry.path("entry_id").asText(),
            entry.path("headword").asText(),
            entry.path("lookup_key").asText(),
            entry.path("headword_summary_zh").asText(),
            entry.toString(),
            position++);
      }
      Sql.execute(db, "UPDATE metadata SET payload=json_set(payload,'$.entryCount',4)");
    }
    return index;
  }

  void mount(LeximeetService s, Path index) throws Exception {
    s.desktop()
        .mount(
            object()
                .put("file", index.toString())
                .put("edition", "core-text")
                .put("version", "0.0.3")
                .put("manifestSha", SHA)
                .put("entryCount", 4));
  }

  ObjectNode request(String kind, String word, String mode, String attempt) {
    return object()
        .put("kind", kind)
        .put("scope", "dictionary")
        .put("wordId", word)
        .put("mode", mode)
        .put("attemptId", attempt);
  }

  ObjectNode answer(JsonNode question, String mode) {
    return object()
        .put("action", "practiceRecord")
        .put("scope", "dictionary")
        .put("wordId", question.path("wordId").asText())
        .put("mode", mode)
        .put("attemptId", question.path("attemptId").asText())
        .put("submissionId", uuid());
  }

  @Test
  void stateAndBehaviorUseTheSameOverviewForDisplayAndCorrectOptionAfterRestart() throws Exception {
    Path index = index();
    JsonNode stateQuestion;
    try (var s = new LeximeetService(directory, CLOCK)) {
      mount(s, index);
      for (String word : new String[] {"state", "behavior"}) {
        JsonNode detail = s.desktop().query(object().put("kind", "detail").put("wordId", word));
        var q =
            s.desktop()
                .query(
                    request(
                        "practiceQuestion", detail.path("id").asText(), "meaning-choice", uuid()));
        assertFalse(q.path("unavailable").asBoolean());
        assertEquals(detail.path("entry").path("headword_summary_zh"), q.path("meaning"));
        assertNotEquals(
            detail.path("entry").path("senses").path(0).path("short_gloss"), q.path("meaning"));
        String choice = "";
        for (JsonNode option : q.path("options"))
          if (option.path("text").equals(q.path("meaning"))) choice = option.path("id").asText();
        assertFalse(choice.isEmpty(), q.toPrettyString());
        var result = s.desktop().command(answer(q, "meaning-choice").put("choiceId", choice));
        assertTrue(result.path("practiceFeedback").path("correct").asBoolean());
        assertEquals(1, result.path("practiceFeedback").path("delta").asInt());
      }
      stateQuestion =
          s.desktop()
              .query(
                  request(
                      "practiceQuestion", "88602776-4385-5b4f-be98-93d27828d354", "copy", uuid()));
    }
    try (var s = new LeximeetService(directory, CLOCK)) {
      mount(s, index);
      var q =
          s.desktop()
              .query(
                  request(
                      "practiceQuestion",
                      stateQuestion.path("wordId").asText(),
                      "copy",
                      stateQuestion.path("attemptId").asText()));
      assertEquals(stateQuestion, q);
      assertTrue(
          s.desktop()
              .command(answer(q, "copy").put("answer", "state"))
              .path("practiceFeedback")
              .path("correct")
              .asBoolean());
    }
  }

  @Test
  void explicitCustomIdJudgesTheAvailableOptionWithoutChangingStableReference() throws Exception {
    var f = fixture();
    Path index = f.index(directory);
    try (var s = new LeximeetService(directory, CLOCK)) {
      // 正式收藏先建立 custom 身份；后续词典挂载可补充判题资料，但不能改写已发出的稳定身份。
      CoreTestData.collect(s, object().put("word", "alpha"));
      String local = s.snapshot().path("words").get(0).path("id").asText();
      assertNotEquals(ALPHA, local);
      f.mount(s, index);
      f.goal(s, "dictionary");
      var q =
          s.desktop()
              .query(
                  request("practiceQuestion", local, "meaning-choice", uuid())
                      .put("scope", "library"));
      String choice = "";
      for (JsonNode option : q.path("options"))
        if (option.path("text").equals(q.path("meaning"))) choice = option.path("id").asText();
      assertFalse(choice.isEmpty());
      assertTrue(
          s.desktop()
              .command(answer(q, "meaning-choice").put("scope", "library").put("choiceId", choice))
              .path("practiceFeedback")
              .path("correct")
              .asBoolean());
      assertEquals(
          11,
          s.desktop()
              .query(object().put("kind", "detail").put("wordId", local))
              .path("familiarity")
              .path("score")
              .asInt());
      assertEquals(1, s.snapshot().path("words").size());
      JsonNode fact =
          s.lmcp().database.read(db -> Sql.first(db, "SELECT word_id FROM desktop_practice_facts"));
      assertEquals(local, fact.path("word_id").asText());
      JsonNode event =
          s.lmcp()
              .database
              .read(
                  db ->
                      Sql.first(
                          db, "SELECT payload FROM desktop_entities WHERE entity_type='practice'"));
      JsonNode reference = Json.MAPPER.readTree(event.path("payload").asText()).path("word");
      assertEquals("custom", reference.path("kind").asText());
      assertEquals(local, reference.path("customId").asText());
    }
  }

  @Test
  void unavailableQuestionNeverLeavesPreviousWordOrAwardsAFreePoint() throws Exception {
    var f = fixture();
    Path index = f.index(directory);
    try (var db = DriverManager.getConnection("jdbc:sqlite:" + index)) {
      Sql.execute(
          db,
          "UPDATE entries SET payload=json_set(payload,'$.senses[0].examples',json('[]')) WHERE"
              + " id=?",
          BETA);
    }
    try (var s = new LeximeetService(directory, CLOCK)) {
      f.mount(s, index);
      var good = s.desktop().query(request("practiceQuestion", ALPHA, "cloze", uuid()));
      assertFalse(good.path("unavailable").asBoolean());
      var unavailable = s.desktop().query(request("practiceQuestion", BETA, "cloze", uuid()));
      assertTrue(unavailable.path("unavailable").asBoolean());
      assertEquals("beta", unavailable.path("word").asText());
      assertTrue(unavailable.path("questionId").isNull());
      assertFalse(unavailable.path("reason").asText().isEmpty());
      var error =
          assertThrows(
              ApiException.class,
              () -> s.desktop().command(answer(unavailable, "cloze").put("answer", "beta")));
      assertEquals("EXERCISE_UNAVAILABLE", error.code());
      assertEquals(0, s.desktop().state().path("insights").path("practiceAnswers").asInt());
    }
  }
}
