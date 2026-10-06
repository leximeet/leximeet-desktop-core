package app.leximeet.core;

import static app.leximeet.core.DesktopWorkspaceTest.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// 真实 SQLite 的计划、熟悉度和增量迁移，隔离目录内固定时钟，无宿主资料依赖。
class DesktopLearningTest {
  @TempDir Path directory;

  DesktopWorkspaceTest fixture() {
    var fixture = new DesktopWorkspaceTest();
    fixture.directory = directory;
    return fixture;
  }

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

  @Test
  void goalAndPlanAreIndependentAndReminderRangeCanBeDisabled() throws Exception {
    var fixture = fixture();
    var index = fixture.index(directory);
    try (var service = new LeximeetService(directory, CLOCK)) {
      fixture.mount(service, index);
      var plan = service.desktop().state().path("profile");
      assertFalse(plan.path("planEnabled").asBoolean());
      assertTrue(plan.path("reminderEnabled").asBoolean());
      assertEquals("08:00", plan.path("studyStart").asText());
      assertEquals("20:00", plan.path("studyEnd").asText());
      service
          .desktop()
          .command(command("setGoal").put("goal", "dictionary").put("expectedRevision", 1));
      assertTrue(service.desktop().state().path("queue").path("planned").isEmpty());
      service
          .desktop()
          .command(
              command("savePlan")
                  .put("planEnabled", true)
                  .put("reminderEnabled", false)
                  .put("studyStart", "09:00")
                  .put("studyEnd", "19:00")
                  .put("expectedRevision", 2));
      assertEquals(2, service.desktop().state().path("queue").path("planned").size());
      assertFalse(service.desktop().state().path("profile").path("reminderEnabled").asBoolean());
      var invalid =
          command("savePlan")
              .put("studyStart", "20:00")
              .put("studyEnd", "08:00")
              .put("expectedRevision", 3);
      assertThrows(ApiException.class, () -> service.desktop().command(invalid));
      assertEquals(3, service.desktop().state().path("profile").path("revision").asInt());
      service
          .desktop()
          .command(command("savePlan").put("planEnabled", false).put("expectedRevision", 3));
      assertTrue(service.desktop().state().path("queue").path("planned").isEmpty());
      assertEquals("dictionary", service.desktop().state().path("profile").path("goal").asText());
    }
  }

  @Test
  void listSignalsAreCountedOnceAndNeverDirectlyMarkMastery() throws Exception {
    var fixture = fixture();
    var index = fixture.index(directory);
    try (var service = new LeximeetService(directory, CLOCK)) {
      fixture.mount(service, index);
      var signal =
          command("practiceRecord")
              .put("wordId", ALPHA)
              .put("mode", "word-list")
              .put("signal", "familiar")
              .put("submissionId", UUID.randomUUID().toString());
      service.desktop().command(signal);
      service.desktop().command(signal);
      var detail =
          service
              .desktop()
              .query(command("unused").removeAll().put("kind", "detail").put("wordId", ALPHA));
      assertEquals(1, detail.path("familiarity").path("familiar").asInt());
      assertEquals("learning", detail.path("status").asText());
      var conflict = signal.deepCopy().put("signal", "unfamiliar");
      assertThrows(ApiException.class, () -> service.desktop().command(conflict));
      service.desktop().command(conflict.put("submissionId", UUID.randomUUID().toString()));
      detail =
          service
              .desktop()
              .query(Json.MAPPER.createObjectNode().put("kind", "detail").put("wordId", ALPHA));
      assertEquals(1, detail.path("familiarity").path("unfamiliar").asInt());
      assertTrue(detail.path("familiarity").path("score").asInt() < 50);
      assertEquals(0, service.desktop().state().path("insights").path("practiceAnswers").asInt());
    }
  }

  @Test
  void answersAreCheckedByCoreAndAssistedCopyDoesNotAdvanceMemory() throws Exception {
    var fixture = fixture();
    var index = fixture.index(directory);
    try (var service = new LeximeetService(directory, CLOCK)) {
      fixture.mount(service, index);
      service.desktop().command(record("copy", "alpha"));
      var assisted =
          record("recall", "alpha")
              .put("assisted", false)
              .put("attemptId", UUID.randomUUID().toString());
      var reveal =
          assisted
              .deepCopy()
              .put("signal", "reveal")
              .put("submissionId", UUID.randomUUID().toString());
      reveal.remove("answer");
      service.desktop().command(reveal);
      service.desktop().command(assisted);
      assertEquals(0, service.desktop().state().path("insights").path("learned").asInt());
      service.desktop().command(record("recall", "wrong"));
      assertEquals(0, service.desktop().state().path("insights").path("learned").asInt());
      service.desktop().command(record("recall", " ALPHA "));
      assertEquals(0, service.desktop().state().path("insights").path("learned").asInt());
      assertThrows(
          ApiException.class,
          () -> service.desktop().command(record("recall", "alpha").put("correct", true)));
      assertEquals(2, service.desktop().state().path("insights").path("practiceAnswers").asInt());
      assertEquals(1, service.desktop().state().path("insights").path("practiceCorrect").asInt());
    }
  }

  @Test
  void proficiencyReachesThirtyAndStaysRestingAfterOneUnknown() throws Exception {
    var fixture = fixture();
    var index = fixture.index(directory);
    for (int day = 0; day < 5; day++) {
      try (var service =
          new LeximeetService(directory, Clock.offset(CLOCK, Duration.ofDays(day)))) {
        fixture.mount(service, index);
        for (int i = 0; i < 4; i++) service.desktop().command(record("recall", "alpha"));
        var query = Json.MAPPER.createObjectNode().put("kind", "detail").put("wordId", ALPHA);
        assertEquals(
            Math.min(30, 10 + 8 * (day + 1)),
            service.desktop().query(query).path("familiarity").path("score").asInt());
        assertEquals(
            day >= 2 ? "mastered" : day == 1 ? "review" : "learning",
            service.desktop().query(query).path("status").asText());
        if (day == 4) {
          service
              .desktop()
              .command(
                  command("practiceRecord")
                      .put("wordId", ALPHA)
                      .put("mode", "word-list")
                      .put("signal", "unfamiliar")
                      .put("submissionId", UUID.randomUUID().toString()));
          assertEquals(
              29, service.desktop().query(query).path("familiarity").path("score").asInt());
          assertEquals("mastered", service.desktop().query(query).path("status").asText());
        }
      }
    }
  }

  @Test
  void localMidnightRatherThanUtcMidnightDefinesIndependentStudyDays() throws Exception {
    var fixture = fixture();
    var index = fixture.index(directory);
    var evening =
        Clock.fixed(
            java.time.Instant.parse("2026-10-01T15:59:00Z"), java.time.ZoneId.of("Asia/Shanghai"));
    try (var service = new LeximeetService(directory, evening)) {
      fixture.mount(service, index);
      for (int i = 0; i < 3; i++) service.desktop().command(record("recall", "alpha"));
    }
    try (var service =
        new LeximeetService(directory, Clock.offset(evening, Duration.ofMinutes(2)))) {
      fixture.mount(service, index);
      service.desktop().command(record("listening", "alpha"));
      assertEquals(
          17,
          service
              .desktop()
              .query(Json.MAPPER.createObjectNode().put("kind", "detail").put("wordId", ALPHA))
              .path("familiarity")
              .path("score")
              .asInt());
    }
  }
}
