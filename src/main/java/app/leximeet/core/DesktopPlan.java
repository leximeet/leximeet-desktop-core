package app.leximeet.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Connection;

// 目标、计划和提醒各自可选；时间窗口用于提醒，不限制随时练习。
final class DesktopPlan {
  static final int REMINDER_INTERVAL_MINUTES = 30;

  static ObjectNode state(Connection db) throws Exception {
    var plan =
        Sql.first(
            db,
            "SELECT goal,daily_new AS dailyNew,daily_review AS dailyReview,study_time AS"
                + " studyTime,revision,plan_enabled AS planEnabled,reminder_enabled AS"
                + " reminderEnabled,study_start AS studyStart,study_end AS studyEnd,study_zone AS"
                + " studyZone,reminder_interval AS reminderInterval,plan_id AS planId,started_on AS"
                + " startedOn,saved_at AS savedAt,reminder_modes AS reminderModesJson FROM"
                + " desktop_profile WHERE id=1");
    plan.set("reminderModes", Json.MAPPER.readTree(plan.remove("reminderModesJson").asText()));
    plan.put("planEnabled", plan.path("planEnabled").asBoolean());
    plan.put("reminderEnabled", plan.path("reminderEnabled").asBoolean());
    return plan;
  }

  static void save(Connection db, JsonNode input) throws Exception {
    Json.fields(
        input,
        "action",
        "goal",
        "dailyNew",
        "dailyReview",
        "studyTime",
        "expectedRevision",
        "planEnabled",
        "reminderEnabled",
        "studyStart",
        "studyEnd",
        "reminderInterval",
        "reminderModes");
    var current = state(db);
    JsonNode modes =
        reminderModes(
            input.has("reminderModes")
                ? input.path("reminderModes")
                : current.path("reminderModes"));
    for (String field : new String[] {"planEnabled", "reminderEnabled"})
      if (input.has(field) && !input.path(field).isBoolean())
        throw ApiException.badRequest(field + " 必须是布尔值");
    int dailyNew = DesktopWords.integer(input, "dailyNew", current.path("dailyNew").asInt(), 1, 50);
    int dailyReview =
        DesktopWords.integer(input, "dailyReview", current.path("dailyReview").asInt(), 0, 500);
    // 间隔由设备调度策略固定，不再要求用户在学习规划中选择。
    int interval =
        DesktopWords.integer(
            input,
            "reminderInterval",
            REMINDER_INTERVAL_MINUTES,
            REMINDER_INTERVAL_MINUTES,
            REMINDER_INTERVAL_MINUTES);
    String start = time(input, current, "studyStart"), end = time(input, current, "studyEnd");
    if (start.compareTo(end) >= 0) throw ApiException.badRequest("学习开始时间应早于结束时间");
    String legacyTime =
        input.has("studyTime")
            ? Json.text(input, "studyTime", 5, true)
            : current.path("studyTime").asText();
    if (!legacyTime.matches("([01][0-9]|2[0-3]):[0-5][0-9]"))
      throw ApiException.badRequest("时间格式应为 HH:mm");
    if (Sql.executeCount(
            db,
            "UPDATE desktop_profile SET"
                + " daily_new=?,daily_review=?,study_time=?,plan_enabled=?,reminder_enabled=?,study_start=?,study_end=?,reminder_interval=?,reminder_modes=?,revision=revision+1"
                + " WHERE id=1 AND revision=?",
            dailyNew,
            dailyReview,
            legacyTime,
            input.path("planEnabled").asBoolean(true) ? 1 : 0,
            input.has("reminderEnabled")
                ? (input.path("reminderEnabled").asBoolean() ? 1 : 0)
                : (current.path("reminderEnabled").asBoolean() ? 1 : 0),
            start,
            end,
            interval,
            modes.toString(),
            input.path("expectedRevision").asInt())
        != 1) throw ApiException.conflict("计划已变化，请刷新后重试");
  }

  // 设置页单独保存通知方式，不改变目标、每日额度、开始日或学习事件。
  static void saveReminderPreferences(Connection db, JsonNode input) throws Exception {
    Json.fields(input, "action", "reminderModes", "expectedRevision");
    JsonNode modes = reminderModes(input.path("reminderModes"));
    if (Sql.executeCount(
            db,
            "UPDATE desktop_profile SET reminder_modes=?,revision=revision+1 WHERE id=1 AND"
                + " revision=?",
            modes.toString(),
            input.path("expectedRevision").asInt())
        != 1) throw ApiException.conflict("提醒设置已变化，请刷新后重试");
  }

  private static JsonNode reminderModes(JsonNode modes) {
    if (!modes.isArray() || modes.isEmpty() || modes.size() > 6)
      throw ApiException.badRequest("请至少选择一种提醒练习");
    var unique = new java.util.HashSet<String>();
    for (var mode : modes)
      if (!mode.isTextual()
          || !DesktopPractice.MODES.contains(mode.asText())
          || !unique.add(mode.asText())) throw ApiException.badRequest("提醒练习模式无效或重复");
    return modes;
  }

  private static String time(JsonNode input, JsonNode current, String key) {
    String value = input.has(key) ? Json.text(input, key, 5, true) : current.path(key).asText();
    if (!value.matches("([01][0-9]|2[0-3]):[0-5][0-9]"))
      throw ApiException.badRequest("学习时间格式应为 HH:mm");
    return value;
  }
}
