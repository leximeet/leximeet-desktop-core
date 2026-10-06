package app.leximeet.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Connection;

// 本机页面只保存显示缓存；词序、输入草稿、题目和完成事实由桌面 Core 持有。
final class DesktopPracticeCheckpoint {
  private final DesktopDataModel workspace;
  private final DesktopPracticeSessions practice;

  DesktopPracticeCheckpoint(DesktopDataModel workspace, DesktopPracticeSessions practice) {
    this.workspace = workspace;
    this.practice = practice;
  }

  ObjectNode session(Connection db, String scope) throws Exception {
    var row = Sql.first(db, "SELECT session_id FROM desktop_practice_scopes WHERE scope=?", scope);
    if (row != null) {
      var cached = practice.session(db, practice.localCaller(db), row.path("session_id").asText());
      boolean changedTarget =
          scope.equals("library")
              && !cached.path("localGoal").asText("").equals(DesktopWords.goal(db));
      boolean wasEmpty = cached.path("total").asInt() == 0 && hasCandidates(db, scope);
      if (!changedTarget && !wasEmpty) return cached;
      Sql.execute(db, "DELETE FROM desktop_practice_scopes WHERE scope=?", scope);
    }
    String kind =
        scope.startsWith("today:")
            ? "today"
            : scope.startsWith("goal:")
                ? "target"
                : scope.startsWith("book:") ? "notebook" : "manual";
    var range = Json.MAPPER.createObjectNode().put("kind", kind).putNull("notebookId");
    if (kind.equals("notebook")) range.put("notebookId", scope.substring(5));
    // 我的词库包含当前目标及额外采集词；原始队列只在开始一轮时固定一次。
    boolean emptyGoal = scope.equals("goal:none");
    boolean wholeDictionary = scope.equals("dictionary");
    if (emptyGoal || wholeDictionary) range.put("kind", "manual");
    boolean library = scope.equals("library") && !DesktopWords.goal(db).isEmpty();
    if (library) range.put("kind", "target");
    var state =
        practice.start(
            db, practice.localCaller(db), Json.MAPPER.createObjectNode().set("scope", range));
    String id = state.path("sessionId").asText();
    if (emptyGoal || wholeDictionary) {
      Sql.execute(db, "DELETE FROM desktop_practice_items WHERE session_id=?", id);
      int position = 0;
      if (wholeDictionary) {
        workspace.lexicon.require();
        position = practice.freezeDictionaryQueue(db, id, "1=1", "e.position");
      }
      state.put("total", position);
      Sql.execute(
          db,
          "UPDATE desktop_practice_sessions SET payload=? WHERE session_id=?",
          state.toString(),
          id);
    }
    if (library) {
      int position = state.path("total").asInt();
      for (JsonNode word :
          Sql.rows(
              db,
              "SELECT w.id FROM words w LEFT JOIN desktop_word_links l ON l.word_id=w.id WHERE"
                  + " w.deleted_at IS NULL AND COALESCE(l.manual_active,1)=1 AND NOT EXISTS(SELECT"
                  + " 1 FROM desktop_practice_items i WHERE i.session_id=? AND i.word_id=w.id)"
                  + " ORDER BY w.created_at,w.id",
              id))
        practice.addItem(
            db,
            id,
            position++,
            workspace.reference(
                db, workspace.desktop.words().detail(db, word.path("id").asText())));
      state.put("total", position);
      Sql.execute(
          db,
          "UPDATE desktop_practice_sessions SET payload=? WHERE session_id=?",
          state.toString(),
          id);
    }
    state.put("localGoal", DesktopWords.goal(db));
    Sql.execute(
        db,
        "UPDATE desktop_practice_sessions SET payload=? WHERE session_id=?",
        state.toString(),
        id);
    Sql.execute(db, "INSERT INTO desktop_practice_scopes VALUES(?,?)", scope, id);
    return state;
  }

  // 六种模式共享词序，但题目、草稿、游标及重新开始的 attempt 空间互不覆盖。
  ObjectNode session(Connection db, String scope, String mode) throws Exception {
    var base = session(db, scope);
    String id = base.path("localModes").path(mode).asText("");
    if (!id.isEmpty()) return practice.session(db, practice.localCaller(db), id);
    var state = base.deepCopy().put("localMode", mode).put("mode", mode).put("position", 0);
    state.remove("localModes");
    return newCycle(db, scope, state);
  }

  private boolean hasCandidates(Connection db, String scope) throws Exception {
    if (scope.equals("goal:none")) return false;
    if (scope.startsWith("today:")) return !workspace.desktop.queue(db).path("tasks").isEmpty();
    if (scope.startsWith("book:"))
      return Sql.first(
              db,
              "SELECT 1 AS n FROM word_books b JOIN words w ON w.id=b.word_id WHERE b.book_id=? AND"
                  + " w.deleted_at IS NULL LIMIT 1",
              scope.substring(5))
          != null;
    String goal = DesktopWords.goal(db);
    if (workspace.lexicon.mounted()
        && (scope.equals("dictionary") || !goal.isEmpty())
        && Sql.first(
                db,
                "SELECT 1 AS n FROM lexicon.entries e WHERE "
                    + (scope.equals("dictionary") ? "1=1" : DesktopWords.predicate(goal))
                    + " AND "
                    + DesktopWords.activeEntry("e")
                    + " LIMIT 1")
            != null) return true;
    return scope.equals("library")
        && Sql.first(
                db,
                "SELECT 1 AS n FROM words w LEFT JOIN desktop_word_links l ON l.word_id=w.id WHERE"
                    + " w.deleted_at IS NULL AND COALESCE(l.manual_active,1)=1 LIMIT 1")
            != null;
  }

  // 本机模式只引用同一份词序，避免大型词书在六个模式/多次重开后重复占用空间。
  static String queueId(JsonNode state) {
    return state.path("localQueueId").asText(state.path("sessionId").asText());
  }

  // 再开始一轮创建新的 attempt 空间，旧题和事件保留，不复制冻结词序。
  ObjectNode newCycle(Connection db, String scope, ObjectNode previous) throws Exception {
    String id = LmcpService.uuid();
    var state =
        previous
            .deepCopy()
            .put("sessionId", id)
            .put("revision", "1")
            .put("draft", "")
            .put("localQueueId", queueId(previous));
    Sql.execute(
        db,
        "INSERT INTO desktop_practice_sessions VALUES(?,?,?,?)",
        id,
        LmcpService.meta(db, "desktopInstanceId"),
        state.toString(),
        workspace.service.businessNow());
    String mode = previous.path("localMode").asText();
    var base = session(db, scope);
    base.withObject("localModes").put(mode, id);
    Sql.execute(
        db,
        "UPDATE desktop_practice_sessions SET payload=? WHERE session_id=?",
        base.toString(),
        base.path("sessionId").asText());
    return state;
  }

  // 重开只刷新当前模式的范围；其他模式继续引用原词序和断点。
  private void refreshScope(Connection db, String scope, String mode) throws Exception {
    var previous = session(db, scope);
    Sql.execute(db, "DELETE FROM desktop_practice_scopes WHERE scope=?", scope);
    var fresh = session(db, scope);
    String oldId = previous.path("sessionId").asText(), newId = fresh.path("sessionId").asText();
    boolean same =
        previous.path("total").asInt() == fresh.path("total").asInt()
            && Sql.first(
                    db,
                    "SELECT 1 FROM desktop_practice_items n LEFT JOIN desktop_practice_items o"
                        + " ON o.session_id=? AND o.position=n.position WHERE n.session_id=?"
                        + " AND (o.word_id IS NULL OR o.word_id<>n.word_id) LIMIT 1",
                    oldId,
                    newId)
                == null;
    var base = same ? previous : fresh;
    // 相同 ID 序列复用原 base；不同序列保留旧 base 给其他模式，不复制它们的进度。
    if (same) {
      Sql.execute(
          db, "UPDATE desktop_practice_scopes SET session_id=? WHERE scope=?", oldId, scope);
      Sql.execute(db, "DELETE FROM desktop_practice_items WHERE session_id=?", newId);
      Sql.execute(db, "DELETE FROM desktop_practice_sessions WHERE session_id=?", newId);
    }
    var modes =
        previous.path("localModes").isObject()
            ? ((ObjectNode) previous.path("localModes")).deepCopy()
            : Json.MAPPER.createObjectNode();
    modes.remove(mode);
    base.set("localModes", modes);
    base.put("localLastMode", previous.path("localLastMode").asText("word-list"));
    Sql.execute(
        db,
        "UPDATE desktop_practice_sessions SET payload=? WHERE session_id=?",
        base.toString(),
        base.path("sessionId").asText());
  }

  ObjectNode load(Connection db, JsonNode input) throws Exception {
    Json.fields(input, "kind", "scope", "wordId", "mode", "reset");
    String scope = Json.text(input, "scope", 140, true);
    if (input.has("reset") && !input.path("reset").isBoolean())
      throw ApiException.badRequest("reset 必须为布尔值");
    String mode =
        input.has("mode")
            ? DesktopPractice.mode(input)
            : session(db, scope).path("localLastMode").asText("word-list");
    if (input.path("reset").asBoolean()) refreshScope(db, scope, mode);
    var state = session(db, scope, mode);
    var base = session(db, scope);
    var result =
        Json.MAPPER
            .createObjectNode()
            .put("scope", scope)
            .put("sessionId", state.path("sessionId").asText())
            .put("cursor", state.path("position").asInt())
            .put("mode", mode)
            .put("lastMode", base.path("localLastMode").asText("word-list"));
    var range = result.putArray("rangeIds");
    if (scope.startsWith("today:"))
      for (JsonNode row :
          Sql.rows(
              db,
              "SELECT word_id FROM desktop_practice_items WHERE session_id=? ORDER BY position",
              queueId(state))) range.add(row.path("word_id").asText());
    ObjectNode draft = Json.MAPPER.createObjectNode();
    if (input.has("wordId")) {
      var item =
          Sql.first(
              db,
              "SELECT position FROM desktop_practice_items WHERE session_id=? AND word_id=?",
              queueId(state),
              workspace
                  .desktop
                  .words()
                  .detail(db, input.path("wordId").asText())
                  .path("id")
                  .asText());
      if (item != null) {
        var cache =
            Sql.first(
                db,
                "SELECT payload FROM desktop_practice_drafts WHERE session_id=? AND position=? AND"
                    + " mode=?",
                state.path("sessionId").asText(),
                item.path("position").asInt(),
                mode);
        if (cache != null)
          draft = Json.object(Json.MAPPER.readTree(cache.path("payload").asText()));
        var text =
            Sql.first(
                db,
                "SELECT text FROM desktop_question_drafts WHERE session_id=? AND position=? AND"
                    + " mode=?",
                state.path("sessionId").asText(),
                item.path("position").asInt(),
                mode);
        if (text != null) draft.put("input", text.path("text").asText());
        // 这些显示字段只从已冻结题目和已提交事件恢复，忽略页面保存的自报值。
        var question =
            Sql.first(
                db,
                "SELECT question_id,assisted FROM desktop_questions WHERE session_id=? AND"
                    + " position=? AND mode=?",
                state.path("sessionId").asText(),
                item.path("position").asInt(),
                mode);
        draft.put("assisted", question != null && question.path("assisted").asBoolean());
        if (question != null) {
          var fact =
              Sql.first(
                  db,
                  "SELECT correct FROM desktop_practice_facts WHERE attempt_id=(SELECT"
                      + " json_extract(payload,'$.attemptId') FROM desktop_questions WHERE"
                      + " question_id=?) AND undone_at IS NULL ORDER BY"
                      + " length(logical_clock) DESC,logical_clock DESC LIMIT 1",
                  question.path("question_id").asText());
          if (fact != null) draft.put("answered", fact.path("correct").asBoolean());
          else draft.putNull("answered");
        } else draft.putNull("answered");
      }
    }
    return result.set("draft", draft);
  }

  void save(Connection db, JsonNode input) throws Exception {
    Json.fields(input, "action", "scope", "cursor", "mode", "wordId", "draft", "rangeIds");
    String scope = Json.text(input, "scope", 140, true);
    String mode = DesktopPractice.mode(input);
    var state = session(db, scope, mode);
    int position = DesktopWords.integer(input, "cursor", 0, 0, 1000000);
    if (position > state.path("total").asInt()) throw ApiException.badRequest("练习游标超出冻结队列");
    String text = "";
    if (input.has("wordId")) {
      var detail = workspace.desktop.words().detail(db, input.path("wordId").asText());
      var item =
          Sql.first(
              db,
              "SELECT position FROM desktop_practice_items WHERE session_id=? AND word_id=?",
              queueId(state),
              detail.path("id").asText());
      if (item == null) throw ApiException.badRequest("单词不属于本轮冻结队列");
      var draft = Json.object(input.path("draft")).deepCopy();
      if (draft.toString().length() > 5000) throw ApiException.badRequest("显示草稿过大");
      text = draft.path("input").asText("");
      if (text.length() > 4000) throw ApiException.badRequest("输入草稿过大");
      draft.remove(java.util.List.of("answered", "assisted"));
      Sql.execute(
          db,
          "INSERT INTO desktop_practice_drafts VALUES(?,?,?,?) ON"
              + " CONFLICT(session_id,position,mode) DO UPDATE SET payload=excluded.payload",
          state.path("sessionId").asText(),
          item.path("position").asInt(),
          mode,
          draft.toString());
      Sql.execute(
          db,
          "INSERT INTO desktop_question_drafts VALUES(?,?,?,?) ON"
              + " CONFLICT(session_id,position,mode) DO UPDATE SET text=excluded.text",
          state.path("sessionId").asText(),
          item.path("position").asInt(),
          mode,
          text);
    }
    state
        .put("position", position)
        .put("mode", mode)
        .put("draft", text)
        .put("revision", DesktopDataModel.nextRevision(state.path("revision").asText()));
    Sql.execute(
        db,
        "UPDATE desktop_practice_sessions SET payload=?,updated_at=? WHERE session_id=?",
        state.toString(),
        workspace.service.businessNow(),
        state.path("sessionId").asText());
    var base = session(db, scope);
    base.put("localLastMode", mode);
    Sql.execute(
        db,
        "UPDATE desktop_practice_sessions SET payload=? WHERE session_id=?",
        base.toString(),
        base.path("sessionId").asText());
    LmcpService.bump(db, "draftRevision");
    workspace.change(db, null, null, "practice", "drafts");
  }

  ObjectNode words(Connection db, JsonNode input) throws Exception {
    Json.fields(input, "kind", "scope", "offset", "limit", "mode");
    String scope = Json.text(input, "scope", 140, true);
    var state =
        input.has("mode") ? session(db, scope, DesktopPractice.mode(input)) : session(db, scope);
    int offset = DesktopWords.integer(input, "offset", 0, 0, 1000000),
        limit = DesktopWords.integer(input, "limit", 40, 1, 100);
    var result = Json.MAPPER.createObjectNode().put("total", state.path("total").asInt());
    var items = result.putArray("words");
    for (JsonNode item :
        Sql.rows(
            db,
            "SELECT word_id FROM desktop_practice_items WHERE session_id=? AND position>=? ORDER BY"
                + " position LIMIT ?",
            queueId(state),
            offset,
            limit)) items.add(workspace.desktop.words().detail(db, item.path("word_id").asText()));
    return result;
  }
}
