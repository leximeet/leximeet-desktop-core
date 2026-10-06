package app.leximeet.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Connection;
import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

/**
 * 桌面应用用例入口：查询无副作用，命令提交个人事实。未来插件与同步适配器可以调用同一入口， 不依赖 Vue、Electron、LMCP 或 LMSP；本版只装配本机身份和 SQLite 存储。
 */
final class DesktopWorkspace {
  private final Database database;
  private final Clock clock;
  private final PublicLexicon lexicon;
  private final DesktopWords words;
  private final DesktopReviews reviews;
  private final DesktopPractice practice;
  private final DesktopGuide guide;
  private final DesktopReminderPractice reminderPractice;
  private DesktopDataModel protocolWorkspace;

  DesktopWorkspace(Database database, Clock clock) throws Exception {
    this.database = database;
    // 学习日固定在资料空间时区，不因出差、更换系统时区或未来换端重算历史额度。
    this.clock =
        database.transaction(
            db -> {
              String zone =
                  Sql.first(db, "SELECT study_zone FROM desktop_profile WHERE id=1")
                      .path("study_zone")
                      .asText();
              if (zone.isBlank()) {
                zone = clock.getZone().getId();
                Sql.execute(db, "UPDATE desktop_profile SET study_zone=? WHERE id=1", zone);
              }
              return new WorkspaceClock(database, clock);
            });
    lexicon = new PublicLexicon(database);
    words = new DesktopWords(lexicon, this.clock);
    reviews = new DesktopReviews(this.clock, words);
    practice = new DesktopPractice(this.clock, words, reviews);
    guide = new DesktopGuide(this.clock);
    reminderPractice = new DesktopReminderPractice(words, this.clock);
  }

  void bindProtocolWorkspace(DesktopDataModel workspace) {
    protocolWorkspace = workspace;
    words.bindProtocolWorkspace(workspace);
    reminderPractice.bindProtocolWorkspace(workspace);
  }

  PublicLexicon lexicon() {
    return lexicon;
  }

  DesktopWords words() {
    return words;
  }

  DesktopPractice practice() {
    return practice;
  }

  ObjectNode mount(JsonNode input) throws Exception {
    return lexicon.mount(input);
  }

  ObjectNode state() throws Exception {
    return database.read(this::state);
  }

  private ObjectNode state(Connection db) throws Exception {
    DesktopFamiliarity.refresh(db, clock.instant());
    ObjectNode output =
        Json.MAPPER
            .createObjectNode()
            .put("schema", "leximeet.desktop/1.0.0")
            .put("today", LocalDate.now(clock).toString())
            .put("zone", clock.getZone().getId());
    output.set("dictionary", lexicon.metadata(db));
    output.set("catalogs", lexicon.catalogs(db));
    output.set("profile", DesktopPlan.state(db));
    output.set(
        "learning", Json.MAPPER.createObjectNode().put("ruleVersion", LearningPolicy.VERSION));
    output.set(
        "settings",
        // 当前资料模型的可选设置只在读取时补缺省值，与 snapshot/采集政策保持同一口径，不改原 JSON。
        SettingsSupport.normalize(
            Json.object(
                Json.MAPPER.readTree(
                    Sql.first(db, "SELECT payload FROM settings WHERE id=1")
                        .path("payload")
                        .asText()))));
    output.set(
        "books",
        Sql.rows(
            db,
            "SELECT b.id,b.name,b.color,r.role,COUNT(wb.word_id) AS count FROM books b LEFT JOIN"
                + " book_roles r ON r.book_id=b.id LEFT JOIN word_books wb ON wb.book_id=b.id AND"
                + " wb.word_id IN(SELECT id FROM words WHERE deleted_at IS NULL) WHERE NOT"
                + " EXISTS(SELECT 1 FROM desktop_entities e WHERE e.entity_type='notebook' AND"
                + " e.entity_id=b.id AND e.deleted_at IS NOT NULL) GROUP BY b.id ORDER BY"
                + " b.created_at,b.id"));

    output.set("guide", guide.state(db));
    output.set("session", session(db));
    output.set("queue", queue(db));
    output.set("goalProgress", goalProgress(db));
    output.set("insights", insights(db));
    return output;
  }

  ObjectNode query(JsonNode input) throws Exception {
    if (input.path("kind").asText().equals("practiceWords"))
      return database.transaction(db -> protocolWorkspace.localPractice.words(db, input));
    if (input.path("kind").asText().equals("practice"))
      return database.transaction(db -> protocolWorkspace.localPractice.load(db, input));
    if (input.path("kind").asText().equals("practiceQuestion"))
      return database.transaction(db -> protocolWorkspace.localQuestion(db, input));
    return database.read(
        db -> {
          DesktopFamiliarity.refresh(db, clock.instant());
          String kind =
              Json.choice(
                  input,
                  "kind",
                  "words",
                  "words",
                  "detail",
                  "encounters",
                  "planningPreview",
                  "clipboardCandidates",
                  "reminderQuestions");
          if (kind.equals("reminderQuestions")) {
            Json.fields(input, "kind");
            var result = Json.MAPPER.createObjectNode();
            var items = result.putArray("questions");
            for (var row :
                Sql.rows(
                    db,
                    "SELECT payload FROM desktop_reminder_questions WHERE answered_at IS NULL AND"
                        + " expires_at>?",
                    clock.instant().toString()))
              items.add(Json.MAPPER.readTree(row.path("payload").asText()));
            return result;
          }
          if (kind.equals("clipboardCandidates"))
            return DesktopClipboard.candidates(db, input, lexicon, clock);
          if (kind.equals("planningPreview"))
            return DesktopPlanning.preview(db, input, lexicon, clock, queue(db));
          if (kind.equals("detail")) {
            Json.fields(input, "kind", "wordId");
            return words.detail(db, Json.text(input, "wordId", 120, true));
          }
          if (kind.equals("encounters")) {
            Json.fields(input, "kind", "search", "from", "to", "offset", "limit");
            String search = Json.text(input, "search", 100, false);
            String from = Json.text(input, "from", 10, false),
                to = Json.text(input, "to", 10, false);
            // 按本机时区的日期转成时间边界，避免 UTC 截断凌晨遇见记录。
            String start =
                from.isEmpty()
                    ? "0000"
                    : LocalDate.parse(from).atStartOfDay(clock.getZone()).toInstant().toString();
            String end =
                to.isEmpty()
                    ? "9999"
                    : LocalDate.parse(to)
                        .plusDays(1)
                        .atStartOfDay(clock.getZone())
                        .toInstant()
                        .toString();
            String clause =
                " FROM encounters e JOIN words w ON w.id=e.word_id WHERE e.created_at>=? AND"
                    + " e.created_at<? AND (w.word LIKE ? OR e.context LIKE ?) AND e.id NOT"
                    + " IN(SELECT encounter_id FROM encounter_details WHERE undone_at IS NOT NULL)";
            ObjectNode result = Json.MAPPER.createObjectNode();
            String like = "%" + search + "%";
            result.put(
                "total",
                Sql.first(db, "SELECT COUNT(*) AS n" + clause, start, end, like, like)
                    .path("n")
                    .asLong());
            result.set(
                "encounters",
                Sql.rows(
                    db,
                    "SELECT e.id,e.word_id AS wordId,w.word,e.context,e.source_title AS"
                        + " sourceTitle,e.source_url AS sourceUrl,e.created_at AS createdAt"
                        + clause
                        + " ORDER BY e.created_at DESC,e.id LIMIT ? OFFSET ?",
                    start,
                    end,
                    like,
                    like,
                    DesktopWords.integer(input, "limit", 50, 1, 100),
                    DesktopWords.integer(input, "offset", 0, 0, 1000000)));
            return result;
          }
          var result = words.query(db, input);
          var scheduled = new java.util.HashSet<String>();
          for (var task : queue(db).path("tasks"))
            if (task.path("kind").asText().equals("new")) scheduled.add(task.path("id").asText());
          for (var row : result.path("words"))
            if (row.path("learningStatus").asText().equals("new")
                && scheduled.contains(row.path("id").asText())) {
              ((ObjectNode) row).put("scheduled", true);
              ((ObjectNode) row.path("familiarity")).put("label", "待学习");
            }
          return result;
        });
  }

  ObjectNode command(JsonNode input) throws Exception {
    return database.transaction(
        db -> {
          String action = Json.text(input, "action", 40, true);
          String event = "";
          ObjectNode practiceFeedback = null;
          ObjectNode captureResult = null;
          switch (action) {
            case "issueReminderQuestion" -> {
              DesktopFamiliarity.refresh(db, clock.instant());
              return reminderPractice.issue(db, input, queue(db));
            }
            case "answerReminderQuestion" -> {
              return reminderPractice.answer(db, input);
            }
            case "setGoal" -> {
              Json.fields(input, "action", "goal", "expectedRevision");
              String goal = Json.text(input, "goal", 100, false);
              lexicon.require();
              if (!goal.isEmpty()
                  && !goal.equals("dictionary")
                  && Sql.first(db, "SELECT id FROM lexicon.catalogs WHERE id=?", goal) == null)
                throw ApiException.badRequest("请选择公共词库或整本词典");
              int changed =
                  Sql.executeCount(
                      db,
                      "UPDATE desktop_profile SET goal=?,revision=revision+1 WHERE id=1 AND"
                          + " revision=?",
                      goal.isEmpty() ? null : goal,
                      input.path("expectedRevision").asInt());
              if (changed != 1) throw ApiException.conflict("目标已变化，请刷新后重试");
              event = "goal";
            }
            case "savePlanning" -> {
              DesktopPlanning.save(db, input, lexicon, clock);
              event = "plan";
            }
            case "savePlan" -> {
              DesktopPlan.save(db, input);
              event = "plan";
            }
            case "saveReminderPreferences" -> DesktopPlan.saveReminderPreferences(db, input);
            case "collect", "capture", "saveNote" -> {
              captureResult = words.save(db, input, action.equals("capture"));
              event =
                  action.equals("capture")
                      ? captureResult.path("captureStatus").asText().equals("created")
                          ? "capture"
                          : ""
                      : "note";
              // 笔记不属于教学步骤；保存时仍执行版本与词本关系校验。
            }
            case "captureClipboard" -> {
              var outcome = DesktopClipboard.capture(db, input, lexicon, words);
              ObjectNode captured = outcome.result();
              // 保存时未通过目标复核属于正常撤回，不改资料、不制造学习事件。
              ObjectNode result =
                  state(db)
                      .put("captureOperationReplayed", outcome.replayed())
                      .put(
                          "clipboardAccepted",
                          captured != null
                              && captured.path("captureStatus").asText().equals("created"));
              if (captured != null) result.set("captureResult", captured);
              return result;
            }
            case "review" -> {
              ObjectNode session = session(db);
              int cursor = session.path("cursor").asInt();
              boolean duplicate =
                  Sql.first(
                          db,
                          "SELECT id FROM desktop_reviews WHERE submission_id=?",
                          input.path("submissionId").asText())
                      != null;
              if (session.path("phase").asText().equals("running") && !duplicate) {
                if (!session.path("day").asText().equals(LocalDate.now(clock).toString()))
                  throw ApiException.conflict("学习会话已经跨日，请结束旧会话后开始今天的任务");
                if (!session
                    .path("tasks")
                    .path(cursor)
                    .path("id")
                    .asText()
                    .equals(input.path("wordId").asText()))
                  throw ApiException.conflict("学习会话位置已经变化，请刷新");
              }
              var result = reviews.record(db, input);
              event = "learn";
              if (session.path("phase").asText().equals("running") && !duplicate) {
                if (result.path("completed").asBoolean()) session.put("cursor", cursor + 1);
                else
                  ((ObjectNode) session.withArray("tasks").get(cursor))
                      .put("round", input.path("round").asInt() + 1);
                if (session.path("cursor").asInt() >= session.path("tasks").size())
                  session.put("phase", "complete");
                Sql.execute(
                    db,
                    "UPDATE desktop_study_session SET payload=? WHERE id=1",
                    session.toString());
              }
            }
            case "undoReview" -> {
              Json.fields(input, "action", "reviewId");
              String id = Json.id(input, "reviewId", true);
              var fact = Sql.first(db, "SELECT word_id FROM desktop_reviews WHERE id=?", id);
              reviews.undo(db, id);
              ObjectNode active = session(db);
              // 撤销当前会话的最近反馈时退回对应单词，刷新或重启后仍可继续。
              if (fact != null
                  && active.path("day").asText().equals(LocalDate.now(clock).toString())) {
                for (int i = 0; i < active.path("tasks").size(); i++) {
                  if (active
                      .path("tasks")
                      .path(i)
                      .path("id")
                      .asText()
                      .equals(fact.path("word_id").asText())) {
                    var card =
                        Sql.first(
                            db,
                            "SELECT memory FROM desktop_cards WHERE word_id=?",
                            fact.path("word_id").asText());
                    ((ObjectNode) active.withArray("tasks").get(i))
                        .put(
                            "round",
                            Json.MAPPER
                                .readTree(card.path("memory").asText())
                                .path("round")
                                .asInt());
                    active.put("cursor", i).put("phase", "running");
                    Sql.execute(
                        db,
                        "UPDATE desktop_study_session SET payload=? WHERE id=1",
                        active.toString());
                    break;
                  }
                }
              }
            }
            case "undoPractice" -> {
              Json.fields(input, "action", "submissionId");
              protocolWorkspace.localUndo(db, input);
            }
            case "practiceSave" -> protocolWorkspace.localPractice.save(db, input);
            case "studyStart", "studyPause", "studyResume", "studyEnd", "studySkip" -> {
              Json.fields(input, "action");
              ObjectNode session = session(db);
              String phase = session.path("phase").asText();
              if (action.equals("studyStart")
                  && (phase.equals("running") || phase.equals("paused")))
                throw ApiException.conflict("还有未结束的学习会话，请继续或结束后再开始");
              if (List.of("studyPause", "studyResume", "studySkip").contains(action)) {
                if (!(phase.equals("running") || phase.equals("paused")))
                  throw ApiException.conflict("当前没有可以继续的学习会话");
                if (!session.path("day").asText().equals(LocalDate.now(clock).toString()))
                  throw ApiException.conflict("学习会话已经跨日，请先结束旧会话");
              }
              if (action.equals("studyStart")) {
                session =
                    Json.MAPPER
                        .createObjectNode()
                        .put("phase", "running")
                        .put("day", LocalDate.now(clock).toString())
                        .put("cursor", 0)
                        .set("tasks", queue(db).path("tasks"));
                if (session.path("tasks").isEmpty()) session.put("phase", "complete");
              }
              if (action.equals("studyPause")) session.put("phase", "paused");
              if (action.equals("studyResume")) session.put("phase", "running");
              if (action.equals("studyEnd"))
                session = Json.MAPPER.createObjectNode().put("phase", "idle");
              if (action.equals("studySkip")) {
                session.put("cursor", session.path("cursor").asInt() + 1);
                if (session.path("cursor").asInt() >= session.path("tasks").size())
                  session.put("phase", "complete");
              }
              Sql.execute(
                  db, "UPDATE desktop_study_session SET payload=? WHERE id=1", session.toString());
            }
            case "practiceRecord" -> {
              words.detail(db, Json.id(input, "wordId", true));
              practiceFeedback = protocolWorkspace.localFeedback(db, input);
              boolean correct = practiceFeedback.path("correct").asBoolean();
              if (!input.path("signal").asText().equals("reveal")
                  && (correct || input.path("mode").asText().equals("word-list"))) {
                event = "practice";
                guide.change(db, "guideEvent", "learn");
              }
            }
            case "createBook" -> {
              Json.fields(input, "action", "name");
              String name = Json.text(input, "name", 80, true);
              PersonalLibrary.rejectReservedName(name);
              Sql.execute(
                  db,
                  "INSERT INTO books VALUES(?,?,?,?)",
                  UUID.randomUUID().toString(),
                  name,
                  "#699383",
                  clock.instant().toString());
              event = "book";
            }
            case "deleteBook" -> {
              Json.fields(input, "action", "bookId");
              protocolWorkspace.localDeleteNotebook(db, Json.id(input, "bookId", true));
            }
            case "trash", "restore", "bulkTrash", "bulkRestore" -> {
              DesktopLibraryOperations.trash(
                  db,
                  input,
                  words,
                  clock,
                  action.equals("restore") || action.equals("bulkRestore"),
                  action.startsWith("bulk"));
              event = action.contains("Restore") || action.equals("restore") ? "restore" : "trash";
            }
            case "bulkAddToBooks" -> DesktopLibraryOperations.addToBooks(db, input, words, clock);
            case "guideStart", "guidePause", "guideResume", "guideReset", "guidePrevious" -> {
              Json.fields(input, "action");
              guide.change(db, action, "");
            }
            case "guideNext" -> {
              Json.fields(input, "action", "step");
              guide.change(db, action, Json.text(input, "step", 40, true));
            }
            case "guideEvent" -> {
              Json.fields(input, "action", "event");
              String e =
                  Json.choice(input, "event", "dictionary", "dictionary", "insights", "theme");
              guide.change(db, action, e);
            }
            default -> throw ApiException.badRequest("桌面命令不存在");
          }
          if (!event.isEmpty()) guide.change(db, "guideEvent", event);
          protocolWorkspace.synchronize(db);
          ObjectNode result = state(db);
          if (practiceFeedback != null) result.set("practiceFeedback", practiceFeedback);
          if (captureResult != null) result.set("captureResult", captureResult);
          return result;
        });
  }

  ObjectNode nativeEvent(JsonNode input) throws Exception {
    Json.fields(input, "event");
    String event = Json.choice(input, "event", "audio", "audio", "backup");
    return database.transaction(
        db -> {
          guide.change(db, "nativeEvent", event);
          return guide.state(db);
        });
  }

  ObjectNode queue(Connection db) throws Exception {
    String day = LocalDate.now(clock).toString();
    ObjectNode plan = Sql.first(db, "SELECT * FROM desktop_profile WHERE id=1");
    int newDone = DesktopReviews.completed(db, day, "new"),
        reviewDone = DesktopReviews.completed(db, day, "review");
    int newRemaining = Math.max(0, plan.path("daily_new").asInt() - newDone),
        reviewRemaining = Math.max(0, plan.path("daily_review").asInt() - reviewDone);
    var groups = Json.MAPPER.createObjectNode();
    ArrayNode encounters = groups.putArray("encounters"),
        planned = groups.putArray("planned"),
        due = groups.putArray("reviews");
    LinkedHashMap<String, JsonNode> tasks = new LinkedHashMap<>();
    if (lexicon.mounted()) {
      for (JsonNode row :
          Sql.rows(
              db,
              "SELECT DISTINCT w.id FROM words w JOIN encounters e ON e.word_id=w.id LEFT JOIN"
                  + " desktop_familiarity c ON c.word_id=w.id WHERE w.deleted_at IS NULL AND"
                  + " c.graduated_at IS NULL AND e.id NOT IN(SELECT encounter_id FROM"
                  + " encounter_details WHERE undone_at IS NOT NULL) ORDER BY e.created_at DESC"
                  + " LIMIT ?",
              200)) addTask(db, row.path("id").asText(), "encounter", "new", encounters, tasks);
      for (JsonNode row :
          Sql.rows(
              db,
              "SELECT f.word_id AS id FROM desktop_familiarity f JOIN words w ON w.id=f.word_id"
                  + " WHERE f.status='learning' AND w.deleted_at IS NULL ORDER BY w.updated_at,w.id"
                  + " LIMIT 200"))
        addTask(db, row.path("id").asText(), "continuing", "new", planned, tasks);
      int rest = Math.max(0, newRemaining - tasks.size());
      int existing = planned.size();
      String goal = plan.path("goal").asText("");
      if (rest > 0 && !goal.isEmpty() && plan.path("plan_enabled").asBoolean())
        for (JsonNode row :
            Sql.rows(
                db,
                "SELECT COALESCE(w.id,e.id) AS id FROM lexicon.entries e LEFT JOIN words w ON "
                    + DesktopWords.personalJoin("e")
                    + " LEFT JOIN desktop_familiarity c ON c.word_id=w.id WHERE "
                    + DesktopWords.predicate(goal)
                    + " AND c.graduated_at IS NULL AND w.deleted_at IS NULL ORDER BY "
                    + DesktopWords.order(goal)
                    + ",e.id LIMIT ?",
                rest + tasks.size())) {
          if (planned.size() - existing >= rest) break;
          addTask(db, row.path("id").asText(), "plan", "new", planned, tasks);
        }
      int reviewAdmissions = 0;
      for (JsonNode row :
          Sql.rows(
              db,
              """
              SELECT f.word_id AS id, EXISTS(SELECT 1 FROM desktop_practice_facts p
                WHERE p.word_id=f.word_id AND p.study_day=? AND p.review_completed=1 AND p.undone_at IS NULL) AS done
              FROM desktop_familiarity f JOIN words w ON w.id=f.word_id
              LEFT JOIN desktop_cards c ON c.word_id=f.word_id
              WHERE f.status='review' AND f.eligible_at<=? AND COALESCE(c.due_at,f.first_recall_due)<=?
                AND w.deleted_at IS NULL
              ORDER BY COALESCE(c.due_at,f.first_recall_due),f.word_id LIMIT 700
              """,
              day,
              clock.instant().toString(),
              clock.instant().toString())) {
        // 同日已经完成的词若再次答错，仍允许短期巩固，不二次占复习额度。
        boolean done = row.path("done").asBoolean();
        if (!done && reviewAdmissions >= reviewRemaining) continue;
        if (!done) reviewAdmissions++;
        addTask(db, row.path("id").asText(), "review", "review", due, tasks);
      }
    }
    String currentGoal = plan.path("goal").asText("");
    for (JsonNode row : tasks.values()) {
      ObjectNode task = (ObjectNode) row;
      ArrayNode sources = task.putArray("sources").add(task.path("source").asText());
      if (task.path("source").asText().equals("encounter") && !currentGoal.isEmpty()) {
        String entry = words.detail(db, task.path("id").asText()).path("entryId").asText("");
        if (!entry.isEmpty()
            && (currentGoal.equals("dictionary")
                || Sql.first(
                        db,
                        "SELECT 1 FROM lexicon.members WHERE catalog_id=? AND entry_id=?",
                        currentGoal,
                        entry)
                    != null)) sources.add("plan");
      }
    }
    groups.set("tasks", Json.MAPPER.valueToTree(tasks.values()));
    groups
        .put("newDone", newDone)
        .put("reviewDone", reviewDone)
        .put("newRemaining", newRemaining)
        .put("reviewRemaining", reviewRemaining);
    var recent =
        Sql.first(
            db,
            "SELECT id FROM desktop_reviews WHERE undone_at IS NULL ORDER BY created_at DESC,rowid"
                + " DESC LIMIT 1");
    if (recent != null) groups.put("lastReviewId", recent.path("id").asText());
    return groups;
  }

  private ObjectNode session(Connection db) throws Exception {
    return Json.object(
        Json.MAPPER.readTree(
            Sql.first(db, "SELECT payload FROM desktop_study_session WHERE id=1")
                .path("payload")
                .asText()));
  }

  private void addTask(
      Connection db,
      String id,
      String source,
      String kind,
      ArrayNode group,
      LinkedHashMap<String, JsonNode> tasks)
      throws Exception {
    if (tasks.containsKey(id)) return;
    ObjectNode detail = words.detail(db, id);
    ObjectNode card = Sql.first(db, "SELECT memory FROM desktop_cards WHERE word_id=?", id);
    int round =
        card == null ? 1 : Json.MAPPER.readTree(card.path("memory").asText()).path("round").asInt();
    ObjectNode task =
        Json.MAPPER
            .createObjectNode()
            .put("id", id)
            .put("word", detail.path("word").asText())
            .put("meaning", detail.path("meaning").asText())
            .put("source", source)
            .put("kind", kind)
            .put("round", round);
    group.add(task);
    tasks.put(id, task);
  }

  private ObjectNode insights(Connection db) throws Exception {
    ObjectNode result = Json.MAPPER.createObjectNode();
    result.put("manualWords", PersonalLibrary.activeCount(db));
    result.put(
        "learned",
        Sql.first(
                db, "SELECT COUNT(*) AS n FROM desktop_familiarity WHERE graduated_at IS NOT NULL")
            .path("n")
            .asLong());
    result.put(
        "encounters",
        Sql.first(
                db,
                "SELECT COUNT(*) AS n FROM encounters WHERE is_demo=0 AND id NOT IN(SELECT"
                    + " encounter_id FROM encounter_details WHERE undone_at IS NOT NULL)")
            .path("n")
            .asLong());
    result.put(
        "practice",
        Sql.first(db, "SELECT COUNT(*) AS n FROM desktop_practice_facts WHERE undone_at IS NULL")
            .path("n")
            .asLong());
    result.put(
        "practiceAnswers",
        Sql.first(
                db,
                "SELECT COUNT(*) AS n FROM desktop_practice_facts f WHERE f.undone_at IS NULL AND"
                    + " mode IN('meaning-choice','recall','listening','cloze') AND assisted=0 AND"
                    + " signal='answer' AND NOT EXISTS(SELECT 1 FROM desktop_practice_facts earlier"
                    + " WHERE earlier.attempt_id=f.attempt_id AND earlier.undone_at IS NULL AND"
                    + " (length(earlier.logical_clock)<length(f.logical_clock) OR"
                    + " (length(earlier.logical_clock)=length(f.logical_clock) AND"
                    + " earlier.logical_clock<f.logical_clock)))")
            .path("n")
            .asLong());
    result.put(
        "practiceCorrect",
        Sql.first(
                db,
                "SELECT COUNT(*) AS n FROM desktop_practice_facts f WHERE f.undone_at IS NULL AND"
                    + " mode IN('meaning-choice','recall','listening','cloze') AND assisted=0 AND"
                    + " signal='answer' AND NOT EXISTS(SELECT 1 FROM desktop_practice_facts earlier"
                    + " WHERE earlier.attempt_id=f.attempt_id AND earlier.undone_at IS NULL AND"
                    + " (length(earlier.logical_clock)<length(f.logical_clock) OR"
                    + " (length(earlier.logical_clock)=length(f.logical_clock) AND"
                    + " earlier.logical_clock<f.logical_clock))) AND correct=1")
            .path("n")
            .asLong());
    result.put(
        "trash",
        Sql.first(db, "SELECT COUNT(*) AS n FROM words WHERE deleted_at IS NOT NULL")
            .path("n")
            .asLong());
    result.set(
        "days",
        Sql.rows(
            db,
            // 洞察与今日额度使用同一完成定义；每词每天只统计一次有效复习，撤销会同步回退。
            "WITH completions AS (SELECT word_id,graduated_day AS day,'new' AS kind FROM"
                + " desktop_familiarity WHERE graduated_at IS NOT NULL UNION ALL SELECT DISTINCT"
                + " word_id,study_day AS day,'review' AS kind FROM desktop_practice_facts WHERE"
                + " review_completed=1 AND undone_at IS NULL AND rule_version=?) SELECT"
                + " day,COUNT(DISTINCT word_id) AS completed,SUM(CASE WHEN kind='new' THEN 1 ELSE 0"
                + " END) AS newFeedback,SUM(CASE WHEN kind='review' THEN 1 ELSE 0 END) AS"
                + " reviewFeedback FROM completions GROUP BY day ORDER BY day DESC LIMIT 30",
            LearningPolicy.VERSION));
    return result;
  }

  private ObjectNode goalProgress(Connection db) throws Exception {
    return DesktopPlanning.progress(db, DesktopWords.goal(db), lexicon);
  }
}
