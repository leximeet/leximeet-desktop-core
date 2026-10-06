package app.leximeet.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

// 一轮固定词序、每模式固定题目、服务器判题。同一题的提示和订正不能制造第二次得分。
final class DesktopPracticeSessions {
  private final DesktopDataModel workspace;
  private final LmcpService service;

  DesktopPracticeSessions(DesktopDataModel workspace) {
    this.workspace = workspace;
    service = workspace.service;
  }

  LmcpService.Caller localCaller(Connection db) throws Exception {
    String owner = LmcpService.meta(db, "desktopInstanceId");
    return new LmcpService.Caller(
        owner,
        owner,
        "local",
        owner,
        Json.MAPPER.createObjectNode().put("mode", "connected"),
        "9999-12-31T23:59:59.999Z");
  }

  // 本机 UI 和系统通知也使用同一冻结题表；本机 DTO 仅是展示适配，不接收自报正误/提示。
  ObjectNode localQuestion(Connection db, JsonNode input) throws Exception {
    var checkpoint = db.setSavepoint();
    try {
      var result = issueLocalQuestion(db, input);
      db.releaseSavepoint(checkpoint);
      return result.put("unavailable", false);
    } catch (ApiException failure) {
      if (!failure.code().equals("EXERCISE_UNAVAILABLE")) throw failure;
      db.rollback(checkpoint);
      db.releaseSavepoint(checkpoint);
      var detail = workspace.desktop.words().detail(db, Json.id(input, "wordId", true));
      var result =
          Json.MAPPER
              .createObjectNode()
              .put("unavailable", true)
              .put("reason", failure.getMessage())
              .putNull("questionId")
              .put("wordId", input.path("wordId").asText())
              .put("word", detail.path("word").asText())
              .put("meaning", detail.path("meaning").asText())
              .put("mode", DesktopPractice.mode(input))
              .put(
                  "attemptId",
                  input.path(input.has("attemptId") ? "attemptId" : "submissionId").asText());
      result.putArray("options");
      return result;
    }
  }

  private ObjectNode issueLocalQuestion(Connection db, JsonNode input) throws Exception {
    Json.fields(
        input,
        "kind",
        "action",
        "wordId",
        "mode",
        "attemptId",
        "submissionId",
        "answer",
        "signal",
        "choiceId",
        "assisted",
        "origin",
        "questionId",
        "scope",
        "cursor");
    String requested = Json.id(input, "wordId", true), mode = DesktopPractice.mode(input);
    requireActive(db, requested);
    String seed = Json.id(input, input.has("attemptId") ? "attemptId" : "submissionId", true);
    var mapped =
        Sql.first(db, "SELECT question_id FROM desktop_attempt_questions WHERE attempt_id=?", seed);
    JsonNode question;
    if (mapped != null) {
      var stored =
          Sql.first(
              db,
              "SELECT * FROM desktop_questions WHERE question_id=?",
              mapped.path("question_id").asText());
      question = publicQuestion(stored);
      if (!question.path("mode").asText().equals(mode)
          || !workspace
              .desktop
              .words()
              .detail(db, requested)
              .path("id")
              .asText()
              .equals(DesktopDataModel.localWordId(db, question.path("word"))))
        throw LmcpService.error("IDEMPOTENCY_CONFLICT", "同一练习不能更换词条或模式");
    } else {
      JsonNode detail = workspace.desktop.words().detail(db, requested),
          ref = workspace.reference(db, detail);
      ObjectNode localState =
          input.has("scope")
              ? workspace.localPractice.session(db, input.path("scope").asText(), mode)
              : null;
      if (localState != null) {
        var concluded =
            Sql.first(
                db,
                "SELECT q.question_id FROM desktop_questions q JOIN desktop_practice_items i ON"
                    + " i.session_id=? AND i.position=q.position JOIN"
                    + " desktop_practice_facts f ON"
                    + " f.attempt_id=json_extract(q.payload,'$.attemptId') WHERE q.session_id=? AND"
                    + " i.word_id=? AND q.mode=? AND f.undone_at IS NULL LIMIT 1",
                DesktopPracticeCheckpoint.queueId(localState),
                localState.path("sessionId").asText(),
                detail.path("id").asText(),
                mode);
        if (concluded != null)
          localState =
              workspace.localPractice.newCycle(db, input.path("scope").asText(), localState);
      }
      String id = localState != null ? localState.path("sessionId").asText() : LmcpService.uuid();
      int position = 0;
      if (input.has("scope")) {
        var item =
            Sql.first(
                db,
                "SELECT position FROM desktop_practice_items WHERE session_id=? AND word_id=?",
                DesktopPracticeCheckpoint.queueId(localState),
                detail.path("id").asText());
        if (item == null) throw LmcpService.error("STALE_QUEUE", "单词不属于本轮冻结队列，请重新开始练习");
        position = item.path("position").asInt();
      }
      var checkpoint =
          Json.MAPPER
              .createObjectNode()
              .put("sessionId", id)
              .put("mode", mode)
              .put("position", 0)
              .put("revision", "1")
              .put("draft", "")
              .put("queueRevision", LmcpService.meta(db, "revision"))
              .put("repeatCount", 1)
              .put("total", 1);
      checkpoint.set(
          "scope", Json.MAPPER.createObjectNode().put("kind", "manual").putNull("notebookId"));
      if (!input.has("scope")) {
        Sql.execute(
            db,
            "INSERT INTO desktop_practice_sessions VALUES(?,?,?,?)",
            id,
            LmcpService.meta(db, "desktopInstanceId"),
            checkpoint.toString(),
            service.businessNow());
        addItem(db, id, 0, ref);
      } else checkpoint = workspace.localPractice.session(db, input.path("scope").asText(), mode);
      question =
          question(
              db,
              localCaller(db),
              Json.MAPPER
                  .createObjectNode()
                  .put("sessionId", id)
                  .put("position", position)
                  .put("mode", mode)
                  .put("expectedQueueRevision", checkpoint.path("queueRevision").asText()));
      Sql.execute(
          db,
          "INSERT INTO desktop_attempt_questions VALUES(?,?)",
          seed,
          question.path("questionId").asText());
    }
    var stored =
        Sql.first(
            db,
            "SELECT answer,correct_choice,payload FROM desktop_questions WHERE question_id=?",
            question.path("questionId").asText());
    var out =
        Json.object(question)
            .deepCopy()
            .put("attemptId", seed)
            .put("wordId", requested)
            .put("word", stored.path("answer").asText());
    out.put(
        "meaning",
        Json.MAPPER.readTree(stored.path("payload").asText()).path("_displayMeaning").asText());
    var options = out.putArray("options");
    for (JsonNode choice : question.path("choices"))
      options
          .addObject()
          .put("id", choice.path("choiceId").asText())
          .put("text", choice.path("text").asText());
    if (question.path("answerVisible").asBoolean()
        && !mode.equals("copy")
        && !stored.path("correct_choice").isNull())
      out.put("revealedChoiceId", stored.path("correct_choice").asText());
    if (mode.equals("cloze")) out.put("context", question.path("prompt").asText());
    return out;
  }

  ObjectNode localFeedback(Connection db, JsonNode input) throws Exception {
    ObjectNode ui = localQuestion(db, input);
    if (ui.path("unavailable").asBoolean())
      throw LmcpService.error("EXERCISE_UNAVAILABLE", ui.path("reason").asText());
    var saved =
        Sql.first(
            db,
            "SELECT * FROM desktop_questions WHERE question_id=?",
            ui.path("questionId").asText());
    ObjectNode feedback = Json.MAPPER.createObjectNode();
    String mode = ui.path("mode").asText();
    if (input.path("signal").asText().equals("reveal") || mode.equals("word-list"))
      feedback.put("kind", "signal").put("signal", input.path("signal").asText());
    else if (mode.equals("meaning-choice"))
      feedback.put("kind", "choice").put("choiceId", input.path("choiceId").asText());
    else feedback.put("kind", "answer").put("answer", input.path("answer").asText());
    var p =
        Json.MAPPER
            .createObjectNode()
            .put("questionId", ui.path("questionId").asText())
            .put("submissionId", Json.id(input, "submissionId", true))
            .put("durationMs", 0)
            .put("origin", input.path("origin").asText("practice"));
    p.set("input", feedback);
    return feedback(db, localCaller(db), p);
  }

  ObjectNode start(Connection db, LmcpService.Caller caller, JsonNode p) throws Exception {
    JsonNode scope = p.path("scope");
    String kind = scope.path("kind").asText();
    if (kind.equals("notebook") && scope.path("notebookId").isNull()
        || !kind.equals("notebook") && !scope.path("notebookId").isNull())
      throw LmcpService.error("INVALID_ARGUMENT", "练习范围与单词本不匹配");
    if (p.path("replaceSessionId").isTextual()) {
      String old = p.path("replaceSessionId").asText();
      session(db, caller, old);
      for (String table :
          List.of(
              "desktop_questions",
              "desktop_question_drafts",
              "desktop_practice_items",
              "desktop_practice_sessions"))
        Sql.execute(db, "DELETE FROM " + table + " WHERE session_id=?", old);
    }
    String id = LmcpService.uuid();
    var prefs = workspace.preferences(db).path("preferences");
    var checkpoint =
        Json.MAPPER
            .createObjectNode()
            .put("sessionId", id)
            .put("mode", prefs.path("defaultMode").asText())
            .put("position", 0)
            .put("revision", "1")
            .put("draft", "")
            .put("queueRevision", LmcpService.meta(db, "revision"))
            .put("repeatCount", prefs.path("repeatCount").asInt(1));
    checkpoint.set("scope", scope);
    Sql.execute(
        db,
        "INSERT INTO desktop_practice_sessions VALUES(?,?,?,?)",
        id,
        caller.pairingId(),
        checkpoint.toString(),
        service.businessNow());
    if (kind.equals("today")) {
      int pos = 0;
      for (JsonNode item : workspace.desktop.queue(db).path("tasks")) {
        JsonNode detail = workspace.desktop.words().detail(db, item.path("id").asText());
        JsonNode ref = workspace.reference(db, detail);
        addItem(db, id, pos++, ref);
      }
    } else if (kind.equals("target")) {
      workspace.lexicon.require();
      String goal = DesktopWords.goal(db);
      if (goal.isEmpty()) throw LmcpService.error("NO_LEARNING_TARGET", "尚未设置学习目标");
      freezeDictionaryQueue(db, id, DesktopWords.predicate(goal), DesktopWords.order(goal));
    } else {
      String sql =
          "SELECT w.id,w.word,l.entry_id FROM words w LEFT JOIN desktop_word_links l ON"
              + " l.word_id=w.id WHERE w.deleted_at IS NULL AND COALESCE(l.manual_active,1)=1";
      if (kind.equals("notebook")) {
        if (workspace.entity(db, "notebook", scope.path("notebookId").asText()) == null
            || !workspace
                .entity(db, "notebook", scope.path("notebookId").asText())
                .path("deletedAt")
                .isNull()) throw LmcpService.error("NOT_FOUND", "单词本不存在");
        sql += " AND EXISTS(SELECT 1 FROM word_books b WHERE b.word_id=w.id AND b.book_id=?)";
      }
      sql += " ORDER BY w.created_at,w.id";
      int position = 0;
      for (JsonNode row :
          Sql.rows(
              db,
              sql,
              kind.equals("notebook")
                  ? new Object[] {scope.path("notebookId").asText()}
                  : new Object[0]))
        addItem(
            db,
            id,
            position++,
            DesktopDataModel.wordRef(
                row.path("entry_id").asText(""),
                row.path("id").asText(),
                row.path("word").asText(),
                workspace.release(db)));
    }
    checkpoint.put("total", itemCount(db, id));
    storeSession(db, id, checkpoint);
    workspace.change(db, null, null, "practice");
    return checkpoint;
  }

  void addItem(Connection db, String session, int position, JsonNode word) throws Exception {
    Sql.execute(
        db,
        "INSERT INTO desktop_practice_items VALUES(?,?,?,?)",
        session,
        position,
        DesktopDataModel.localWordId(db, word),
        word.toString());
  }

  // 大型词书在同一事务内一次冻结，避免逐词准备 SQL、查个人关联和复制词典元数据。
  // 谓词和排序只来自本机领域代码；会话 ID 与发布版本仍通过参数绑定。
  int freezeDictionaryQueue(Connection db, String session, String predicate, String order)
      throws Exception {
    String where = predicate + " AND " + DesktopWords.activeEntry("e");
    int total =
        Sql.first(db, "SELECT COUNT(*) AS n FROM lexicon.entries e WHERE " + where)
            .path("n")
            .asInt();
    if (total > 1_000_000) throw LmcpService.error("LIMIT_EXCEEDED", "练习词数超过上限");
    Sql.execute(
        db,
        "INSERT INTO desktop_practice_items(session_id,position,word_id,word_ref) SELECT ?,"
            + "ROW_NUMBER() OVER (ORDER BY "
            + order
            + ",e.id)-1,COALESCE(l.word_id,e.id),"
            + "json_object('kind','dictionary','entryId',e.id,'release',?,"
            + "'entrySchema','leximeet.entry.v2') FROM lexicon.entries e LEFT JOIN"
            + " (SELECT entry_id,MIN(word_id) AS word_id FROM desktop_word_links"
            + " WHERE entry_id IS NOT NULL GROUP BY entry_id) l ON l.entry_id=e.id WHERE "
            + where,
        session,
        workspace.release(db));
    return total;
  }

  private int itemCount(Connection db, String id) throws Exception {
    return Sql.first(db, "SELECT COUNT(*) AS n FROM desktop_practice_items WHERE session_id=?", id)
        .path("n")
        .asInt();
  }

  ObjectNode session(Connection db, LmcpService.Caller caller, String id) throws Exception {
    var row =
        Sql.first(
            db,
            "SELECT * FROM desktop_practice_sessions WHERE session_id=? AND owner_id=?",
            id,
            caller.pairingId());
    if (row == null) throw LmcpService.error("NOT_FOUND", "练习会话不属于当前客户端");
    return Json.object(Json.MAPPER.readTree(row.path("payload").asText()));
  }

  private void storeSession(Connection db, String id, JsonNode data) throws Exception {
    Sql.execute(
        db,
        "UPDATE desktop_practice_sessions SET payload=?,updated_at=? WHERE session_id=?",
        data.toString(),
        service.businessNow(),
        id);
  }

  private ObjectNode question(Connection db, LmcpService.Caller caller, JsonNode p)
      throws Exception {
    String id = p.path("sessionId").asText();
    var state = session(db, caller, id);
    if (!state.path("queueRevision").asText().equals(p.path("expectedQueueRevision").asText()))
      throw LmcpService.error("REVISION_CONFLICT", "练习冻结队列已改变");
    int position = p.path("position").asInt();
    String mode = p.path("mode").asText();
    var active =
        Sql.first(
            db,
            "SELECT word_id FROM desktop_practice_items WHERE session_id=? AND position=?",
            DesktopPracticeCheckpoint.queueId(state),
            position);
    if (active != null) requireActive(db, active.path("word_id").asText());
    var stored =
        Sql.first(
            db,
            "SELECT * FROM desktop_questions WHERE session_id=? AND position=? AND mode=?",
            id,
            position,
            mode);
    if (stored != null) return publicQuestion(stored);
    var item =
        Sql.first(
            db,
            "SELECT * FROM desktop_practice_items WHERE session_id=? AND position=?",
            DesktopPracticeCheckpoint.queueId(state),
            position);
    if (item == null) throw LmcpService.error("NOT_FOUND", "练习位置不存在");
    JsonNode ref = Json.MAPPER.readTree(item.path("word_ref").asText());
    var detail = workspace.desktop.words().detail(db, DesktopDataModel.id(ref));
    String word = detail.path("word").asText(),
        meaning =
            mode.equals("meaning-choice")
                ? DesktopPractice.displayMeaning(detail)
                : detail.path("meaning").asText(),
        prompt = word;
    if (meaning.isBlank() && List.of("meaning-choice", "recall", "cloze").contains(mode))
      throw LmcpService.error("EXERCISE_UNAVAILABLE", "词条没有真实释义，无法生成此练习");
    ObjectNode out =
        Json.MAPPER
            .createObjectNode()
            .put("questionId", LmcpService.uuid())
            .put("attemptId", LmcpService.uuid())
            .put("sessionId", id)
            .put("position", position)
            .put("mode", mode)
            .put("queueRevision", state.path("queueRevision").asText())
            .put("repeatCount", state.path("repeatCount").asInt(1))
            .put("answerVisible", mode.equals("copy"))
            .putNull("expiresAt")
            .put("caseSensitive", false);
    out.set("word", ref);
    var choices = out.putArray("choices");
    String correctChoice = null;
    if (mode.equals("meaning-choice")) {
      var options =
          workspace
              .desktop
              .practice()
              .choices(
                  db,
                  DesktopDataModel.id(ref),
                  out.path("attemptId").asText(),
                  DesktopPracticeCheckpoint.queueId(state))
              .path("options");
      if (options.size() < 2) throw LmcpService.error("EXERCISE_UNAVAILABLE", "没有足够的不同释义用于选项");
      for (JsonNode option : options) {
        String choice = LmcpService.uuid();
        choices
            .addObject()
            .put("choiceId", choice)
            .put(
                "text",
                option
                    .path("text")
                    .asText()
                    .substring(0, Math.min(300, option.path("text").asText().length())));
        if (option.path("id").asText().equals(detail.path("id").asText())) correctChoice = choice;
      }
    }
    if (mode.equals("recall")) prompt = meaning;
    if (mode.equals("listening")) prompt = "";
    if (mode.equals("cloze")) {
      String sentence = realContext(detail, word);
      if (sentence.isBlank()) throw LmcpService.error("EXERCISE_UNAVAILABLE", "没有包含该单词的真实语境");
      prompt =
          sentence.replaceAll("(?i)(?<![A-Za-z])" + Pattern.quote(word) + "(?![A-Za-z])", "_____");
    }
    if (mode.equals("meaning-choice") && correctChoice == null)
      throw LmcpService.error("EXERCISE_UNAVAILABLE", "真实释义选项缺少目标词，请换一种练习方式");
    out.put("prompt", prompt.length() > 4000 ? prompt.substring(0, 4000) : prompt);
    // 展示内容与题目同时冻结，编辑词义不能改变已经发出的题目。
    out.put("_displayMeaning", meaning);
    Sql.execute(
        db,
        "INSERT INTO desktop_questions VALUES(?,?,?,?,?,?,?,0)",
        out.path("questionId").asText(),
        id,
        position,
        mode,
        out.toString(),
        word,
        correctChoice);
    var exposed = out.deepCopy();
    exposed.remove("_displayMeaning");
    return exposed;
  }

  private ObjectNode publicQuestion(JsonNode row) throws Exception {
    var out = Json.object(Json.MAPPER.readTree(row.path("payload").asText()));
    out.remove("_displayMeaning");
    if (row.path("assisted").asBoolean())
      out.put("answerVisible", true).put("revealedAnswer", row.path("answer").asText());
    return out;
  }

  static String realContext(JsonNode detail, String word) {
    var pattern = Pattern.compile("(?i)(?<![A-Za-z])" + Pattern.quote(word) + "(?![A-Za-z])");
    for (JsonNode row : detail.path("encounters")) {
      String text = row.path("context").asText();
      if (text.length() <= 4000 && pattern.matcher(text).find()) return text;
    }
    for (JsonNode sense : detail.path("entry").path("senses"))
      for (JsonNode example : sense.path("examples")) {
        String text = example.path("sentence").asText(example.path("text").asText());
        if (text.length() <= 4000 && pattern.matcher(text).find()) return text;
      }
    return "";
  }

  private ObjectNode feedback(Connection db, LmcpService.Caller caller, JsonNode p)
      throws Exception {
    var row =
        Sql.first(
            db,
            "SELECT * FROM desktop_questions WHERE question_id=?",
            p.path("questionId").asText());
    if (row == null) throw LmcpService.error("NOT_FOUND", "冻结题目不存在");
    session(db, caller, row.path("session_id").asText());
    JsonNode question = Json.MAPPER.readTree(row.path("payload").asText()),
        ref = question.path("word"),
        input = p.path("input");
    requireActive(db, DesktopDataModel.id(ref));
    Json.object(question).remove("_displayMeaning");
    String mode = question.path("mode").asText(),
        kind = input.path("kind").asText(),
        signal = input.path("signal").asText(),
        submission = p.path("submissionId").asText(),
        attempt = question.path("attemptId").asText();
    boolean reveal = kind.equals("signal") && signal.equals("reveal"),
        assisted = reveal || row.path("assisted").asBoolean();
    var original =
        Sql.first(
            db, "SELECT assisted FROM desktop_practice_facts WHERE submission_id=?", submission);
    if (original != null) assisted = original.path("assisted").asBoolean();
    if (mode.equals("word-list") && !kind.equals("signal")
        || !mode.equals("word-list") && kind.equals("signal") && !reveal
        || mode.equals("meaning-choice") && !kind.equals("choice") && !reveal
        || !mode.equals("word-list")
            && !mode.equals("meaning-choice")
            && !kind.equals("answer")
            && !reveal) throw LmcpService.error("INVALID_ARGUMENT", "提交方式与题目模式不同");
    ObjectNode record =
        Json.MAPPER
            .createObjectNode()
            .put("action", "practiceRecord")
            .put("wordId", DesktopDataModel.id(ref))
            .put("mode", mode)
            .put("submissionId", submission)
            .put("attemptId", attempt)
            .put("assisted", assisted)
            .put("origin", p.path("origin").asText("practice"));
    if (reveal || mode.equals("word-list")) record.put("signal", signal);
    else if (mode.equals("meaning-choice")) {
      boolean found = false;
      for (JsonNode option : question.path("choices"))
        if (option.path("choiceId").asText().equals(input.path("choiceId").asText())) found = true;
      if (!found) throw LmcpService.error("INVALID_ARGUMENT", "选项不属于本题");
      record.put(
          "choiceId",
          input.path("choiceId").asText().equals(row.path("correct_choice").asText())
              ? workspace.desktop.words().detail(db, DesktopDataModel.id(ref)).path("id").asText()
              : "incorrect");
    } else record.put("answer", input.path("answer").asText());
    var before = workspace.learning(db, ref, service.businessClock.instant());
    boolean
        duplicate =
            Sql.first(db, "SELECT id FROM desktop_practice_facts WHERE submission_id=?", submission)
                != null,
        closed =
            Sql.first(
                    db,
                    "SELECT id FROM desktop_practice_facts WHERE attempt_id=? AND undone_at IS"
                        + " NULL",
                    attempt)
                != null;
    var frozen =
        Json.MAPPER
            .createObjectNode()
            .put("word", row.path("answer").asText())
            .put("caseSensitive", question.path("caseSensitive").asBoolean());
    var options = frozen.putArray("options");
    options
        .addObject()
        .put(
            "id",
            workspace.desktop.words().detail(db, DesktopDataModel.id(ref)).path("id").asText());
    options.addObject().put("id", "incorrect");
    boolean correct = workspace.desktop.practice().record(db, record, frozen);
    if (reveal)
      Sql.execute(
          db,
          "UPDATE desktop_questions SET assisted=1 WHERE question_id=?",
          p.path("questionId").asText());
    JsonNode fact =
        Sql.first(db, "SELECT * FROM desktop_practice_facts WHERE submission_id=?", submission);
    if (!duplicate) {
      ObjectNode data = eventData(db, caller, question, row, input, p, fact);
      workspace.put(db, DesktopDataModel.record("practice", submission, 1, data, null));
      // 前台与系统通知使用同一 reducer，复制/提前练习也不能形成不同 FSRS 基线。
      workspace.replay.replay(db, DesktopDataModel.localWordId(db, ref));
      LmcpService.bump(db, "learningRevision");
    }
    var after = workspace.learning(db, ref, service.businessClock.instant());
    var result =
        Json.MAPPER
            .createObjectNode()
            .put("submissionId", submission)
            .put("attemptId", attempt)
            .put("effective", !closed && !duplicate)
            .put("duplicate", duplicate)
            .put("delta", after.path("score").asInt() - before.path("score").asInt())
            .put("correct", correct)
            .put("assisted", assisted)
            .put("revealedAnswer", reveal ? row.path("answer").asText() : null)
            .put("workspaceRevision", LmcpService.meta(db, "revision"));
    result.set("learning", after);
    result.set("memory", memory(db, ref));
    return result;
  }

  private void requireActive(Connection db, String wordId) throws Exception {
    if (workspace.desktop.words().detail(db, wordId).path("deletedAt").isTextual())
      throw LmcpService.error("STALE_QUEUE", "单词已移入回收站，请重新开始练习");
  }

  private ObjectNode eventData(
      Connection db,
      LmcpService.Caller caller,
      JsonNode question,
      JsonNode row,
      JsonNode input,
      JsonNode params,
      JsonNode fact)
      throws Exception {
    String initial = service.businessNow();
    var card =
        Sql.first(
            db,
            "SELECT initial_due FROM desktop_cards WHERE word_id=?",
            fact.path("word_id").asText());
    if (card != null) initial = card.path("initial_due").asText();
    var earliest =
        Sql.first(
            db,
            "SELECT MIN(json_extract(payload,'$.initialDueAt')) AS at FROM desktop_entities WHERE"
                + " entity_type='practice' AND (json_extract(payload,'$.word.entryId')=? OR"
                + " json_extract(payload,'$.word.customId')=?)",
            question.path("word").path("entryId").asText(),
            question.path("word").path("customId").asText());
    if (!earliest.path("at").isNull()
        && Instant.parse(earliest.path("at").asText()).isBefore(Instant.parse(initial)))
      initial = earliest.path("at").asText();
    var d =
        Json.MAPPER
            .createObjectNode()
            .put("attemptId", question.path("attemptId").asText())
            .put("questionId", question.path("questionId").asText())
            .put("mode", fact.path("mode").asText())
            .put("signal", fact.path("signal").asText())
            .put("correct", fact.path("correct").asBoolean())
            .put("assisted", fact.path("assisted").asBoolean())
            .put("ruleVersion", LearningPolicy.VERSION)
            .put("algorithmProfileId", "leximeet.fsrs6/1")
            .put("mergeProfileId", "leximeet.learning-sync/1")
            .put("deviceSeq", fact.path("device_seq").asText())
            .put("logicalClock", fact.path("logical_clock").asText())
            .put("timeZone", fact.path("zone").asText())
            .put("studyDay", fact.path("study_day").asText())
            .put(
                "occurredAt",
                LmcpService.timestamp(Instant.parse(fact.path("created_at").asText())))
            .put("initialDueAt", LmcpService.timestamp(Instant.parse(initial)))
            .put("source", fact.path("origin").asText())
            .put("durationMs", params.path("durationMs").asInt());
    d.set("word", question.path("word"));
    d.set(
        "origin",
        Json.MAPPER
            .createObjectNode()
            .put("deviceId", fact.path("device_id").asText())
            .put("clientKind", "desktop"));
    ObjectNode audit =
        Json.object(question)
            .deepCopy()
            .put("expected", row.path("answer").asText())
            .put("correctChoiceId", row.path("correct_choice").asText(null));
    d.set(
        "evidence",
        Json.MAPPER
            .createObjectNode()
            .put("questionDigest", LmcpContract.digest(audit))
            .put("answerDigest", LmcpContract.digest(input))
            .put("judgeVersion", "leximeet.judge/1"));
    return d;
  }

  private ObjectNode memory(Connection db, JsonNode ref) throws Exception {
    JsonNode card =
        Sql.first(
            db,
            "SELECT initial_due,memory FROM desktop_cards WHERE word_id=?",
            DesktopDataModel.localWordId(db, ref));
    ObjectNode out =
        Json.MAPPER
            .createObjectNode()
            .put("profileId", "leximeet.fsrs6/1")
            .put("historyHash", workspace.historyHash(db, ref, service.businessClock.instant()))
            .put("revision", LmcpService.meta(db, "learningRevision"));
    out.set("word", ref);
    if (card == null)
      out.put("state", "new")
          .putNull("step")
          .putNull("stability")
          .putNull("difficulty")
          .put("dueAt", service.businessNow())
          .putNull("lastReviewAt");
    else {
      JsonNode state = Json.MAPPER.readTree(card.path("memory").asText());
      out.put("state", state.path("lastReviewAt").isNull() ? "new" : state.path("state").asText());
      for (String key : List.of("step", "stability", "difficulty")) out.set(key, state.path(key));
      out.put("dueAt", LmcpService.timestamp(Instant.parse(state.path("dueAt").asText())));
      out.set(
          "lastReviewAt",
          state.path("lastReviewAt").isNull()
              ? NullNode.instance
              : Json.MAPPER.valueToTree(
                  LmcpService.timestamp(Instant.parse(state.path("lastReviewAt").asText()))));
    }
    return out;
  }

  ObjectNode localUndo(Connection db, JsonNode input) throws Exception {
    String submission = Json.id(input, "submissionId", true);
    var fact =
        Sql.first(
            db, "SELECT undone_at FROM desktop_practice_facts WHERE submission_id=?", submission);
    if (fact != null && !fact.path("undone_at").isNull())
      return Json.MAPPER.createObjectNode().put("duplicate", true);
    return undo(
        db,
        localCaller(db),
        Json.MAPPER
            .createObjectNode()
            .put("submissionId", submission)
            .put("expectedLearningRevision", LmcpService.meta(db, "learningRevision")));
  }

  private ObjectNode undo(Connection db, LmcpService.Caller caller, JsonNode p) throws Exception {
    if (!LmcpService.meta(db, "learningRevision")
        .equals(p.path("expectedLearningRevision").asText()))
      throw LmcpService.error("REVISION_CONFLICT", "学习记录已改变");
    JsonNode event = workspace.entity(db, "practice", p.path("submissionId").asText());
    if (event == null) throw LmcpService.error("NOT_FOUND", "练习提交不存在");
    JsonNode ref = event.path("data").path("word");
    String id = DesktopDataModel.localWordId(db, ref);
    var fact =
        Sql.first(
            db,
            "SELECT * FROM desktop_practice_facts WHERE submission_id=?",
            p.path("submissionId").asText());
    if (fact == null) throw LmcpService.error("NOT_FOUND", "本机练习事实不存在");
    var latest =
        Sql.first(
            db,
            "SELECT submission_id FROM desktop_practice_facts WHERE word_id=? AND undone_at IS NULL"
                + " ORDER BY length(logical_clock) DESC,logical_clock DESC,device_id"
                + " DESC,length(device_seq) DESC,device_seq DESC,id DESC LIMIT 1",
            id);
    if (latest == null
        || !latest.path("submission_id").asText().equals(p.path("submissionId").asText()))
      throw LmcpService.error("UNDO_NOT_LATEST", "只能撤销该词最近一次有效评价");
    ObjectNode d =
        Json.MAPPER
            .createObjectNode()
            .put("targetType", "practice")
            .put("targetId", p.path("submissionId").asText())
            .put("occurredAt", service.businessNow());
    d.set(
        "origin",
        Json.MAPPER
            .createObjectNode()
            .put("deviceId", LmcpService.meta(db, "desktopInstanceId"))
            .put("clientKind", "desktop"));
    ObjectNode retraction = DesktopDataModel.record("retraction", LmcpService.uuid(), 1, d, null);
    workspace.importRetraction(db, retraction);
    var out =
        Json.MAPPER.createObjectNode().put("workspaceRevision", LmcpService.meta(db, "revision"));
    out.set("retraction", retraction);
    out.set("learning", workspace.learning(db, ref, service.businessClock.instant()));
    return out;
  }
}
