package app.leximeet.core;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// 规划必须整体生效，预览/取消不得写资料，跨目标不补回已用额度。
class DesktopPlanningTest {
  @TempDir Path directory;

  private ObjectNode request(JsonNode state, String goal) {
    return Json.MAPPER
        .createObjectNode()
        .put("action", "savePlanning")
        .put("goal", goal)
        .put("dailyNew", 20)
        .put("dailyReview", 0)
        .put("planEnabled", true)
        .put("reminderEnabled", true)
        .put("studyStart", "08:00")
        .put("studyEnd", "20:00")
        .put("expectedRevision", state.path("profile").path("revision").asInt());
  }

  @Test
  void previewIsReadOnlyAndPlanningIsAtomicWithStableIdentity() throws Exception {
    var fixture = new DesktopWorkspaceTest();
    Path index = fixture.index(directory);
    try (var service = new LeximeetService(directory, DesktopWorkspaceTest.CLOCK)) {
      fixture.mount(service, index);
      JsonNode before = service.desktop().state();
      var preview =
          service
              .desktop()
              .query(
                  DesktopWorkspaceTest.json(
                      "{\"kind\":\"planningPreview\",\"goal\":\"exam:test\",\"dailyNew\":1}"));
      assertEquals(2, preview.path("days").asInt());
      assertEquals(1, preview.path("firstDays").get(0).path("count").asInt());
      assertEquals(before, service.desktop().state());
      ObjectNode invalid = request(before, "exam:test").put("studyEnd", "07:00");
      assertThrows(ApiException.class, () -> service.desktop().command(invalid));
      assertEquals(before, service.desktop().state());
      var saved = service.desktop().command(request(before, "exam:test"));
      assertEquals("exam:test", saved.path("profile").path("goal").asText());
      assertEquals(0, saved.path("profile").path("dailyReview").asInt());
      String planId = saved.path("profile").path("planId").asText();
      assertFalse(planId.isBlank());
      assertThrows(
          ApiException.class, () -> service.desktop().command(request(before, "dictionary")));
      assertEquals(planId, service.desktop().state().path("profile").path("planId").asText());
      var adjusted = service.desktop().command(request(saved, "exam:test").put("dailyNew", 30));
      assertEquals(planId, adjusted.path("profile").path("planId").asText());
      assertEquals(
          saved.path("profile").path("startedOn"), adjusted.path("profile").path("startedOn"));
      var replaced = service.desktop().command(request(adjusted, "dictionary"));
      assertNotEquals(planId, replaced.path("profile").path("planId").asText());
      fixture.review(service, DesktopWorkspaceTest.ALPHA, 1, "good");
      for (int i = 0; i < 10; i++)
        service
            .desktop()
            .command(
                Json.MAPPER
                    .createObjectNode()
                    .put("action", "practiceRecord")
                    .put("wordId", DesktopWorkspaceTest.ALPHA)
                    .put("mode", "copy")
                    .put("answer", "alpha")
                    .put("attemptId", java.util.UUID.randomUUID().toString())
                    .put("submissionId", java.util.UUID.randomUUID().toString()));
      preview =
          service
              .desktop()
              .query(
                  DesktopWorkspaceTest.json(
                      "{\"kind\":\"planningPreview\",\"goal\":\"exam:test\",\"dailyNew\":1}"));
      assertEquals(1, preview.path("remaining").asInt());
      assertEquals(0, preview.path("firstDays").get(0).path("count").asInt());
      assertEquals(1, preview.path("firstDays").get(1).path("count").asInt());
    }
    try (var reopened = new LeximeetService(directory, DesktopWorkspaceTest.CLOCK)) {
      fixture.mount(reopened, index);
      assertEquals("dictionary", reopened.desktop().state().path("profile").path("goal").asText());
      assertEquals(0, reopened.desktop().state().path("profile").path("dailyReview").asInt());
    }
  }

  @Test
  void predictionUsesCurrentQueueAndShowsRealFutureWordsWithoutMutatingFacts() throws Exception {
    var fixture = new DesktopWorkspaceTest();
    Path index = fixture.index(directory);
    try (var service = new LeximeetService(directory, DesktopWorkspaceTest.CLOCK)) {
      fixture.mount(service, index);
      var state = service.desktop().state();
      service.desktop().command(request(state, "exam:test").put("dailyNew", 1));
      service
          .desktop()
          .command(
              Json.MAPPER
                  .createObjectNode()
                  .put("action", "capture")
                  .put("word", "gamma")
                  .put("context", "gamma appears here."));
      JsonNode before = service.desktop().state();
      var preview =
          service
              .desktop()
              .query(
                  Json.MAPPER
                      .createObjectNode()
                      .put("kind", "planningPreview")
                      .put("goal", "exam:test")
                      .put("dailyNew", 1)
                      .put("daysAhead", 7));
      assertEquals(2, preview.path("remaining").asInt());
      assertEquals(3, preview.path("days").asInt());
      assertEquals(0, preview.path("firstDays").path(0).path("count").asInt());
      assertTrue(preview.path("firstDays").path(0).path("words").isEmpty());
      assertEquals(
          DesktopWorkspaceTest.BETA,
          preview.path("firstDays").path(1).path("words").path(0).path("id").asText());
      assertEquals(
          DesktopWorkspaceTest.ALPHA,
          preview.path("firstDays").path(2).path("words").path(0).path("id").asText());
      assertEquals(before, service.desktop().state());
      assertEquals(1, service.snapshot().path("words").size());
      assertThrows(
          ApiException.class,
          () ->
              service
                  .desktop()
                  .query(
                      Json.MAPPER
                          .createObjectNode()
                          .put("kind", "planningPreview")
                          .put("goal", "exam:test")
                          .put("daysAhead", 8)));
    }
  }
}
