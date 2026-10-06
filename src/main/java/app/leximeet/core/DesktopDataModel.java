package app.leximeet.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;

// 工作区业务适配：公共身份保持稳定，私人覆盖与本机 UI 写到同一事务和关系表。
final class DesktopDataModel {
  final LmcpService service;
  final DesktopWorkspace desktop;
  final PublicLexicon lexicon;
  private final DesktopPracticeSessions practice;
  final DesktopPracticeCheckpoint localPractice;
  final DesktopLearningReplay replay;

  DesktopDataModel(LmcpService service, DesktopWorkspace desktop) {
    this.service = service;
    this.desktop = desktop;
    lexicon = desktop.lexicon();
    practice = new DesktopPracticeSessions(this);
    localPractice = new DesktopPracticeCheckpoint(this, practice);
    replay = new DesktopLearningReplay(this);
    desktop.bindProtocolWorkspace(this);
  }

  // 普通词本删除保留墓碑，个人词条和学习事实不会随关系回收而消失。
  void localDeleteNotebook(Connection db, String id) throws Exception {
    synchronize(db);
    ObjectNode old = entity(db, "notebook", id);
    if (old == null || old.path("deletedAt").isTextual())
      throw LmcpService.error("NOT_FOUND", "单词本不存在");
    if (PersonalLibrary.protectedBook(db, id)) throw LmcpService.error("FORBIDDEN", "系统词本不可修改或回收");
    ObjectNode deleted =
        record(
            "notebook",
            id,
            nextRevision(old.path("revision").asText()),
            old.path("data"),
            service.businessNow());
    // 关系变化使打开的笔记草稿失效；保留词本表和关系供备份审计，读模型排除墓碑。
    Sql.execute(
        db,
        "UPDATE words SET revision=revision+1,updated_at=? WHERE id IN"
            + " (SELECT word_id FROM word_books WHERE book_id=?)",
        service.businessNow(),
        id);
    put(db, deleted);
  }

  ObjectNode localQuestion(Connection db, JsonNode input) throws Exception {
    return practice.localQuestion(db, input);
  }

  ObjectNode localFeedback(Connection db, JsonNode input) throws Exception {
    return practice.localFeedback(db, input);
  }

  // 本机命令和协议都使用同一业务表；这里只生成跨端投影，不制造学习事实。
  void synchronize(Connection db) throws Exception {
    int position = 0;
    for (JsonNode row : Sql.rows(db, "SELECT * FROM books ORDER BY created_at,id")) {
      ObjectNode data =
          Json.MAPPER
              .createObjectNode()
              .put("name", row.path("name").asText())
              .put("color", row.path("color").asText())
              .put("position", Math.min(position++, 2000));
      var existing = entity(db, "notebook", row.path("id").asText());
      if (existing != null) {
        data.put("position", existing.path("data").path("position").asInt());
      }
      sync(
          db,
          "notebook",
          row.path("id").asText(),
          data,
          existing == null ? null : existing.path("deletedAt").asText(null));
    }
    for (JsonNode row :
        Sql.rows(
            db,
            "SELECT w.*,l.entry_id,l.manual_active FROM words w LEFT JOIN desktop_word_links l ON"
                + " l.word_id=w.id")) {
      ObjectNode word =
          wordRef(
              row.path("entry_id").isTextual() ? row.path("entry_id").asText() : "",
              row.path("id").asText(),
              row.path("word").asText(),
              release(db));
      var remembered =
          Sql.first(
              db,
              "SELECT payload FROM desktop_word_identity WHERE word_id=?",
              row.path("id").asText());
      if (remembered != null)
        word = Json.object(Json.MAPPER.readTree(remembered.path("payload").asText()));
      else
        Sql.execute(
            db,
            "INSERT INTO desktop_word_identity VALUES(?,?)",
            row.path("id").asText(),
            word.toString());
      ObjectNode data =
          Json.MAPPER
              .createObjectNode()
              .put("note", row.path("note").asText())
              .put("collected", row.path("manual_active").asBoolean(true));
      data.set("word", word);
      var books = data.putArray("notebookIds");
      for (JsonNode relation :
          Sql.rows(
              db,
              "SELECT book_id FROM word_books WHERE word_id=? ORDER BY book_id",
              row.path("id").asText())) books.add(relation.path("book_id").asText());
      sync(db, "word", key(word), data, row.path("deleted_at").asText(null));
    }
    for (JsonNode row :
        Sql.rows(
            db,
            "SELECT * FROM desktop_entities WHERE entity_type='notebook' AND deleted_at IS NULL")) {
      if (Sql.first(db, "SELECT id FROM books WHERE id=?", row.path("entity_id").asText())
          == null) {
        ObjectNode previous = fromRow(row);
        put(
            db,
            record(
                "notebook",
                previous.path("entityId").asText(),
                nextRevision(previous.path("revision").asText()),
                previous.path("data"),
                service.businessNow()));
      }
    }
    sync(db, "studyPlan", LmcpService.meta(db, "workspaceId"), planData(db), null);
  }

  private void sync(Connection db, String type, String id, ObjectNode data, String deleted)
      throws Exception {
    ObjectNode old = entity(db, type, id);
    if (old != null
        && old.path("data").equals(data)
        && java.util.Objects.equals(old.path("deletedAt").asText(null), deleted)) return;
    put(
        db,
        record(
            type,
            id,
            old == null ? "1" : nextRevision(old.path("revision").asText()),
            data,
            deleted == null ? null : LmcpService.timestamp(Instant.parse(deleted))));
  }

  private ObjectNode planData(Connection db) throws Exception {
    var profile = DesktopPlan.state(db);
    ObjectNode out =
        Json.MAPPER
            .createObjectNode()
            .put("active", profile.path("planEnabled").asBoolean())
            .put("dailyNew", profile.path("dailyNew").asInt())
            .put("dailyReview", profile.path("dailyReview").asInt())
            .put("timeZone", profile.path("studyZone").asText("UTC"));
    out.set("planId", profile.path("planId"));
    out.set("startedOn", profile.path("startedOn"));
    String goal = profile.path("goal").asText("");
    out.set("goal", goal.isEmpty() ? NullNode.instance : goal(db, goal));
    return out;
  }

  ObjectNode plan(Connection db) throws Exception {
    ObjectNode current = entity(db, "studyPlan", LmcpService.meta(db, "workspaceId"));
    return current == null
        ? record("studyPlan", LmcpService.meta(db, "workspaceId"), 1, planData(db), null)
        : current;
  }

  ObjectNode preferences(Connection db) throws Exception {
    return Json.MAPPER
        .createObjectNode()
        .set("preferences", Json.MAPPER.readTree(LmcpService.meta(db, "preferences")));
  }

  void mirrorWord(Connection db, JsonNode entity) throws Exception {
    JsonNode d = entity.path("data"), word = d.path("word");
    Sql.execute(
        db,
        "INSERT INTO desktop_word_identity VALUES(?,?) ON CONFLICT(word_id) DO NOTHING",
        id(word),
        word.toString());
    String local = id(word),
        entry = word.path("entryId").asText(),
        head = word.path("headword").asText(),
        meaning = "";
    if (word.path("kind").asText().equals("dictionary")) {
      JsonNode found = lexicon.entry(db, entry);
      if (found != null) {
        head = found.path("headword").asText();
        // 这里只缓存只读词典的简短释义；个人事实不包含释义覆盖。
        meaning = DesktopWords.meaning(found);
      } else head = "[" + entry + "]";
    }
    var localVersion = Sql.first(db, "SELECT revision FROM words WHERE id=?", local);
    long displayRevision =
        localVersion == null ? 1 : Math.addExact(localVersion.path("revision").asLong(), 1);
    // UI 本机并发版本不是跨端 revision，不能将40位协议串写入 INTEGER 投影而丢精度。
    Sql.execute(
        db,
        "INSERT INTO"
            + " words(id,word,normalized,meaning,note,revision,created_at,updated_at,deleted_at)"
            + " VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET"
            + " meaning=excluded.meaning,note=excluded.note,revision=excluded.revision,updated_at=excluded.updated_at,deleted_at=excluded.deleted_at",
        local,
        head,
        "identity:" + key(word),
        meaning,
        d.path("note").asText(),
        displayRevision,
        service.businessNow(),
        service.businessNow(),
        entity.path("deletedAt").asText(null));
    Sql.execute(
        db,
        "INSERT INTO desktop_word_links VALUES(?,?,?) ON CONFLICT(word_id) DO UPDATE SET"
            + " entry_id=excluded.entry_id,manual_active=excluded.manual_active",
        local,
        entry.isEmpty() ? null : entry,
        d.path("collected").asBoolean() ? 1 : 0);
    Sql.execute(db, "DELETE FROM word_books WHERE word_id=?", local);
    for (JsonNode book : d.path("notebookIds"))
      Sql.execute(db, "INSERT INTO word_books VALUES(?,?)", local, book.asText());
  }

  ObjectNode wordSummary(Connection db, JsonNode ref) throws Exception {
    ObjectNode record = entity(db, "word", key(ref));
    JsonNode entry =
        ref.path("kind").asText().equals("dictionary")
            ? lexicon.entry(db, ref.path("entryId").asText())
            : null;
    var out =
        Json.MAPPER
            .createObjectNode()
            .put("inTarget", inTarget(db, ref))
            .put(
                "resourceStatus",
                entry != null || ref.path("kind").asText().equals("custom")
                    ? "available"
                    : "missing");
    out.set("word", ref);
    out.set(
        "headword",
        entry != null
            ? entry.path("headword")
            : ref.path("kind").asText().equals("custom")
                ? ref.path("headword")
                : NullNode.instance);
    out.set("personal", record == null ? NullNode.instance : record);
    out.set("learning", learning(db, ref, service.businessClock.instant()));
    return out;
  }

  ObjectNode learning(Connection db, JsonNode ref, Instant at) throws Exception {
    String local = localWordId(db, ref);
    var projection = DesktopFamiliarity.assess(db, local, at);
    var out =
        Json.MAPPER
            .createObjectNode()
            .put("ruleVersion", LearningPolicy.VERSION)
            .put("mergeProfileId", "leximeet.learning-sync/1")
            .put("score", projection.path("score").asInt())
            .put("status", projection.path("status").asText())
            .put("unfamiliar", projection.path("score").asInt() == 0)
            .put("asOf", LmcpService.timestamp(at))
            .put("revision", LmcpService.meta(db, "learningRevision"));
    out.set("word", ref);
    for (String field :
        List.of(
            "startedAt",
            "graduatedAt",
            "graduatedDay",
            "reviewEligibleAt",
            "firstRecallDueAt",
            "masteryCycleAt",
            "nextDecayAt"))
      out.set(
          field,
          projection.path(field).isTextual() && !field.equals("graduatedDay")
              ? Json.MAPPER.valueToTree(
                  LmcpService.timestamp(Instant.parse(projection.path(field).asText())))
              : projection.path(field));
    var card = Sql.first(db, "SELECT due_at FROM desktop_cards WHERE word_id=?", local);
    String due =
        card == null
            ? projection.path("firstRecallDueAt").asText(null)
            : card.path("due_at").asText();
    String eligible = projection.path("reviewEligibleAt").asText(null);
    out.put(
        "automaticDueAt",
        projection.path("status").asText().equals("review") && due != null && eligible != null
            ? (Instant.parse(due).isAfter(Instant.parse(eligible))
                ? LmcpService.timestamp(Instant.parse(due))
                : LmcpService.timestamp(Instant.parse(eligible)))
            : null);
    out.put("historyHash", historyHash(db, ref, at));
    return out;
  }

  String historyHash(Connection db, JsonNode ref, Instant asOf) throws Exception {
    var history = Json.MAPPER.createObjectNode().put("asOf", LmcpService.timestamp(asOf));
    history.set("mergeProfile", service.contract.mergeProfile);
    history.set("algorithmProfile", service.contract.algorithmProfile);
    String initial = null;
    var practices = history.putArray("practice");
    var retractions = history.putArray("retractions");
    for (JsonNode fact : DesktopFamiliarity.orderedFacts(db, localWordId(db, ref))) {
      if (Instant.parse(fact.path("created_at").asText()).isAfter(asOf)) continue;
      JsonNode entity = entity(db, "practice", fact.path("id").asText());
      if (entity == null) continue;
      String seed = entity.path("data").path("initialDueAt").asText();
      if (initial == null || Instant.parse(seed).isBefore(Instant.parse(initial))) initial = seed;
      boolean removed = false;
      for (JsonNode row :
          Sql.rows(
              db,
              "SELECT entity_id,payload FROM desktop_entities WHERE entity_type='retraction' AND"
                  + " json_extract(payload,'$.targetType')='practice' AND"
                  + " json_extract(payload,'$.targetId')=? ORDER BY entity_id",
              fact.path("id").asText())) {
        JsonNode data = Json.MAPPER.readTree(row.path("payload").asText());
        if (!Instant.parse(data.path("occurredAt").asText()).isAfter(asOf)) {
          removed = true;
          retractions.add(
              Json.MAPPER
                  .createObjectNode()
                  .put("entityId", row.path("entity_id").asText())
                  .set("data", data));
        }
      }
      if (!removed)
        practices.add(
            Json.MAPPER
                .createObjectNode()
                .put("entityId", entity.path("entityId").asText())
                .set("data", entity.path("data")));
    }
    history.put("initialDueAt", initial);
    java.util.List<JsonNode> sorted = new java.util.ArrayList<>();
    retractions.forEach(sorted::add);
    sorted.sort(java.util.Comparator.comparing(value -> value.path("entityId").asText()));
    retractions.removeAll();
    sorted.forEach(retractions::add);
    return LmcpContract.digest(history);
  }

  // 公共身份保持不变；本机个人覆盖可使用不同主键，事实必须落到其真实关联。
  static String localWordId(Connection db, JsonNode ref) throws Exception {
    if (!ref.path("kind").asText().equals("dictionary")) return id(ref);
    var linked =
        Sql.first(
            db,
            "SELECT word_id FROM desktop_word_links WHERE entry_id=? ORDER BY word_id LIMIT 1",
            ref.path("entryId").asText());
    return linked == null ? id(ref) : linked.path("word_id").asText();
  }

  ObjectNode localUndo(Connection db, JsonNode input) throws Exception {
    return practice.localUndo(db, input);
  }

  boolean inTarget(Connection db, JsonNode ref) throws Exception {
    if (!ref.path("kind").asText().equals("dictionary") || !lexicon.mounted()) return false;
    String goal = DesktopWords.goal(db);
    return goal.equals("dictionary")
        || !goal.isBlank()
            && Sql.first(
                    db,
                    "SELECT 1 FROM lexicon.members WHERE catalog_id=? AND entry_id=?",
                    goal,
                    ref.path("entryId").asText())
                != null;
  }

  String release(Connection db) throws Exception {
    return lexicon.metadata(db).path("version").asText("0.0.3");
  }

  ObjectNode goal(Connection db, String id) throws Exception {
    var out =
        Json.MAPPER
            .createObjectNode()
            .put("kind", id.equals("dictionary") ? "dictionary" : "catalog")
            .put("release", release(db));
    if (!id.equals("dictionary")) out.put("catalogId", id);
    else out.put("edition", lexicon.metadata(db).path("edition").asText("core-text"));
    return out;
  }

  static ObjectNode wordRef(String entry, String custom, String head, String release) {
    var out = Json.MAPPER.createObjectNode().put("kind", entry.isEmpty() ? "custom" : "dictionary");
    if (entry.isEmpty()) out.put("customId", custom).put("headword", head).put("language", "en");
    else out.put("entryId", entry).put("release", release).put("entrySchema", "leximeet.entry.v2");
    return out;
  }

  static String key(JsonNode word) {
    return word.path("kind").asText().equals("dictionary")
        ? "dict:" + word.path("entryId").asText()
        : "custom:" + word.path("customId").asText();
  }

  static String id(JsonNode word) {
    return word.path("kind").asText().equals("dictionary")
        ? word.path("entryId").asText()
        : word.path("customId").asText();
  }

  static String nextRevision(String value) {
    String result = new java.math.BigInteger(value).add(java.math.BigInteger.ONE).toString();
    if (result.length() > 40) throw LmcpService.error("LIMIT_EXCEEDED", "修订号超出协议上限");
    return result;
  }

  ObjectNode entity(Connection db, String type, String id) throws Exception {
    var row =
        Sql.first(
            db, "SELECT * FROM desktop_entities WHERE entity_type=? AND entity_id=?", type, id);
    return row == null ? null : fromRow(row);
  }

  static ObjectNode fromRow(JsonNode row) throws Exception {
    return record(
        row.path("entity_type").asText(),
        row.path("entity_id").asText(),
        row.path("revision").asText(),
        Json.MAPPER.readTree(row.path("payload").asText()),
        row.path("deleted_at").asText(null));
  }

  static ObjectNode record(String type, String id, long revision, JsonNode data, String deleted) {
    return record(type, id, Long.toString(revision), data, deleted);
  }

  static ObjectNode record(String type, String id, String revision, JsonNode data, String deleted) {
    var out =
        Json.MAPPER
            .createObjectNode()
            .put("entityType", type)
            .put("entityId", id)
            .put("revision", revision)
            .put("deletedAt", deleted);
    out.set("data", data);
    return out;
  }

  void put(Connection db, JsonNode record) throws Exception {
    service.contract.entity(record);
    Sql.execute(
        db,
        "INSERT INTO desktop_entities VALUES(?,?,?,?,?) ON CONFLICT(entity_type,entity_id) DO"
            + " UPDATE SET"
            + " revision=excluded.revision,deleted_at=excluded.deleted_at,payload=excluded.payload",
        record.path("entityType").asText(),
        record.path("entityId").asText(),
        record.path("revision").asText(),
        record.path("deletedAt").asText(null),
        record.path("data").toString());
    change(
        db,
        record.path("entityType").asText(),
        record.path("entityId").asText(),
        "library",
        "today",
        "insights");
  }

  void change(Connection db, String type, String id, String... views) throws Exception {
    LmcpService.bump(db, "revision");
  }

  ObjectNode reference(Connection db, JsonNode detail) throws Exception {
    var remembered =
        Sql.first(
            db,
            "SELECT payload FROM desktop_word_identity WHERE word_id=?",
            detail.path("id").asText());
    if (remembered != null)
      return Json.object(Json.MAPPER.readTree(remembered.path("payload").asText()));
    return wordRef(
        detail.path("entryId").asText(""),
        detail.path("id").asText(),
        detail.path("word").asText(),
        release(db));
  }

  void importRetraction(Connection db, JsonNode record) throws Exception {
    replay.importRetraction(db, record);
  }

  void importEncounter(Connection db, JsonNode record) throws Exception {
    service.contract.entity(record);
    JsonNode d = record.path("data");
    service.checkEventTime(d.path("occurredAt").asText());
    String event = record.path("entityId").asText();
    var old = entity(db, "encounter", event);
    if (old != null) {
      if (!old.path("data").equals(d))
        throw LmcpService.error("IDEMPOTENCY_CONFLICT", "同一遇见事件内容不同");
      return;
    }
    String word = replay.ensureWord(db, d.path("word"));
    Sql.execute(
        db,
        "INSERT INTO encounters VALUES(?,?,?,?,?,?,0)",
        event,
        word,
        d.path("savedExcerpt").asText(),
        d.path("source").path("title").asText(),
        d.path("source").path("url").asText(""),
        Instant.parse(d.path("occurredAt").asText()).toString());
    Sql.execute(
        db,
        "INSERT INTO encounter_details VALUES(?,?,?,?,?,?,?,?,?,?,?,NULL)",
        event,
        d.path("originalSentence").asText(),
        d.path("savedExcerpt").asText(),
        d.path("annotation").toString(),
        d.path("source").path("kind").asText(),
        d.path("origin").path("deviceId").asText(),
        d.path("occurredAt").asText(),
        service.businessNow(),
        d.path("timeZone").asText(),
        d.path("occurrenceRanges").toString(),
        d.path("excerptRanges").toString());
    if (d.path("collectionIntent").asText().equals("collect"))
      Sql.execute(db, "UPDATE desktop_word_links SET manual_active=1 WHERE word_id=?", word);
    put(db, record);
  }
}
