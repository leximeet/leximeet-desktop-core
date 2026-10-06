package app.leximeet.core;

import static app.leximeet.core.DesktopWorkspaceTest.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// 真实词书成员和个人库验证：目标限定只属于剪贴板，采集语境不等于学习或手动扩充。
class DesktopClipboardTest {
  @TempDir Path directory;

  DesktopWorkspaceTest fixture() {
    var fixture = new DesktopWorkspaceTest();
    fixture.directory = directory;
    return fixture;
  }

  Path index() throws Exception {
    Path file = fixture().index(directory);
    try (var db = DriverManager.getConnection("jdbc:sqlite:" + file)) {
      // beta 在词典中，但不在这本目标中，不能只检查“查得到”。
      Sql.execute(db, "DELETE FROM members WHERE entry_id=?", BETA);
    }
    return file;
  }

  ObjectNode candidates(String... words) {
    var input = Json.MAPPER.createObjectNode().put("kind", "clipboardCandidates");
    var values = input.putArray("words");
    for (String word : words) values.add(word);
    return input;
  }

  ObjectNode capture(String goal, String id) {
    return Json.MAPPER
        .createObjectNode()
        .put("action", "captureClipboard")
        .put("goal", goal)
        .put("wordId", id)
        .put("context", "Alpha beta in context.");
  }

  @Test
  void onlyCurrentGoalMembersMatchAndQueriesNeverCreatePersonalData() throws Exception {
    Path file = index();
    try (var service = new LeximeetService(directory, CLOCK)) {
      fixture().mount(service, file);
      assertTrue(service.desktop().query(candidates("alpha")).path("matches").isEmpty());
      fixture().goal(service, "exam:test");
      var result = service.desktop().query(candidates("ALPHA", "alpha", "beta", "unknown"));
      assertEquals("exam:test", result.path("goal").asText());
      assertEquals(1, result.path("matches").size());
      assertEquals(ALPHA, result.path("matches").get(0).path("wordId").asText());
      assertTrue(service.snapshot().path("words").isEmpty());
      // 暂停计划和是否已练过不改变目标成员；整本词典必须由用户显式选择。
      var plan = service.desktop().state().path("profile");
      service
          .desktop()
          .command(
              Json.MAPPER
                  .createObjectNode()
                  .put("action", "savePlan")
                  .put("planEnabled", false)
                  .put("expectedRevision", plan.path("revision").asInt()));
      assertEquals(1, service.desktop().query(candidates("alpha")).path("matches").size());
      fixture().goal(service, "dictionary");
      assertEquals(2, service.desktop().query(candidates("alpha", "beta")).path("matches").size());
    }
  }

  @Test
  void saveRechecksGoalToggleMembershipAndTrashInsideTheTransaction() throws Exception {
    Path file = index();
    try (var service = new LeximeetService(directory, CLOCK)) {
      fixture().mount(service, file);
      fixture().goal(service, "exam:test");
      assertFalse(
          service
              .desktop()
              .command(capture("exam:test", ALPHA))
              .path("clipboardAccepted")
              .asBoolean());
      service.updateSettings(json("{\"clipboardCaptureEnabled\":true}"));
      assertFalse(
          service
              .desktop()
              .command(capture("exam:test", BETA))
              .path("clipboardAccepted")
              .asBoolean());
      assertTrue(service.snapshot().path("words").isEmpty());
      fixture().goal(service, "dictionary");
      assertFalse(
          service
              .desktop()
              .command(capture("exam:test", ALPHA))
              .path("clipboardAccepted")
              .asBoolean());
      fixture().goal(service, "exam:test");
      assertTrue(
          service
              .desktop()
              .command(capture("exam:test", ALPHA))
              .path("clipboardAccepted")
              .asBoolean());
      var detail =
          service
              .desktop()
              .query(Json.MAPPER.createObjectNode().put("kind", "detail").put("wordId", ALPHA));
      assertEquals(
          "Alpha beta in context.", detail.path("encounters").get(0).path("context").asText());
      assertEquals("系统剪贴板", detail.path("encounters").get(0).path("sourceTitle").asText());
      assertFalse(detail.path("manualActive").asBoolean());
      assertEquals(10, detail.path("familiarity").path("score").asInt());
      assertEquals(0, detail.path("reviewCount").asInt());
      service
          .desktop()
          .command(Json.MAPPER.createObjectNode().put("action", "trash").put("wordId", ALPHA));
      assertTrue(service.desktop().query(candidates("alpha")).path("matches").isEmpty());
      assertFalse(
          service
              .desktop()
              .command(capture("exam:test", ALPHA))
              .path("clipboardAccepted")
              .asBoolean());
      assertEquals(1, service.desktop().state().path("insights").path("encounters").asInt());
    }
  }

  @Test
  void manualCaptureCanAddOutsideWordsAndCollectedContextsSurviveGoalChange() throws Exception {
    Path file = index();
    try (var service = new LeximeetService(directory, CLOCK)) {
      fixture().mount(service, file);
      fixture().goal(service, "exam:test");
      service.updateSettings(json("{\"clipboardCaptureEnabled\":true}"));
      service.desktop().command(capture("exam:test", ALPHA));
      service
          .desktop()
          .command(json("{\"action\":\"capture\",\"word\":\"beta\",\"context\":\"manual beta\"}"));
      service
          .desktop()
          .command(
              json(
                  "{\"action\":\"capture\",\"word\":\"outsideword\",\"context\":\"manual"
                      + " outsideword\"}"));
      fixture().goal(service, "");
      assertTrue(service.desktop().query(candidates("alpha", "beta")).path("matches").isEmpty());
      assertEquals(
          3, service.desktop().query(json("{\"kind\":\"encounters\"}")).path("total").asInt());
      assertTrue(
          service
              .desktop()
              .query(json("{\"kind\":\"detail\",\"wordId\":\"beta\"}"))
              .path("manualActive")
              .asBoolean());
    }
  }

  @Test
  void candidateLimitsRejectMalformedOrUnboundedInput() throws Exception {
    try (var service = new LeximeetService(directory, CLOCK)) {
      assertThrows(ApiException.class, () -> service.desktop().query(candidates("not a token")));
      assertThrows(ApiException.class, () -> service.desktop().query(candidates("123")));
      assertThrows(
          ApiException.class,
          () -> service.desktop().query(candidates("alpha").put("words", "alpha")));
      var tooMany = candidates();
      for (int n = 0; n < 201; n++) tooMany.withArray("words").add("alpha");
      assertThrows(ApiException.class, () -> service.desktop().query(tooMany));
    }
  }
}
