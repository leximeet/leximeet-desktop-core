package app.leximeet.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Connection;
import java.util.LinkedHashSet;

// 剪贴板只补充当前目标的语境；目标成员判断集中在 Core，不把整本词书传给宿主。
final class DesktopClipboard {
  private DesktopClipboard() {}

  static ObjectNode candidates(
      Connection db, JsonNode input, PublicLexicon lexicon, java.time.Clock clock)
      throws Exception {
    Json.fields(input, "kind", "words", "text");
    String context = input.has("text") ? Json.text(input, "text", 20000, true) : null;
    JsonNode values = input.path("words");
    if (!values.isArray() || values.size() > 200)
      throw ApiException.badRequest("剪贴板候选应为最多 200 个单词");
    var tokens = new LinkedHashSet<String>();
    for (JsonNode value : values) {
      if (!value.isTextual()
          || value.asText().length() > 120
          || !value.asText().matches("[A-Za-z]+(?:['’\\-][A-Za-z]+)*"))
        throw ApiException.badRequest("剪贴板候选必须是英文单词");
      tokens.add(PublicLexicon.normalize(value.asText().replace('’', '\'')));
    }
    String goal = DesktopWords.goal(db);
    var result = Json.MAPPER.createObjectNode().put("goal", goal);
    var matches = result.putArray("matches");
    var policy = CapturePolicy.preferences(db);
    result.set("capturePolicy", policy.json());
    // 没有目标时静默，不把个人词库或整个词典当成隐式目标。
    if (goal.isEmpty() || !lexicon.metadata(db).path("ready").asBoolean()) return result;
    for (String token : tokens) {
      var entry =
          Sql.first(
              db,
              "SELECT e.id AS wordId,e.headword AS word FROM lexicon.entries e WHERE e.normalized=?"
                  + " AND "
                  + eligible(goal)
                  + " ORDER BY CASE WHEN e.headword=? THEN 0 ELSE 1 END,e.position,e.id LIMIT 1",
              token,
              token);
      if (entry != null) {
        if (context != null) {
          String head = entry.path("word").asText();
          CapturePolicy.Segment safe;
          try {
            safe = CapturePolicy.automaticSegment(context, head, policy);
            if (safe == null) continue;
          } catch (ApiException rejected) {
            if (rejected.code().equals("SENSITIVE_SELECTION")) continue;
            throw rejected;
          }
          var linked =
              Sql.first(
                  db,
                  "SELECT word_id FROM desktop_word_links WHERE entry_id=? ORDER BY word_id LIMIT"
                      + " 1",
                  entry.path("wordId").asText());
          String wordId =
              linked == null ? entry.path("wordId").asText() : linked.path("word_id").asText();
          if (CapturePolicy.duplicate(db, wordId, safe.text(), policy, clock.instant()) != null)
            continue;
          entry.put("context", safe.text());
        }
        matches.add(entry);
      }
    }
    return result;
  }

  record Outcome(ObjectNode result, boolean replayed) {}

  // 原操作先恢复回执；新操作重新判断开关、目标和成员关系，不留下迟到记录。
  static Outcome capture(Connection db, JsonNode input, PublicLexicon lexicon, DesktopWords words)
      throws Exception {
    Json.fields(input, "action", "goal", "wordId", "context", "operationId");
    String requestedGoal = Json.text(input, "goal", 100, true);
    String entryId = Json.id(input, "wordId", true);
    String context = Json.text(input, "context", 20000, true);
    String operation = input.has("operationId") ? Json.text(input, "operationId", 36, true) : "";
    if (!operation.isEmpty() && !operation.matches("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}"))
      throw ApiException.badRequest("operationId 必须是规范 UUID");
    // 操作重试与七天语境查重是两件事：即使关闭查重，也只能执行原操作一次。
    // 复用已有安全回执表，私有命名空间不占用公开 LMCP eventId；原请求只保存摘要。
    String receiptKey = "desktopClipboard:" + operation;
    String fingerprint = operation.isEmpty() ? "" : LmcpContract.digest(input);
    if (!operation.isEmpty()) {
      var receipt = Sql.first(db, "SELECT * FROM lmcp_capture_events WHERE event_id=?", receiptKey);
      if (receipt != null) {
        if (!receipt.path("input_hash").asText().equals(fingerprint))
          throw new ApiException(409, "IDEMPOTENCY_KEY_REUSED", "采集操作标识已用于不同内容，请恢复原操作");
        var saved = Json.MAPPER.readTree(receipt.path("result").asText()).path("captureResult");
        return new Outcome(saved.isNull() ? null : Json.object(saved), true);
      }
    }
    ObjectNode captured = captureNew(db, requestedGoal, entryId, context, lexicon, words);
    if (!operation.isEmpty()) {
      var receipt = Json.MAPPER.createObjectNode();
      // 只保存政策处理后的结果；开关/目标复核未通过也记住原操作的明确撤回结果。
      if (captured == null) receipt.putNull("captureResult");
      else receipt.set("captureResult", captured);
      Sql.execute(
          db,
          "INSERT INTO lmcp_capture_events VALUES(?,?,?)",
          receiptKey,
          fingerprint,
          receipt.toString());
    }
    return new Outcome(captured, false);
  }

  private static ObjectNode captureNew(
      Connection db,
      String requestedGoal,
      String entryId,
      String context,
      PublicLexicon lexicon,
      DesktopWords words)
      throws Exception {
    var settings =
        Json.MAPPER.readTree(
            Sql.first(db, "SELECT payload FROM settings WHERE id=1").path("payload").asText());
    String goal = DesktopWords.goal(db);
    if (!settings.path("clipboardCaptureEnabled").asBoolean()
        || goal.isEmpty()
        || !goal.equals(requestedGoal)
        || !lexicon.metadata(db).path("ready").asBoolean()) return null;
    if (Sql.first(
            db, "SELECT e.id FROM lexicon.entries e WHERE e.id=? AND " + eligible(goal), entryId)
        == null) return null;
    // 目标语境不会把目标词改成手动扩充词；已有手动收藏关系仍保留，语境独立长期保存。
    String wordId = words.materialize(db, entryId, false);
    return words.saveEncounter(
        db,
        wordId,
        Json.MAPPER.createObjectNode().put("context", context).put("sourceTitle", "系统剪贴板"));
  }

  private static String eligible(String goal) {
    return DesktopWords.predicate(goal)
        + " AND NOT EXISTS(SELECT 1 FROM words w WHERE w.deleted_at IS NOT NULL AND ("
        + DesktopWords.personalJoin("e")
        + "))";
  }
}
