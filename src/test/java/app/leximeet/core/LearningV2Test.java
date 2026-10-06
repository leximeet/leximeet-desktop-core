package app.leximeet.core;

import static app.leximeet.core.DesktopWorkspaceTest.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// 使用真实数据库和可控时钟验证阶段、FSRS、每日额度与通知回调的完整事务边界。
class LearningV2Test {
  @TempDir Path directory;
  final DesktopWorkspaceTest fixture = new DesktopWorkspaceTest();

  ObjectNode command(String action) {
    return Json.MAPPER.createObjectNode().put("action", action);
  }

  ObjectNode record(String mode, String answer) {
    return command("practiceRecord")
        .put("wordId", ALPHA)
        .put("mode", mode)
        .put("answer", answer)
        .put("submissionId", UUID.randomUUID().toString());
  }

  JsonNode learning(LeximeetService s) throws Exception {
    return s.desktop()
        .query(json("{\"kind\":\"detail\",\"wordId\":\"" + ALPHA + "\"}"))
        .path("familiarity");
  }

  void copy(LeximeetService s, int n) throws Exception {
    for (int i = 0; i < n; i++) s.desktop().command(record("copy", "alpha"));
  }

  @Test
  void sharedCrossClientVectorsMatchScoreStageAndTime() throws Exception {
    try (var input = getClass().getResourceAsStream("/learning-rule-v2-vectors.json")) {
      var vectors = Json.MAPPER.readTree(input);
      assertEquals(LearningPolicy.VERSION, vectors.path("ruleVersion").asText());
      for (var vector : vectors.path("cases")) {
        var result =
            LearningPolicy.project(
                vector.path("events"), Instant.parse(vector.path("asOf").asText()));
        var fields = vector.path("expected").fields();
        while (fields.hasNext()) {
          var field = fields.next();
          assertEquals(
              field.getValue(),
              result.path(field.getKey()),
              vector.path("name").asText() + " / " + field.getKey());
        }
      }
    }
  }

  @Test
  void copyGraduatesWithoutInventingMemoryAndOnlyThenEntersNextDayQueue() throws Exception {
    var index = fixture.index(directory);
    try (var s = new LeximeetService(directory, CLOCK)) {
      fixture.mount(s, index);
      fixture.goal(s, "dictionary");
      assertEquals(10, learning(s).path("score").asInt());
      copy(s, 9);
      assertEquals("learning", learning(s).path("status").asText());
      assertEquals(0, s.desktop().state().path("queue").path("newDone").asInt());
      copy(s, 1);
      assertEquals("review", learning(s).path("status").asText());
      assertEquals(1, s.desktop().state().path("queue").path("newDone").asInt());
      assertEquals(
          1, s.desktop().state().path("insights").path("days").get(0).path("newFeedback").asInt());
      assertEquals(0, s.desktop().state().path("queue").path("reviews").size());
      assertFalse(
          s.desktop()
              .query(json("{\"kind\":\"detail\",\"wordId\":\"" + ALPHA + "\"}"))
              .has("dueAt"));
    }
    try (var s = new LeximeetService(directory, Clock.offset(CLOCK, Duration.ofDays(1)))) {
      fixture.mount(s, index);
      assertEquals(
          ALPHA, s.desktop().state().path("queue").path("reviews").get(0).path("id").asText());
      s.desktop().command(record("recall", "alpha"));
      assertEquals(22, learning(s).path("score").asInt());
      assertEquals(1, s.desktop().state().path("queue").path("reviewDone").asInt());
      s.desktop().command(record("recall", "wrong"));
      assertEquals(21, learning(s).path("score").asInt());
      assertEquals(1, s.desktop().state().path("queue").path("reviewDone").asInt());
      assertEquals(
          1,
          s.desktop().state().path("insights").path("days").get(0).path("reviewFeedback").asInt());
    }
    try (var s =
        new LeximeetService(directory, Clock.offset(CLOCK, Duration.ofDays(1).plusMinutes(2)))) {
      fixture.mount(s, index);
      assertEquals(1, s.desktop().state().path("queue").path("reviews").size(), "成功后答错仍可短期巩固");
    }
  }

  @Test
  void masteryDecayIsLazyIdempotentFilteredAndUndoRebuildsCycle() throws Exception {
    var index = fixture.index(directory);
    try (var s = new LeximeetService(directory, CLOCK)) {
      fixture.mount(s, index);
      fixture.goal(s, "dictionary");
      copy(s, 20);
      assertEquals("mastered", learning(s).path("status").asText());
    }
    for (int day : new int[] {3, 5, 7, 23, 50})
      try (var s = new LeximeetService(directory, Clock.offset(CLOCK, Duration.ofDays(day)))) {
        fixture.mount(s, index);
        int expected = day < 5 ? 30 : Math.max(20, 30 - (day - 3) / 2);
        for (int i = 0; i < 2; i++) {
          assertEquals(expected, learning(s).path("score").asInt());
          assertEquals(
              expected > 20 ? 1 : 0,
              s.desktop()
                  .query(json("{\"scope\":\"dictionary\",\"learningStatus\":\"mastered\"}"))
                  .path("total")
                  .asInt());
        }
      }
    try (var s = new LeximeetService(directory, Clock.offset(CLOCK, Duration.ofDays(51)))) {
      fixture.mount(s, index);
      copy(s, 9);
      var finalPoint = record("copy", "alpha");
      s.desktop().command(finalPoint);
      assertEquals("mastered", learning(s).path("status").asText());
      s.desktop()
          .command(
              command("undoPractice")
                  .put("submissionId", finalPoint.path("submissionId").asText()));
      assertEquals(29, learning(s).path("score").asInt());
      assertEquals("review", learning(s).path("status").asText());
      assertTrue(learning(s).path("masteryCycleAt").isNull());
      assertEquals(29, s.desktop().state().path("insights").path("practice").asInt());
    }
  }

  @Test
  void notificationAnswersUseFrozenQuestionsAndOneScoringTransaction() throws Exception {
    var index = fixture.index(directory);
    try (var s = new LeximeetService(directory, CLOCK)) {
      fixture.mount(s, index);
      fixture.goal(s, "dictionary");
      var issue = command("issueReminderQuestion");
      issue.putArray("supportedModes").add("meaning-choice");
      var q = s.desktop().command(issue);
      assertEquals("meaning-choice", q.path("mode").asText());
      assertEquals(10, learning(s).path("score").asInt());
      String correctChoice = "";
      for (var option : q.path("options"))
        if (option.path("text").asText().equals(q.path("meaning").asText()))
          correctChoice = option.path("id").asText();
      var answer =
          command("answerReminderQuestion")
              .put("questionId", q.path("id").asText())
              .put("choiceId", correctChoice);
      assertTrue(s.desktop().command(answer).path("correct").asBoolean());
      assertTrue(s.desktop().command(answer).path("duplicate").asBoolean());
      assertTrue(
          s.desktop()
              .command(
                  command("answerReminderQuestion")
                      .put("choiceId", answer.path("choiceId").asText())
                      .put("questionId", q.path("id").asText()))
              .path("duplicate")
              .asBoolean());
      assertEquals(11, learning(s).path("score").asInt());
      assertEquals(0, s.desktop().state().path("queue").path("newDone").asInt());
      assertThrows(
          ApiException.class, () -> s.desktop().command(answer.deepCopy().put("choiceId", BETA)));
    }
  }

  @Test
  void expiredNotificationDoesNotWriteScoreOrMemory() throws Exception {
    var index = fixture.index(directory);
    String questionId;
    try (var s = new LeximeetService(directory, CLOCK)) {
      fixture.mount(s, index);
      fixture.goal(s, "dictionary");
      var issue = command("issueReminderQuestion");
      issue.putArray("supportedModes").add("meaning-choice");
      questionId = s.desktop().command(issue).path("id").asText();
    }
    try (var s = new LeximeetService(directory, Clock.offset(CLOCK, Duration.ofMinutes(31)))) {
      fixture.mount(s, index);
      var answer =
          command("answerReminderQuestion").put("questionId", questionId).put("choiceId", ALPHA);
      assertThrows(ApiException.class, () -> s.desktop().command(answer));
      assertEquals(10, learning(s).path("score").asInt());
      assertEquals(0, s.desktop().state().path("insights").path("practice").asInt());
      assertEquals(
          0, s.desktop().query(json("{\"kind\":\"reminderQuestions\"}")).path("questions").size());
    }
  }

  @Test
  void clockRollbackCannotWriteEarlierFactsAndReadCacheRecovers() throws Exception {
    var index = fixture.index(directory);
    try (var s = new LeximeetService(directory, Clock.offset(CLOCK, Duration.ofDays(1)))) {
      fixture.mount(s, index);
      copy(s, 1);
    }
    try (var s = new LeximeetService(directory, CLOCK)) {
      fixture.mount(s, index);
      assertEquals(
          0,
          s.desktop()
              .query(json("{\"scope\":\"dictionary\",\"learningStatus\":\"learning\"}"))
              .path("total")
              .asInt());
      assertThrows(ApiException.class, () -> copy(s, 1));
    }
    try (var s = new LeximeetService(directory, Clock.offset(CLOCK, Duration.ofDays(1)))) {
      fixture.mount(s, index);
      assertEquals(
          1,
          s.desktop()
              .query(json("{\"scope\":\"dictionary\",\"learningStatus\":\"learning\"}"))
              .path("total")
              .asInt());
      assertEquals(11, learning(s).path("score").asInt());
    }
  }

  @Test
  void crossingTwentyAndDroppingToZeroNeverCreatesAnotherNewCompletion() throws Exception {
    var index = fixture.index(directory);
    try (var s = new LeximeetService(directory, CLOCK)) {
      fixture.mount(s, index);
      copy(s, 9);
      s.desktop().command(record("recall", "alpha"));
      assertEquals(21, learning(s).path("score").asInt());
      for (int i = 0; i < 25; i++) s.desktop().command(record("recall", "wrong"));
      assertEquals(0, learning(s).path("score").asInt());
      assertEquals("review", learning(s).path("status").asText());
      copy(s, 20);
      assertEquals(1, s.desktop().state().path("queue").path("newDone").asInt());
      assertEquals(0, s.desktop().state().path("queue").path("reviewDone").asInt());
    }
  }
}
