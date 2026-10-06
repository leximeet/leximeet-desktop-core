package app.leximeet.core;

import static app.leximeet.core.DesktopWorkspaceTest.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// 同一道题、重试、提示与提前复习的真实事务边界。
class UnifiedLearningTest {
  @TempDir Path directory;

  ObjectNode event(String mode, String attempt, String signal) {
    var node =
        Json.MAPPER
            .createObjectNode()
            .put("action", "practiceRecord")
            .put("wordId", ALPHA)
            .put("mode", mode)
            .put("attemptId", attempt)
            .put("submissionId", UUID.randomUUID().toString());
    if (!signal.isEmpty()) node.put("signal", signal);
    return node;
  }

  ObjectNode query() {
    return Json.MAPPER.createObjectNode().put("kind", "detail").put("wordId", ALPHA);
  }

  @Test
  void previousGuideRevisitsWithoutUndoingFactsOrCelebratingAgain() throws Exception {
    try (var database = new Database(directory)) {
      var guide = new DesktopGuide(CLOCK);
      database.transaction(
          db -> {
            guide.change(db, "guideStart", "");
            guide.change(db, "guideNext", "sidebar");
            String advanced = guide.state(db).path("advancedAt").asText();
            guide.change(db, "guidePrevious", "");
            assertEquals(0, guide.state(db).path("cursor").asInt());
            assertEquals(1, guide.state(db).path("completed").size());
            guide.change(db, "guideNext", "sidebar");
            assertEquals(1, guide.state(db).path("cursor").asInt());
            assertEquals(1, guide.state(db).path("completed").size());
            assertEquals(advanced, guide.state(db).path("advancedAt").asText());
            return null;
          });
    }
  }

  @Test
  void revealPenalizesOnceAndCannotBeTurnedIntoIndependentSuccess() throws Exception {
    var fixture = new DesktopWorkspaceTest();
    fixture.directory = directory;
    var index = fixture.index(directory);
    try (var service = new LeximeetService(directory, CLOCK)) {
      fixture.mount(service, index);
      service.desktop().command(event("recall", "seed", "").put("answer", "alpha"));
      assertEquals(12, service.desktop().query(query()).path("familiarity").path("score").asInt());
      var reveal = event("word-list", "list-one", "reveal");
      service.desktop().command(reveal);
      service.desktop().command(reveal);
      service.desktop().command(event("word-list", "list-one", "unfamiliar"));
      service.desktop().command(event("word-list", "list-one", "familiar"));
      assertEquals(11, service.desktop().query(query()).path("familiarity").path("score").asInt());
      for (int i = 0; i < 14; i++)
        service.desktop().command(event("word-list", "bad-" + i, "unfamiliar"));
      assertEquals(0, service.desktop().query(query()).path("familiarity").path("score").asInt());
      assertThrows(
          ApiException.class,
          () -> service.desktop().command(event("copy", "list-one", "").put("answer", "alpha")));
    }
  }

  @Test
  void choiceUsesRealOptionsAndCoreValidatesSelectedIdentity() throws Exception {
    var fixture = new DesktopWorkspaceTest();
    fixture.directory = directory;
    var index = fixture.index(directory);
    try (var service = new LeximeetService(directory, CLOCK)) {
      fixture.mount(service, index);
      var question =
          service
              .desktop()
              .query(
                  Json.MAPPER
                      .createObjectNode()
                      .put("kind", "practiceQuestion")
                      .put("mode", "meaning-choice")
                      .put("wordId", ALPHA)
                      .put("attemptId", "choice-one"));
      assertEquals(2, question.path("options").size());
      assertThrows(
          ApiException.class,
          () ->
              service
                  .desktop()
                  .command(event("meaning-choice", "choice-one", "").put("choiceId", "invented")));
      String choice = "";
      for (var option : question.path("options"))
        if (option.path("text").asText().equals("中文0")) choice = option.path("id").asText();
      var answer = event("meaning-choice", "choice-one", "").put("choiceId", choice);
      service.desktop().command(answer);
      service.desktop().command(answer);
      assertEquals(11, service.desktop().query(query()).path("familiarity").path("score").asInt());
      var due = service.desktop().query(query()).path("dueAt");
      service.desktop().command(event("recall", "practice-again", "").put("answer", "alpha"));
      assertEquals(due, service.desktop().query(query()).path("dueAt"));
      assertEquals(0, service.desktop().state().path("queue").path("newDone").asInt());
    }
  }

  @Test
  void wrongThenCorrectSameAttemptDoesNotEarnPointsAndCopyDoesNotSchedule() throws Exception {
    var fixture = new DesktopWorkspaceTest();
    fixture.directory = directory;
    var index = fixture.index(directory);
    try (var service = new LeximeetService(directory, CLOCK)) {
      fixture.mount(service, index);
      service.desktop().command(event("copy", "copy-one", "").put("answer", "alpha"));
      assertEquals(11, service.desktop().query(query()).path("familiarity").path("score").asInt());
      assertFalse(service.desktop().query(query()).has("dueAt"));
      service.desktop().command(event("recall", "recall-one", "").put("answer", "wrong"));
      service.desktop().command(event("recall", "recall-one", "").put("answer", "alpha"));
      assertEquals(10, service.desktop().query(query()).path("familiarity").path("score").asInt());
      assertEquals(0, service.desktop().state().path("queue").path("newDone").asInt());
    }
  }
}
