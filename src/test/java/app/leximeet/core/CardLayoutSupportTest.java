package app.leximeet.core;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// 从真实 SQLite 快照验证词卡展示配置，不以渲染器替身代替持久化与冲突判断。
class CardLayoutSupportTest {
  @TempDir Path temporary;

  private static ObjectNode json(String text) throws Exception {
    return (ObjectNode) Json.MAPPER.readTree(text);
  }

  @Test
  void defaultIsBalancedAndCustomLayoutSurvivesRestartAndBackup() throws Exception {
    ObjectNode backup;
    try (var service = new LeximeetService(temporary.resolve("source"), Clock.systemUTC())) {
      var original = service.snapshot().path("settings").path("cardLayout");
      assertEquals("balanced", original.path("level").asText());
      assertEquals("preset", original.path("mode").asText());
      assertEquals(0, original.path("revision").asInt());
      var saved =
          service.updateCardLayout(
              json(
                  "{"
                      + "\"expectedRevision\":0,\"level\":\"rich\",\"mode\":\"custom\","
                      + "\"customSections\":[\"meaning\",\"note\",\"evidence\"]}"));
      assertEquals(1, saved.path("settings").path("cardLayout").path("revision").asInt());
      assertTrue(saved.path("settings").path("cardLayout").path("customSaved").asBoolean());
      assertEquals(
          "meaning",
          saved.path("settings").path("cardLayout").path("customSections").get(0).asText());
      backup = service.exportBackup();
      assertEquals(100, backup.path("version").asInt());
      assertEquals("sqlite-base64", backup.path("encoding").asText());
    }
    try (var restarted = new LeximeetService(temporary.resolve("source"), Clock.systemUTC())) {
      assertEquals(
          1, restarted.snapshot().path("settings").path("cardLayout").path("revision").asInt());
    }
    try (var restored = new LeximeetService(temporary.resolve("restore"), Clock.systemUTC())) {
      assertEquals(
          "custom",
          restored.importBackup(backup).path("settings").path("cardLayout").path("mode").asText());
    }
  }

  @Test
  void switchingPresetKeepsThePreviouslySavedCustomOrder() throws Exception {
    try (var service = new LeximeetService(temporary.resolve("switch"), Clock.systemUTC())) {
      service.updateCardLayout(
          json(
              "{\"expectedRevision\":0,\"level\":\"balanced\","
                  + "\"mode\":\"custom\",\"customSections\":[\"note\",\"meaning\"]}"));
      var preset =
          service.updateCardLayout(
              json(
                  "{\"expectedRevision\":1,\"level\":\"complete\","
                      + "\"mode\":\"preset\",\"customSections\":[\"note\",\"meaning\"]}"));
      var layout = preset.path("settings").path("cardLayout");
      assertTrue(layout.path("customSaved").asBoolean());
      assertEquals("note", layout.path("customSections").get(0).asText());
    }
  }

  @Test
  void invalidOrStaleMutationNeverChangesStoredLayout() throws Exception {
    try (var service = new LeximeetService(temporary.resolve("profile"), Clock.systemUTC())) {
      var first =
          service.updateCardLayout(
              json(
                  "{"
                      + "\"expectedRevision\":0,\"level\":\"minimal\",\"mode\":\"preset\","
                      + "\"customSections\":[\"meaning\"]}"));
      var committed = first.path("settings").path("cardLayout");
      assertEquals(
          409,
          assertThrows(
                  ApiException.class,
                  () ->
                      service.updateCardLayout(
                          json(
                              "{\"expectedRevision\":0,\"level\":\"complete\",\"mode\":\"preset\","
                                  + "\"customSections\":[\"meaning\"]}")))
              .status());
      for (String sections :
          new String[] {
            "[]", "[\"note\"]", "[\"meaning\",\"meaning\"]", "[\"meaning\",\"unknown\"]"
          }) {
        var input =
            json(
                "{\"expectedRevision\":1,\"level\":\"minimal\",\"mode\":\"custom\",\"customSections\":"
                    + sections
                    + "}");
        assertEquals(
            400, assertThrows(ApiException.class, () -> service.updateCardLayout(input)).status());
      }
      assertEquals(
          400,
          assertThrows(
                  ApiException.class, () -> service.updateSettings(json("{\"cardLayout\":{}}")))
              .status());
      assertEquals(committed, service.snapshot().path("settings").path("cardLayout"));
    }
  }
}
