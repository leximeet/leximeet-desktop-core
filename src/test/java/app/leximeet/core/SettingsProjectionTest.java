package app.leximeet.core;

import static app.leximeet.core.DesktopWorkspaceTest.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// 当前 schema 100 的设置缺省投影，不迁移旧库、不批量重写已经保存的用户偏好。
class SettingsProjectionTest {
  @TempDir Path directory;

  ObjectNode previousSettings() {
    return Json.MAPPER
        .createObjectNode()
        .put("theme", "dark")
        .put("translationEnabled", false)
        .put("reviewStrictness", "lenient")
        .put("developerMonitoringEnabled", false)
        .put("closeBehavior", "hide")
        .put("launchAtLogin", false)
        .put("qualityMetricsEnabled", false)
        .put("pluginCaptureNotificationsEnabled", false)
        .put("clipboardCaptureEnabled", true)
        .put("clipboardAutoCollect", false)
        .put("globalShortcutEnabled", false)
        .put("practiceSpeakEnabled", false)
        .put("practiceKeySoundEnabled", false)
        .put("practiceResultSoundEnabled", false)
        .put("practiceVolume", 37)
        .put("encounterNewLimit", 8)
        .put("plannedNewLimit", 12)
        .put("reviewInterleaveEvery", 4)
        .put("reviewsAfterNew", true);
  }

  String raw(LeximeetService service) throws Exception {
    return service
        .lmcp()
        .database
        .read(db -> Sql.first(db, "SELECT payload FROM settings WHERE id=1"))
        .path("payload")
        .asText();
  }

  void store(LeximeetService service, String payload) throws Exception {
    service
        .lmcp()
        .database
        .transaction(
            db -> {
              Sql.execute(db, "UPDATE settings SET payload=? WHERE id=1", payload);
              return null;
            });
  }

  void verify(LeximeetService service, ObjectNode stored, int days, boolean redact, int length)
      throws Exception {
    String before = raw(service);
    JsonNode state = service.desktop().state().path("settings");
    assertEquals(service.snapshot().path("settings"), state);
    stored
        .fields()
        .forEachRemaining(
            entry -> assertEquals(entry.getValue(), state.get(entry.getKey()), entry.getKey()));
    assertEquals(days, state.path("captureDuplicateWindowDays").asInt());
    assertEquals(redact, state.path("captureSensitiveRedactionEnabled").asBoolean());
    assertEquals(length, state.path("captureContextMaxLength").asInt());
    var policy = service.lmcp().database.read(db -> CapturePolicy.preferences(db).json());
    assertEquals(days, policy.path("duplicateWindowDays").asInt());
    assertEquals(redact, policy.path("sensitiveRedactionEnabled").asBoolean());
    assertEquals(length, policy.path("contextMaxLength").asInt());
    assertEquals(before, raw(service));
    assertEquals(
        100,
        service
            .lmcp()
            .database
            .read(db -> Sql.first(db, "PRAGMA user_version"))
            .path("user_version")
            .asInt());
  }

  @Test
  void currentModelWithNineteenFieldsProjectsDefaultsAndNeverPersistsThemIncludingRestart()
      throws Exception {
    ObjectNode stored = previousSettings();
    assertEquals(19, stored.size());
    String payload = stored.toString();
    try (var service = new LeximeetService(directory, CLOCK)) {
      store(service, payload);
      verify(service, stored, 7, true, 500);
      assertEquals(payload, raw(service));
    }
    try (var service = new LeximeetService(directory, CLOCK)) {
      verify(service, stored, 7, true, 500);
      assertEquals(payload, raw(service));
    }
  }

  @Test
  void explicitUserValuesSurviveReadProjectionAndRestart() throws Exception {
    var stored =
        previousSettings()
            .put("captureDuplicateWindowDays", 0)
            .put("captureSensitiveRedactionEnabled", false)
            .put("captureContextMaxLength", 120)
            .put("clipboardReminderMode", "popup");
    String payload = stored.toString();
    try (var service = new LeximeetService(directory, CLOCK)) {
      store(service, payload);
      verify(service, stored, 0, false, 120);
    }
    try (var service = new LeximeetService(directory, CLOCK)) {
      verify(service, stored, 0, false, 120);
      assertEquals(payload, raw(service));
    }
  }
}
