package app.leximeet.core;

import static app.leximeet.core.DesktopWorkspaceTest.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// 真 SQLite 验证目标引用、回收墓碑和批量操作，不只比较 UI 文案。
class DesktopLibraryManagementTest {
  @TempDir Path directory;

  DesktopWorkspaceTest fixture() {
    var fixture = new DesktopWorkspaceTest();
    fixture.directory = directory;
    return fixture;
  }

  ObjectNode command(String action, String... ids) {
    var input = Json.MAPPER.createObjectNode().put("action", action);
    var selected = input.putArray("wordIds");
    for (String id : ids) selected.add(id);
    return input;
  }

  ObjectNode page(String scope) {
    return Json.MAPPER.createObjectNode().put("kind", "words").put("scope", scope);
  }

  JsonNode detail(LeximeetService service, String id) throws Exception {
    return service
        .desktop()
        .query(Json.MAPPER.createObjectNode().put("kind", "detail").put("wordId", id));
  }

  @Test
  void virtualGoalTrashExcludesLibraryQueueForecastAndPracticeWithoutDeletingDictionary()
      throws Exception {
    var fixture = fixture();
    try (var service = new LeximeetService(directory, CLOCK)) {
      fixture.mount(service, fixture.index(directory));
      assertEquals(0, service.desktop().query(page("library")).path("total").asInt());
      fixture.goal(service, "exam:test");
      assertEquals(0, service.snapshot().path("words").size());
      service
          .desktop()
          .command(Json.MAPPER.createObjectNode().put("action", "trash").put("wordId", BETA));
      assertEquals(1, service.desktop().query(page("library")).path("total").asInt());
      assertEquals(2, service.desktop().query(page("dictionary")).path("total").asInt());
      assertEquals(1, service.desktop().state().path("queue").path("tasks").size());
      assertEquals(
          ALPHA, service.desktop().state().path("queue").path("tasks").path(0).path("id").asText());
      assertEquals(
          1,
          service
              .desktop()
              .query(
                  Json.MAPPER
                      .createObjectNode()
                      .put("kind", "practiceWords")
                      .put("scope", "library"))
              .path("total")
              .asInt());
      var forecast =
          service
              .desktop()
              .query(
                  Json.MAPPER
                      .createObjectNode()
                      .put("kind", "planningPreview")
                      .put("goal", "exam:test"));
      assertEquals(1, forecast.path("total").asInt());
      assertEquals(
          ALPHA, forecast.path("firstDays").path(0).path("words").path(0).path("id").asText());
      assertFalse(detail(service, BETA).path("manualActive").asBoolean());
      service
          .desktop()
          .command(Json.MAPPER.createObjectNode().put("action", "restore").put("wordId", BETA));
      assertFalse(detail(service, BETA).path("manualActive").asBoolean());
      assertEquals(2, service.desktop().query(page("library")).path("total").asInt());
      fixture.goal(service, "");
      assertEquals(0, service.desktop().query(page("library")).path("total").asInt());
    }
  }

  @Test
  void bulkTrashRestoreAndBookAppendAreBoundedAndAtomic() throws Exception {
    var fixture = fixture();
    try (var service = new LeximeetService(directory, CLOCK)) {
      fixture.mount(service, fixture.index(directory));
      fixture.goal(service, "exam:test");
      assertThrows(
          ApiException.class,
          () ->
              service.desktop().command(command("bulkTrash", ALPHA, UUID.randomUUID().toString())));
      assertEquals(0, service.snapshot().path("words").size());
      assertThrows(
          ApiException.class, () -> service.desktop().command(command("bulkTrash", ALPHA, ALPHA)));
      var tooMany = command("bulkTrash");
      for (int i = 0; i < 101; i++) tooMany.withArray("wordIds").add(UUID.randomUUID().toString());
      assertThrows(ApiException.class, () -> service.desktop().command(tooMany));
      var add = command("bulkAddToBooks", ALPHA, BETA);
      add.putArray("bookIds").add(PersonalLibrary.READING_ID).add(UUID.randomUUID().toString());
      assertThrows(ApiException.class, () -> service.desktop().command(add));
      assertEquals(0, service.snapshot().path("words").size());
      add.putArray("bookIds").add(PersonalLibrary.READING_ID);
      service.desktop().command(add);
      add.putArray("bookIds").add(PersonalLibrary.DAILY_ID);
      service.desktop().command(add);
      int revision = detail(service, ALPHA).path("revision").asInt();
      service.desktop().command(add);
      assertEquals(revision, detail(service, ALPHA).path("revision").asInt());
      assertEquals(2, detail(service, ALPHA).path("books").size());
      service.desktop().command(command("bulkTrash", ALPHA, BETA));
      assertEquals(0, service.desktop().query(page("library")).path("total").asInt());
      assertEquals(2, service.desktop().query(page("trash")).path("total").asInt());
      assertThrows(ApiException.class, () -> service.desktop().command(add));
      service.desktop().command(command("bulkRestore", ALPHA, BETA));
      assertEquals(2, service.desktop().query(page("library")).path("total").asInt());
      assertTrue(detail(service, ALPHA).path("manualActive").asBoolean());
      assertEquals(2, detail(service, ALPHA).path("books").size());
    }
  }

  @Test
  void realPosFilterCountsBeforePagingAndSortingUsesRecentPersonalUpdate() throws Exception {
    var fixture = fixture();
    Path index = fixture.index(directory);
    try (var db = DriverManager.getConnection("jdbc:sqlite:" + index);
        var statement = db.createStatement()) {
      statement.execute(
          "CREATE TABLE entry_parts_of_speech(entry_id TEXT,pos TEXT,PRIMARY KEY(entry_id,pos))");
      Sql.execute(db, "INSERT INTO entry_parts_of_speech VALUES(?,'noun')", ALPHA);
      Sql.execute(db, "INSERT INTO entry_parts_of_speech VALUES(?,'verb')", BETA);
      Sql.execute(db, "INSERT INTO entry_parts_of_speech VALUES(?,'noun')", BETA);
    }
    try (var service = new LeximeetService(directory, CLOCK)) {
      fixture.mount(service, index);
      fixture.goal(service, "exam:test");
      var nouns =
          service.desktop().query(page("library").put("partOfSpeech", "noun").put("limit", 1));
      assertEquals(2, nouns.path("total").asInt());
      assertEquals(1, nouns.path("words").size());
      var verbs = service.desktop().query(page("library").put("partOfSpeech", "verb"));
      assertEquals(1, verbs.path("total").asInt());
      assertEquals(BETA, verbs.path("words").path(0).path("id").asText());
      service
          .desktop()
          .command(
              Json.MAPPER
                  .createObjectNode()
                  .put("action", "saveNote")
                  .put("wordId", ALPHA)
                  .put("note", "my note"));
      assertEquals(
          ALPHA,
          service
              .desktop()
              .query(page("library").put("sort", "recent"))
              .path("words")
              .path(0)
              .path("id")
              .asText());
      assertEquals(
          BETA,
          service
              .desktop()
              .query(page("library").put("sort", "source"))
              .path("words")
              .path(0)
              .path("id")
              .asText());
      assertThrows(
          ApiException.class,
          () -> service.desktop().query(page("library").put("partOfSpeech", "invented")));
    }
  }

  @Test
  void staleFrozenQuestionCannotScoreTrashedWord() throws Exception {
    var fixture = fixture();
    try (var service = new LeximeetService(directory, CLOCK)) {
      fixture.mount(service, fixture.index(directory));
      fixture.goal(service, "exam:test");
      var question =
          service
              .desktop()
              .query(
                  Json.MAPPER
                      .createObjectNode()
                      .put("kind", "practiceQuestion")
                      .put("scope", "library")
                      .put("mode", "copy")
                      .put("wordId", ALPHA)
                      .put("attemptId", UUID.randomUUID().toString()));
      service.desktop().command(command("bulkTrash", ALPHA));
      var feedback =
          Json.MAPPER
              .createObjectNode()
              .put("action", "practiceRecord")
              .put("scope", "library")
              .put("mode", "copy")
              .put("wordId", ALPHA)
              .put("attemptId", question.path("attemptId").asText())
              .put("submissionId", UUID.randomUUID().toString())
              .put("answer", "alpha");
      assertThrows(ApiException.class, () -> service.desktop().command(feedback));
      assertEquals(0, service.desktop().state().path("insights").path("practice").asInt());
    }
  }

  @Test
  void fullyTrashedTargetKeepsEmptyCheckpointStableAndDictionaryPracticeExcludesTrash()
      throws Exception {
    var fixture = fixture();
    try (var service = new LeximeetService(directory, CLOCK)) {
      fixture.mount(service, fixture.index(directory));
      fixture.goal(service, "exam:test");
      service.desktop().command(command("bulkTrash", ALPHA, BETA));
      var request =
          Json.MAPPER
              .createObjectNode()
              .put("kind", "practice")
              .put("scope", "library")
              .put("mode", "copy");
      var first = service.desktop().query(request);
      assertEquals(first.path("sessionId"), service.desktop().query(request).path("sessionId"));
      assertEquals(
          0,
          service
              .desktop()
              .query(
                  Json.MAPPER
                      .createObjectNode()
                      .put("kind", "practiceWords")
                      .put("scope", "dictionary"))
              .path("total")
              .asInt());
      assertEquals(2, service.desktop().query(page("dictionary")).path("total").asInt());
    }
  }
}
