package app.leximeet.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Connection;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

// 分页读模型与个人覆盖。目标中的未学词保持虚拟，只有产生个人事实时才物化。
final class DesktopWords {
  final PublicLexicon lexicon;
  private final Clock clock;
  private DesktopDataModel protocolWorkspace;

  void bindProtocolWorkspace(DesktopDataModel workspace) {
    protocolWorkspace = workspace;
  }

  private final WordRelationsService relations = new WordRelationsService();

  DesktopWords(PublicLexicon lexicon, Clock clock) {
    this.lexicon = lexicon;
    this.clock = clock;
  }

  static String goal(Connection db) throws Exception {
    return Sql.first(db, "SELECT goal FROM desktop_profile WHERE id=1").path("goal").asText("");
  }

  static String predicate(String goal) {
    if (goal.equals("dictionary")) return "1=1";
    if (goal.isEmpty()) return "1=0";
    // catalog id 来自本机清单；仍使用字面值转义，避免动态谓词出现 SQL 注入。
    return "e.id IN(SELECT entry_id FROM lexicon.members WHERE catalog_id='"
        + goal.replace("'", "''")
        + "')";
  }

  // 回收的是个人逻辑引用，公共词典本体保持只读；同一公共身份的墓碑优先于目标引用。
  static String activeEntry(String alias) {
    return "NOT EXISTS(SELECT 1 FROM desktop_word_links dl JOIN words dw ON dw.id=dl.word_id"
        + " WHERE dl.entry_id="
        + alias
        + ".id AND dw.deleted_at IS NOT NULL)";
  }

  static String personalJoin(String alias) {
    return "w.id=(SELECT link.word_id FROM desktop_word_links link WHERE link.entry_id="
        + alias
        + ".id LIMIT 1) OR (w.normalized="
        + alias
        + ".normalized AND NOT EXISTS(SELECT 1 FROM desktop_word_links link WHERE link.word_id=w.id"
        + " AND link.entry_id IS NOT NULL))";
  }

  // 词库成员的顺序来自发布清单；整本词典才使用词条的全局顺序。
  static String order(String goal) {
    if (goal.isEmpty() || goal.equals("dictionary")) return "COALESCE(e.position,999999999)";
    return "COALESCE((SELECT m.position FROM lexicon.members m WHERE m.entry_id=e.id AND"
        + " m.catalog_id='"
        + goal.replace("'", "''")
        + "'),999999999)";
  }

  ObjectNode query(Connection db, JsonNode input) throws Exception {
    lexicon.require();
    return DesktopWordPage.query(db, input, clock.instant());
  }

  ObjectNode detail(Connection db, String id) throws Exception {
    ObjectNode personal =
        Sql.first(
            db,
            "SELECT id,word,meaning,phonetic,note,status,revision,deleted_at AS deletedAt FROM"
                + " words WHERE id=? OR normalized=? LIMIT 1",
            id,
            PublicLexicon.normalize(id));
    var link =
        personal == null
            ? null
            : Sql.first(
                db,
                "SELECT entry_id,manual_active FROM desktop_word_links WHERE word_id=?",
                personal.path("id").asText());
    ObjectNode entry =
        lexicon.entry(
            db,
            personal == null
                ? id
                : link != null && !link.path("entry_id").isNull()
                    ? link.path("entry_id").asText()
                    : personal.path("word").asText());
    if (personal == null && entry != null)
      personal =
          Sql.first(
              db,
              "SELECT w.id,w.word,w.meaning,w.phonetic,w.note,w.status,w.revision,w.deleted_at AS"
                  + " deletedAt FROM words w LEFT JOIN desktop_word_links l ON l.word_id=w.id WHERE"
                  + " l.entry_id=? OR (w.normalized=? AND l.entry_id IS NULL) LIMIT 1",
              entry.path("entry_id").asText(),
              entry.path("lookup_key").asText());
    if (personal == null && entry == null) throw ApiException.notFound("词条不存在");
    ObjectNode output =
        personal == null
            ? Json.MAPPER
                .createObjectNode()
                .put("id", entry.path("entry_id").asText())
                .put("word", entry.path("headword").asText())
                .put("meaning", meaning(entry))
                .put("note", "")
                .put("revision", 0)
                .put("status", "new")
            : personal;
    output.set(
        "entry", entry == null ? com.fasterxml.jackson.databind.node.NullNode.instance : entry);
    if (entry != null) {
      output.put("entryId", entry.path("entry_id").asText());
      if (output.path("meaning").asText().isBlank()) output.put("meaning", meaning(entry));
    }
    output.set(
        "books",
        Sql.rows(
            db,
            "SELECT b.id,b.name FROM word_books wb JOIN books b ON b.id=wb.book_id WHERE"
                + " wb.word_id=? AND NOT EXISTS(SELECT 1 FROM desktop_entities e WHERE"
                + " e.entity_type='notebook' AND e.entity_id=b.id AND e.deleted_at IS NOT NULL)",
            output.path("id").asText()));

    output.set(
        "encounters",
        Sql.rows(
            db,
            "SELECT id,context,source_title AS sourceTitle,source_url AS sourceUrl,created_at AS"
                + " createdAt FROM encounters WHERE word_id=? AND id NOT IN(SELECT encounter_id"
                + " FROM encounter_details WHERE undone_at IS NOT NULL) ORDER BY created_at DESC"
                + " LIMIT 50",
            output.path("id").asText()));
    link =
        Sql.first(
            db,
            "SELECT manual_active FROM desktop_word_links WHERE word_id=?",
            output.path("id").asText());
    output.put("manualActive", link != null && link.path("manual_active").asBoolean());
    var review =
        Sql.first(
            db,
            "SELECT COUNT(*) AS count,MAX(created_at) AS last FROM desktop_reviews WHERE word_id=?"
                + " AND undone_at IS NULL",
            output.path("id").asText());
    output.put("reviewCount", review.path("count").asInt());
    output.set("lastReviewedAt", review.path("last"));
    var card =
        Sql.first(
            db, "SELECT due_at FROM desktop_cards WHERE word_id=?", output.path("id").asText());
    if (card != null) output.put("dueAt", card.path("due_at").asText());
    var familiarity = DesktopFamiliarity.assess(db, output.path("id").asText(), clock.instant());
    output.set("familiarity", familiarity);
    if (card != null) output.put("fsrsDueAt", card.path("due_at").asText());
    if (familiarity.path("status").asText().equals("review")) {
      java.time.Instant eligible =
          java.time.Instant.parse(familiarity.path("reviewEligibleAt").asText());
      java.time.Instant due =
          java.time.Instant.parse(
              card != null
                  ? card.path("due_at").asText()
                  : familiarity.path("firstRecallDueAt").asText());
      java.time.Instant automatic = due.isAfter(eligible) ? due : eligible;
      output
          .put("automaticDueAt", automatic.toString())
          .put("reviewDue", !clock.instant().isBefore(automatic));
    }
    if (familiarity.path("samples").asInt() > 0)
      output.put("status", familiarity.path("status").asText());
    return output;
  }

  String materialize(Connection db, String requested, boolean manual) throws Exception {
    ObjectNode detail = detail(db, requested);
    String id = detail.path("id").asText(), entryId = detail.path("entryId").asText("");
    if (detail.path("revision").asInt() == 0) {
      String timestamp = clock.instant().toString();
      // 大小写相同的 lookup_key 不代表同一词条（例如 A 与 a）；公共身份始终使用 entry_id。
      Sql.execute(
          db,
          "INSERT INTO words(id,word,normalized,meaning,created_at,updated_at) VALUES(?,?,?,?,?,?)",
          id,
          detail.path("word").asText(),
          entryId.isEmpty()
              ? PublicLexicon.normalize(detail.path("word").asText())
              : "public:" + entryId,
          // 该列只缓存已经解析的公共释义；未知自定义词没有个人释义输入。
          detail.path("meaning").asText(),
          timestamp,
          timestamp);
      Sql.execute(
          db,
          "INSERT INTO desktop_word_links VALUES(?,?,?)",
          id,
          entryId.isEmpty() ? null : entryId,
          manual ? 1 : 0);
    } else {
      Sql.execute(
          db,
          "INSERT INTO desktop_word_links VALUES(?,?,?) ON CONFLICT(word_id) DO UPDATE SET"
              + " entry_id=COALESCE(excluded.entry_id,desktop_word_links.entry_id),manual_active=MAX(desktop_word_links.manual_active,excluded.manual_active)",
          id,
          entryId.isEmpty() ? null : entryId,
          manual ? 1 : 0);
    }
    if (manual) {
      if (detail.path("deletedAt").isTextual()) throw ApiException.conflict("个人资料在回收站，请先恢复");
      if (PersonalLibrary.activeCount(db) > PersonalLibrary.ACTIVE_LIMIT)
        throw ApiException.conflict("手动词库达到一万词上限");
    }
    return id;
  }

  ObjectNode save(Connection db, JsonNode input, boolean encounter) throws Exception {
    Json.fields(
        input,
        "action",
        "wordId",
        "word",
        "note",
        "context",
        "sourceTitle",
        "sourceUrl",
        "bookIds",
        "expectedRevision");
    // 采集和个人编辑都只接受笔记、词本；释义始终来自只读词典。
    if (input.path("action").asText().equals("saveNote"))
      Json.fields(input, "action", "wordId", "note", "bookIds", "expectedRevision");
    var checkpoint = encounter ? db.setSavepoint() : null;
    var policy = CapturePolicy.preferences(db);
    String id;
    if (input.hasNonNull("wordId")) {
      String requested = Json.id(input, "wordId", true);
      // 公共虚拟词的版本为 0。先比较原版本，再产生个人覆盖，避免首次编辑绕过并发保护。
      if (input.has("expectedRevision")
          && integer(input, "expectedRevision", 0, 0, Integer.MAX_VALUE)
              != detail(db, requested).path("revision").asInt())
        throw ApiException.conflict("词条已变化，草稿保留，请刷新后重试");
      id = materialize(db, requested, true);
    } else {
      if (input.has("expectedRevision")) throw ApiException.badRequest("版本校验需要 wordId");
      String word = Json.text(input, "word", 120, true);
      ObjectNode entry = lexicon.entry(db, word);
      ObjectNode existing =
          Sql.first(db, "SELECT id FROM words WHERE normalized=?", PublicLexicon.normalize(word));
      if (entry != null || existing != null)
        id =
            materialize(
                db,
                existing == null ? entry.path("entry_id").asText() : existing.path("id").asText(),
                true);
      else {
        PersonalLibrary.assertRoomForActivation(db);
        id = UUID.randomUUID().toString();
        String time = clock.instant().toString();
        Sql.execute(
            db,
            "INSERT INTO words(id,word,normalized,meaning,created_at,updated_at)"
                + " VALUES(?,?,?,?,?,?)",
            id,
            word,
            PublicLexicon.normalize(word),
            "",
            time,
            time);
        Sql.execute(db, "INSERT INTO desktop_word_links VALUES(?,NULL,1)", id);
      }
    }
    // 与既有词条编辑共用关系校验；无效、重复或已删除的 ID 返回领域错误并回滚整笔保存。
    WordRelationsService.Selection books = relations.books(db, input);
    if (input.has("note"))
      Sql.execute(
          db,
          "UPDATE words SET note=? WHERE id=?",
          encounter && policy.sensitiveRedactionEnabled()
              ? CapturePolicy.redact(Json.text(input, "note", 20000, false))
              : Json.text(input, "note", 20000, false),
          id);
    // 先更新主归属，再替换完整集合，避免主归属触发器误删重排后仍需保留的其他词本。
    if (books.explicit())
      Sql.execute(
          db,
          "UPDATE words SET book_id=? WHERE id=?",
          books.ids().isEmpty() ? null : books.firstOrEmpty(),
          id);
    relations.replaceBooks(db, id, books);
    if (input.has("note") || books.explicit())
      Sql.execute(
          db,
          "UPDATE words SET revision=revision+1,updated_at=? WHERE id=?",
          clock.instant().toString(),
          id);
    if (!encounter) return null;
    ObjectNode result = saveEncounter(db, id, input);
    // 重复语境连同本次临时收藏、笔记与关系一起撤回，保留原记录。
    if (result.path("captureStatus").asText().equals("duplicate-context")) db.rollback(checkpoint);
    db.releaseSavepoint(checkpoint);
    return result;
  }

  // 手动与剪贴板共用语境校验和写入，采集来源不能改变熟练度或复习日程。
  ObjectNode saveEncounter(Connection db, String id, JsonNode input) throws Exception {
    String context = Json.text(input, "context", 20000, true),
        url = Json.text(input, "sourceUrl", 2000, false);
    if (!url.isEmpty()) {
      java.net.URI uri;
      try {
        uri = java.net.URI.create(url);
      } catch (Exception e) {
        throw ApiException.badRequest("来源链接无效");
      }
      if (!List.of("http", "https").contains(uri.getScheme())
          || uri.getHost() == null
          || uri.getUserInfo() != null) throw ApiException.badRequest("来源链接必须是 HTTP / HTTPS");
    }
    JsonNode detail = detail(db, id);
    String head = detail.path("word").asText();
    var policy = CapturePolicy.preferences(db);
    var segment = CapturePolicy.automaticSegment(context, head, policy);
    if (segment == null) throw ApiException.badRequest("语境中需要包含当前单词；只记录单词时可把语境填为单词本身");
    context = segment.text();
    var ranges = segment.ranges();
    String surface =
        context.substring(ranges.path(0).path("start").asInt(), ranges.path(0).path("end").asInt());
    String title = Json.text(input, "sourceTitle", 300, false);
    boolean clipboard = title.equals("系统剪贴板");
    var data =
        Json.MAPPER
            .createObjectNode()
            .put("surface", surface)
            .put("originalSentence", context)
            .put("savedExcerpt", context)
            .put("timeZone", clock.getZone().getId())
            .put("occurredAt", LmcpService.timestamp(clock.instant()))
            .put("collectionIntent", clipboard ? "context-only" : "collect");
    data.set("word", protocolWorkspace.reference(db, detail));
    data.set("occurrenceRanges", ranges);
    data.set("excerptRanges", ranges.deepCopy());
    data.set("annotation", Json.MAPPER.createObjectNode().put("note", ""));
    data.set(
        "source",
        Json.MAPPER
            .createObjectNode()
            .put("kind", clipboard ? "clipboard" : url.isEmpty() ? "manual" : "web")
            .put("title", title)
            .put("url", url.isEmpty() ? null : url));
    data.set(
        "origin",
        Json.MAPPER
            .createObjectNode()
            .put("deviceId", LmcpService.meta(db, "desktopInstanceId"))
            .put("clientKind", "desktop"));
    data = CapturePolicy.prepare(data, policy);
    String repeated =
        CapturePolicy.duplicate(
            db, id, data.path("savedExcerpt").asText(), policy, clock.instant());
    ObjectNode result = Json.MAPPER.createObjectNode();
    result.set("capturePolicy", policy.json());
    if (repeated != null) {
      protocolWorkspace.synchronize(db);
      result
          .put("captureStatus", "duplicate-context")
          .set(
              "entity",
              CapturePolicy.duplicateProjection(
                  protocolWorkspace.entity(db, "encounter", repeated), policy));
      return result;
    }
    var record = DesktopDataModel.record("encounter", UUID.randomUUID().toString(), 1, data, null);
    protocolWorkspace.importEncounter(db, record);
    return result.put("captureStatus", "created").set("entity", record);
  }

  static int integer(JsonNode input, String key, int fallback, int min, int max) {
    if (!input.has(key)) return fallback;
    JsonNode v = input.get(key);
    if (!v.isIntegralNumber() || !v.canConvertToInt() || v.asInt() < min || v.asInt() > max)
      throw ApiException.badRequest(key + " 超出范围");
    return v.asInt();
  }

  static String meaning(JsonNode entry) {
    String summary = entry.path("headword_summary_zh").asText("");
    if (!summary.isEmpty()) return summary;
    var first = entry.path("senses").path(0);
    return first
        .path("short_gloss")
        .asText(entry.path("ecdict").path("zh_fallback").asText("暂无中文释义"));
  }
}
