package app.leximeet.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Connection;
import java.time.Clock;
import java.util.ArrayList;
import java.util.UUID;

// 通知只承载固定的一道题。判题、幂等和积分仍由 Core 完成，关闭通知不会生成答题事件。
final class DesktopReminderPractice {
  private final DesktopWords words;
  private final Clock clock;
  private DesktopDataModel protocolWorkspace;

  void bindProtocolWorkspace(DesktopDataModel workspace) {
    protocolWorkspace = workspace;
  }

  DesktopReminderPractice(DesktopWords words, Clock clock) {
    this.words = words;
    this.clock = clock;
  }

  ObjectNode issue(Connection db, JsonNode input, JsonNode queue) throws Exception {
    Json.fields(input, "action", "supportedModes");
    JsonNode selected = DesktopPlan.state(db).path("reminderModes");
    var modes = new ArrayList<String>();
    for (var value : selected) {
      for (var supported : input.path("supportedModes"))
        if (value.asText().equals(supported.asText())) {
          modes.add(value.asText());
          break;
        }
    }
    if (modes.isEmpty()) return unavailable("当前系统不支持所选通知答题方式，可调整提醒练习类型");
    long count =
        Sql.first(db, "SELECT COUNT(*) AS n FROM desktop_reminder_questions").path("n").asLong();
    java.util.Collections.rotate(modes, -(int) (count % modes.size()));
    for (var task : queue.path("tasks")) {
      String id = task.path("id").asText();
      if (Sql.first(
              db,
              "SELECT 1 FROM desktop_reminder_questions WHERE word_id=? AND answered_at IS NULL AND"
                  + " expires_at>?",
              id,
              clock.instant().toString())
          != null) continue;
      for (String mode : modes) {
        String attempt = UUID.randomUUID().toString();
        ObjectNode frozen;
        var checkpoint = db.setSavepoint();
        try {
          frozen =
              protocolWorkspace.localQuestion(
                  db,
                  Json.MAPPER
                      .createObjectNode()
                      .put("kind", "practiceQuestion")
                      .put("wordId", id)
                      .put("mode", mode)
                      .put("attemptId", attempt));
          if (frozen.path("unavailable").asBoolean())
            throw LmcpService.error("EXERCISE_UNAVAILABLE", frozen.path("reason").asText());
          db.releaseSavepoint(checkpoint);
        } catch (ApiException failure) {
          if (!failure.code().equals("EXERCISE_UNAVAILABLE")) throw failure;
          // 没有可用题目才尝试下一个模式；同时回滚本次尚未完整冻结的单题会话。
          db.rollback(checkpoint);
          db.releaseSavepoint(checkpoint);
          continue;
        }
        // 通知和判题共用同一份冻结展示，不能再独立挑选例句或重新读取可变释义。
        var question =
            Json.MAPPER
                .createObjectNode()
                .put("id", attempt)
                .put("wordId", id)
                .put("word", frozen.path("word").asText())
                .put("mode", mode)
                .put("ruleVersion", LearningPolicy.VERSION)
                .put("meaning", frozen.path("meaning").asText())
                .put("createdAt", clock.instant().toString())
                .put("expiresAt", clock.instant().plusSeconds(1800).toString())
                .put("questionId", frozen.path("questionId").asText());
        question.set("options", frozen.path("options"));
        if (mode.equals("cloze")) question.set("context", frozen.path("context"));
        String canonical = words.materialize(db, id, false);
        question.put("wordId", canonical);
        Sql.execute(
            db,
            "INSERT INTO desktop_reminder_questions VALUES(?,?,?,?,?,?,NULL,NULL)",
            attempt,
            canonical,
            mode,
            question.toString(),
            clock.instant().toString(),
            question.path("expiresAt").asText());
        return question;
      }
    }
    return unavailable("暂无适合所选模式的待学词，或已有题目等待回答");
  }

  ObjectNode answer(Connection db, JsonNode input) throws Exception {
    Json.fields(input, "action", "questionId", "answer", "signal", "choiceId");
    String id = Json.id(input, "questionId", true);
    var saved = Sql.first(db, "SELECT * FROM desktop_reminder_questions WHERE id=?", id);
    if (saved == null) throw ApiException.notFound("提醒题目不存在");
    ObjectNode answer = Json.object(input).deepCopy();
    answer.remove("action");
    // 已回答的相同请求允许安全重试；不同回答不可覆盖首次结果。
    if (!saved.path("answered_at").isNull()) {
      if (!answer.equals(Json.MAPPER.readTree(saved.path("answer").asText())))
        throw ApiException.conflict("这道提醒已经回答");
      return Json.MAPPER.createObjectNode().put("duplicate", true);
    }
    if (!clock.instant().isBefore(java.time.Instant.parse(saved.path("expires_at").asText())))
      throw ApiException.conflict("提醒已过期，未扣分；请等待新题或打开练习中心");
    ObjectNode question = Json.object(Json.MAPPER.readTree(saved.path("payload").asText()));
    ObjectNode request =
        Json.MAPPER
            .createObjectNode()
            .put("action", "practiceRecord")
            .put("wordId", saved.path("word_id").asText())
            .put("mode", saved.path("mode").asText())
            .put("submissionId", id)
            .put("attemptId", id)
            .put("origin", "notification");
    for (String key : new String[] {"answer", "signal", "choiceId"})
      if (input.has(key)) request.set(key, input.get(key));
    boolean correct = protocolWorkspace.localFeedback(db, request).path("correct").asBoolean();
    Sql.execute(
        db,
        "UPDATE desktop_reminder_questions SET answered_at=?,answer=? WHERE id=?",
        clock.instant().toString(),
        answer.toString(),
        id);
    return Json.MAPPER
        .createObjectNode()
        .put("correct", correct)
        .set(
            "familiarity",
            DesktopFamiliarity.assess(db, saved.path("word_id").asText(), clock.instant()));
  }

  private ObjectNode unavailable(String reason) {
    return Json.MAPPER.createObjectNode().put("unavailable", reason);
  }
}
