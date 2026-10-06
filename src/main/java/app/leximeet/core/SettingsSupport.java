package app.leximeet.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

// 设置 JSON 的缺省值与白名单。新增可选字段时不抬升 schema：旧备份缺少新键则补默认值， 未知键仍然拒绝。剪贴板与全局快捷键默认关闭，质量统计默认可读、可关闭。
final class SettingsSupport {
  static final String DEFAULT_JSON =
      "{\"theme\":\"system\",\"translationEnabled\":false,\"reviewStrictness\":\"normal\","
          + "\"developerMonitoringEnabled\":false,\"closeBehavior\":\"platform\",\"launchAtLogin\":false,"
          + "\"qualityMetricsEnabled\":true,\"pluginCaptureNotificationsEnabled\":true,\"captureDuplicateWindowDays\":7,\"captureSensitiveRedactionEnabled\":true,\"captureContextMaxLength\":500,\"clipboardCaptureEnabled\":false,\"clipboardAutoCollect\":true,\"globalShortcutEnabled\":false,"
          + "\"practiceSpeakEnabled\":true,\"practiceKeySoundEnabled\":true,\"practiceResultSoundEnabled\":true,"
          + "\"practiceVolume\":60,\"encounterNewLimit\":10,\"plannedNewLimit\":20,"
          + "\"reviewInterleaveEvery\":3,\"reviewsAfterNew\":false}";
  private static final String[] REQUIRED_BACKUP = {
    "theme", "translationEnabled", "reviewStrictness",
    "developerMonitoringEnabled", "closeBehavior", "launchAtLogin"
  };

  private SettingsSupport() {}

  static ObjectNode normalize(ObjectNode settings) {
    if (!settings.has("qualityMetricsEnabled")) settings.put("qualityMetricsEnabled", true);
    // 插件采集成功提示由 Main 消费；它不改变采集授权、事务或 LMCP 回执。
    if (!settings.has("pluginCaptureNotificationsEnabled"))
      settings.put("pluginCaptureNotificationsEnabled", true);
    if (!settings.has("captureDuplicateWindowDays")) settings.put("captureDuplicateWindowDays", 7);
    if (!settings.has("captureSensitiveRedactionEnabled"))
      settings.put("captureSensitiveRedactionEnabled", true);
    if (!settings.has("captureContextMaxLength")) settings.put("captureContextMaxLength", 500);
    if (!settings.has("clipboardCaptureEnabled")) settings.put("clipboardCaptureEnabled", false);
    if (!settings.has("clipboardReminderMode")) settings.put("clipboardReminderMode", "system");
    if (!settings.has("clipboardAutoCollect")) settings.put("clipboardAutoCollect", true);
    if (!settings.has("globalShortcutEnabled")) settings.put("globalShortcutEnabled", false);
    if (!settings.has("practiceSpeakEnabled")) settings.put("practiceSpeakEnabled", true);
    if (!settings.has("practiceKeySoundEnabled")) settings.put("practiceKeySoundEnabled", true);
    if (!settings.has("practiceResultSoundEnabled"))
      settings.put("practiceResultSoundEnabled", true);
    if (!settings.has("practiceVolume")) settings.put("practiceVolume", 60);
    if (!settings.has("encounterNewLimit")) settings.put("encounterNewLimit", 10);
    if (!settings.has("plannedNewLimit")) settings.put("plannedNewLimit", 20);
    if (!settings.has("reviewInterleaveEvery")) settings.put("reviewInterleaveEvery", 3);
    if (!settings.has("reviewsAfterNew")) settings.put("reviewsAfterNew", false);
    if (!settings.has("cardLayout")) settings.set("cardLayout", CardLayoutSupport.defaults());
    return settings;
  }

  static void validate(JsonNode input) {
    Json.fields(
        input,
        "theme",
        "translationEnabled",
        "reviewStrictness",
        "developerMonitoringEnabled",
        "closeBehavior",
        "launchAtLogin",
        "qualityMetricsEnabled",
        "pluginCaptureNotificationsEnabled",
        "captureDuplicateWindowDays",
        "captureSensitiveRedactionEnabled",
        "captureContextMaxLength",
        "clipboardCaptureEnabled",
        "clipboardAutoCollect",
        "clipboardReminderMode",
        "globalShortcutEnabled",
        "practiceSpeakEnabled",
        "practiceKeySoundEnabled",
        "practiceResultSoundEnabled",
        "practiceVolume",
        "encounterNewLimit",
        "plannedNewLimit",
        "reviewInterleaveEvery",
        "reviewsAfterNew");
    Json.choice(input, "clipboardReminderMode", "system", "system", "popup");
    Json.choice(input, "theme", "system", "system", "light", "dark");
    Json.choice(input, "reviewStrictness", "normal", "normal", "lenient", "strict");
    Json.choice(input, "closeBehavior", "platform", "platform", "hide", "quit");
    for (String key :
        new String[] {
          "translationEnabled",
          "developerMonitoringEnabled",
          "launchAtLogin",
          "qualityMetricsEnabled",
          "pluginCaptureNotificationsEnabled",
          "captureSensitiveRedactionEnabled",
          "clipboardCaptureEnabled",
          "clipboardAutoCollect",
          "globalShortcutEnabled",
          "practiceSpeakEnabled",
          "practiceKeySoundEnabled",
          "practiceResultSoundEnabled"
        })
      if (input.has(key) && !input.get(key).isBoolean())
        throw ApiException.badRequest(key + " 必须为布尔值");
    if (input.has("practiceVolume")) {
      JsonNode volume = input.get("practiceVolume");
      if (!volume.isIntegralNumber()
          || !volume.canConvertToInt()
          || volume.asInt() < 0
          || volume.asInt() > 100) throw ApiException.badRequest("practiceVolume 必须是 0–100 的整数");
    }
    bounded(input, "encounterNewLimit", 0, 100);
    bounded(input, "captureDuplicateWindowDays", 0, 365);
    bounded(input, "captureContextMaxLength", 120, 2000);
    bounded(input, "plannedNewLimit", 0, 200);
    bounded(input, "reviewInterleaveEvery", 1, 20);
    if (input.has("reviewsAfterNew") && !input.get("reviewsAfterNew").isBoolean())
      throw ApiException.badRequest("reviewsAfterNew 必须为布尔值");
  }

  private static void bounded(JsonNode input, String key, int min, int max) {
    if (!input.has(key)) return;
    JsonNode value = input.get(key);
    if (!value.isIntegralNumber()
        || !value.canConvertToInt()
        || value.asInt() < min
        || value.asInt() > max) throw ApiException.badRequest(key + " 超出允许范围");
  }

  static void requireBackupCoreFields(JsonNode settings) {
    // 备份携带已提交的展示偏好；普通 settings PATCH 不能绕过词卡专用的修订检查。
    Json.fields(
        settings,
        "theme",
        "translationEnabled",
        "reviewStrictness",
        "developerMonitoringEnabled",
        "closeBehavior",
        "launchAtLogin",
        "qualityMetricsEnabled",
        "pluginCaptureNotificationsEnabled",
        "captureDuplicateWindowDays",
        "captureSensitiveRedactionEnabled",
        "captureContextMaxLength",
        "clipboardCaptureEnabled",
        "clipboardAutoCollect",
        "clipboardReminderMode",
        "globalShortcutEnabled",
        "practiceSpeakEnabled",
        "practiceKeySoundEnabled",
        "practiceResultSoundEnabled",
        "practiceVolume",
        "encounterNewLimit",
        "plannedNewLimit",
        "reviewInterleaveEvery",
        "reviewsAfterNew",
        "cardLayout");
    if (settings.has("cardLayout")) CardLayoutSupport.validateStored(settings.get("cardLayout"));
    ObjectNode ordinary = ((ObjectNode) settings).deepCopy();
    ordinary.remove("cardLayout");
    // 熟练事件是独立事实表；设置备份不接受事件字段，防止绕过专用命令。
    validate(ordinary);
    for (String key : REQUIRED_BACKUP)
      if (!settings.hasNonNull(key)) throw ApiException.badRequest("当前备份缺少设置字段：" + key);
  }
}
