package app.leximeet.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// 只使用各测试独有的临时资料，验证通知偏好真实落库，拒绝页面的宽松类型转换。
class PluginCaptureSettingsTest {
  @TempDir Path directory;

  private LeximeetService service(String profile) throws Exception {
    return new LeximeetService(directory.resolve(profile), Clock.systemUTC());
  }

  private static ObjectNode settings(boolean enabled) {
    return Json.MAPPER.createObjectNode().put("pluginCaptureNotificationsEnabled", enabled);
  }

  @Test
  void defaultsOnAndBothChoicesSurviveDatabaseRestart() throws Exception {
    try (var service = service("profile")) {
      JsonNode initial =
          service.snapshot().path("settings").path("pluginCaptureNotificationsEnabled");
      assertTrue(initial.isBoolean());
      assertTrue(initial.asBoolean());
      assertFalse(
          service
              .updateSettings(settings(false))
              .path("settings")
              .path("pluginCaptureNotificationsEnabled")
              .asBoolean());
    }
    try (var restarted = service("profile")) {
      assertFalse(
          restarted
              .snapshot()
              .path("settings")
              .path("pluginCaptureNotificationsEnabled")
              .asBoolean());
      restarted.updateSettings(settings(true));
    }
    try (var restarted = service("profile")) {
      assertTrue(
          restarted
              .snapshot()
              .path("settings")
              .path("pluginCaptureNotificationsEnabled")
              .asBoolean());
    }
  }

  @Test
  void invalidTypesRejectTheWholePatchWithoutChangingOtherPreferences() throws Exception {
    try (var service = service("profile")) {
      service.updateSettings(settings(false));
      JsonNode before = service.snapshot().path("settings");
      for (String literal : new String[] {"\"true\"", "1", "null", "{}", "[]"}) {
        ObjectNode patch =
            (ObjectNode)
                Json.MAPPER.readTree(
                    "{\"theme\":\"dark\",\"pluginCaptureNotificationsEnabled\":" + literal + "}");
        ApiException rejected =
            assertThrows(ApiException.class, () -> service.updateSettings(patch));
        assertEquals(400, rejected.status());
        assertTrue(rejected.getMessage().contains("pluginCaptureNotificationsEnabled"));
        assertEquals(before, service.snapshot().path("settings"));
      }
    }
  }

  @Test
  void currentBackupPreservesDisabledPreferenceAndRejectsInvalidType() throws Exception {
    ObjectNode backup;
    try (var source = service("source")) {
      source.updateSettings(settings(false));
      backup = source.exportBackup();
    }
    try (var destination = service("destination")) {
      assertFalse(
          destination
              .importBackup(backup)
              .path("settings")
              .path("pluginCaptureNotificationsEnabled")
              .asBoolean());
      ObjectNode malformed = backup.deepCopy();
      malformed.put("data", "not-base64");
      assertThrows(ApiException.class, () -> destination.importBackup(malformed));
      assertFalse(
          destination
              .snapshot()
              .path("settings")
              .path("pluginCaptureNotificationsEnabled")
              .asBoolean());
    }
  }
}
