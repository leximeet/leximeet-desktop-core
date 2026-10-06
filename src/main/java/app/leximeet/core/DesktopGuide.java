package app.leximeet.core;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Connection;
import java.time.Clock;
import java.util.List;

// 教学只保存进度：真实操作由业务回执完成，参观步骤由明确的“下一步”确认，不制造个人事实。
final class DesktopGuide {
  static final int FLOW_VERSION = 3;
  static final List<String> STEPS =
      List.of(
          "sidebar",
          "goal",
          "plan",
          "learn",
          "audio",
          "practice",
          "clipboard",
          "trash",
          "dictionary",
          "insights",
          "theme",
          "audioSettings",
          "captureSettings");
  static final List<String> READING_STEPS =
      List.of(
          "sidebar",
          "clipboard",
          "trash",
          "dictionary",
          "insights",
          "theme",
          "audioSettings",
          "captureSettings");
  private final Clock clock;

  DesktopGuide(Clock clock) {
    this.clock = clock;
  }

  ObjectNode state(Connection db) throws Exception {
    ObjectNode saved =
        Json.object(
            Json.MAPPER.readTree(
                Sql.first(db, "SELECT payload FROM desktop_guide WHERE id=1")
                    .path("payload")
                    .asText()));
    if (saved.path("flowVersion").asInt() != FLOW_VERSION)
      throw new IllegalStateException("教学版本不属于当前资料空间，请使用新的隔离目录");
    return saved;
  }

  void change(Connection db, String action, String event) throws Exception {
    ObjectNode guide = state(db);
    switch (action) {
      case "guideReset" -> {
        guide =
            Json.MAPPER
                .createObjectNode()
                .put("flowVersion", FLOW_VERSION)
                .put("active", true)
                .put("started", false);
        guide.putArray("completed");
      }
      case "guidePause" -> guide.put("active", false);
      case "guideResume" -> guide.put("active", true);
      case "guideStart" -> guide.put("started", true).put("active", true);
      case "guidePrevious" -> {
        if (guide.path("active").asBoolean() && guide.path("started").asBoolean())
          guide.put(
              "cursor",
              Math.max(0, guide.path("cursor").asInt(guide.path("completed").size()) - 1));
      }
      case "guideNext", "guideEvent", "nativeEvent" -> {
        if (!guide.path("active").asBoolean() || !guide.path("started").asBoolean()) return;
        String step =
            STEPS.size() > guide.path("cursor").asInt(guide.path("completed").size())
                ? STEPS.get(guide.path("cursor").asInt(guide.path("completed").size()))
                : "complete";
        boolean valid;
        if (action.equals("guideNext")) {
          if (!(guide.path("cursor").asInt(guide.path("completed").size())
                      < guide.path("completed").size()
                  || READING_STEPS.contains(step)
                  || step.equals("goal")
                  || step.equals("plan")
                  || step.equals("learn")
                  || step.equals("practice"))
              || !step.equals(event)) throw ApiException.badRequest("请先完成当前教学操作，不能跳到后面的讲解");
          valid = true;
        } else {
          valid =
              switch (step) {
                case "goal", "plan" -> event.equals(step);
                case "learn" ->
                    event.equals("learn")
                        && Sql.first(db, "SELECT 1 FROM desktop_practice_facts LIMIT 1") != null;
                case "audio" -> action.equals("nativeEvent") && event.equals("audio");
                case "practice" ->
                    event.equals("practice")
                        && Sql.first(db, "SELECT 1 FROM desktop_practice_facts LIMIT 1") != null;
                case "book" ->
                    event.equals("book")
                        && Sql.first(
                                db,
                                "SELECT 1 FROM books b WHERE NOT EXISTS(SELECT 1 FROM book_roles r"
                                    + " WHERE r.book_id=b.id) LIMIT 1")
                            != null;
                default -> false;
              };
        }
        if (valid) advance(guide, step);
      }
      default -> throw ApiException.badRequest("引导动作无效");
    }
    if (guide.path("cursor").asInt(guide.path("completed").size()) == STEPS.size())
      guide.put("active", false).put("finishedAt", clock.instant().toString());
    Sql.execute(db, "UPDATE desktop_guide SET payload=? WHERE id=1", guide.toString());
  }

  private void advance(ObjectNode guide, String step) {
    int index = guide.path("cursor").asInt(guide.path("completed").size());
    if (index < STEPS.size() && STEPS.get(index).equals(step)) {
      if (index == guide.path("completed").size()) {
        guide.withArray("completed").add(step);
        guide.put("advancedAt", clock.instant().toString());
      }
      guide.put("cursor", index + 1);
    }
  }
}
