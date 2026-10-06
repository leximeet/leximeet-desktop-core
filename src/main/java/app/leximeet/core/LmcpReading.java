package app.leximeet.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Connection;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

// 1.0.0 只读词卡、有限匹配与原子采集；私人内容只有笔记，不提供释义覆盖或标签。
final class LmcpReading {
  private final LmcpService service;
  private final DesktopDataModel model;

  LmcpReading(LmcpService service, DesktopDataModel model) {
    this.service = service;
    this.model = model;
  }

  JsonNode dispatch(Connection db, LmcpService.Caller caller, String method, JsonNode p)
      throws Exception {
    model.synchronize(db);
    return switch (method) {
      case "getWorkspace" -> workspace(db, caller);
      case "getChanges" -> changes(db, caller, p);
      case "getWord" -> summary(db, p.path("word"));
      case "getPublicEntry" -> publicEntry(db, p);
      case "matchWords" -> match(db, p);
      case "listNotebooks" -> notebooks(db, caller, p);
      case "listEncounters" -> encounters(db, caller, p);
      default -> throw LmcpService.error("METHOD_NOT_FOUND", "未定义的只读方法");
    };
  }

  private ObjectNode workspace(Connection db, LmcpService.Caller caller) throws Exception {
    var out =
        Json.MAPPER
            .createObjectNode()
            .put("revision", revision(db))
            .put("dictionaryRelease", model.release(db))
            .put(
                "dictionaryEdition", model.lexicon.metadata(db).path("edition").asText("core-text"))
            .put("cloudSync", "disabled")
            .put("readLeaseUntil", lease(caller));
    out.set("owner", service.owner(db, caller));
    out.set("capturePolicy", CapturePolicy.preferences(db).json());
    out.set(
        "account",
        Json.MAPPER.createObjectNode().put("status", "unavailable").put("source", "desktop"));
    return out;
  }

  private ObjectNode changes(Connection db, LmcpService.Caller caller, JsonNode p)
      throws Exception {
    String revision = revision(db);
    if (new java.math.BigInteger(p.path("sinceRevision").asText())
            .compareTo(new java.math.BigInteger(revision))
        > 0) throw LmcpService.error("GENERATION_MISMATCH", "修订高于当前工作区，请重读上下文");
    return Json.MAPPER
        .createObjectNode()
        .put("revision", revision)
        .put("changed", !revision.equals(p.path("sinceRevision").asText()))
        .put("readLeaseUntil", lease(caller));
  }

  private String lease(LmcpService.Caller caller) {
    Instant until = service.clock.instant().plusSeconds(30),
        expiry = Instant.parse(caller.expiresAt());
    return LmcpService.timestamp(until.isBefore(expiry) ? until : expiry);
  }

  private String revision(Connection db) throws Exception {
    return LmcpService.meta(db, "revision");
  }

  // 查询身份不改为当前资源版本；缺失版本只影响资源状态，不改变私人事实。
  ObjectNode summary(Connection db, JsonNode ref) throws Exception {
    JsonNode personal = model.entity(db, "word", DesktopDataModel.key(ref));
    if (personal != null && !personal.path("data").path("word").equals(ref)) {
      if (ref.path("kind").asText().equals("custom"))
        throw LmcpService.error("INVALID_ARGUMENT", "自定义身份与既有词头不同");
    }
    JsonNode entry = entry(db, ref);
    String local = localId(db, ref);
    var projection = DesktopFamiliarity.assess(db, local, service.businessClock.instant());
    var out =
        Json.MAPPER
            .createObjectNode()
            .put(
                "collected",
                personal != null
                    && personal.path("deletedAt").isNull()
                    && personal.path("data").path("collected").asBoolean())
            .put("inTarget", entry != null && model.inTarget(db, ref))
            .put(
                "resourceStatus",
                entry != null || ref.path("kind").asText().equals("custom")
                    ? "available"
                    : "missing")
            .put("refreshAfterMs", 30000);
    out.set("word", ref);
    out.put(
        "headword",
        entry != null
            ? entry.path("headword").asText()
            : ref.path("kind").asText().equals("custom") ? ref.path("headword").asText() : null);
    out.set(
        "personal",
        personal == null
            ? NullNode.instance
            : Json.MAPPER
                .createObjectNode()
                .put("note", personal.path("data").path("note").asText()));
    out.set(
        "learning",
        Json.MAPPER
            .createObjectNode()
            .put("status", projection.path("status").asText("new"))
            .put("score", projection.path("score").asInt(10))
            .put("asOf", service.businessNow()));
    return out;
  }

  private JsonNode entry(Connection db, JsonNode ref) throws Exception {
    if (!ref.path("kind").asText().equals("dictionary")
        || !ref.path("release").asText().equals(model.release(db))) return null;
    return model.lexicon.entry(db, ref.path("entryId").asText());
  }

  private ObjectNode publicEntry(Connection db, JsonNode p) throws Exception {
    var ref =
        DesktopDataModel.wordRef(p.path("entryId").asText(), "", "", p.path("release").asText());
    JsonNode entry = entry(db, ref);
    if (entry == null) throw LmcpService.error("RESOURCE_UNAVAILABLE", "所需词典版本或词条不可用");
    return Json.MAPPER
        .createObjectNode()
        .put("release", p.path("release").asText())
        .set("entry", entry);
  }

  private String localId(Connection db, JsonNode ref) throws Exception {
    if (ref.path("kind").asText().equals("custom")) return ref.path("customId").asText();
    var row =
        Sql.first(
            db,
            "SELECT word_id FROM desktop_word_links WHERE entry_id=? ORDER BY word_id LIMIT 1",
            ref.path("entryId").asText());
    return row == null ? ref.path("entryId").asText() : row.path("word_id").asText();
  }

  private ObjectNode match(Connection db, JsonNode p) throws Exception {
    var out =
        Json.MAPPER
            .createObjectNode()
            .put("workspaceRevision", revision(db))
            .put("asOf", service.businessNow())
            .put("refreshAfterMs", 30000);
    var results = out.putArray("results");
    JsonNode inputs = p.path(p.path("kind").asText().equals("ids") ? "words" : "tokens");
    int index = 0;
    for (JsonNode input : inputs) {
      var result = results.addObject().put("inputIndex", index++).put("complete", true);
      var matches = result.putArray("matches");
      var refs = new java.util.TreeMap<String, JsonNode>();
      if (p.path("kind").asText().equals("ids")) refs.put(DesktopDataModel.key(input), input);
      else {
        String token = input.asText();
        // 精确词头优先；同级稳定按身份排序。查询键不能将两个 entryId 合为一个词。
        JsonNode publicRows =
            model.lexicon.mounted()
                ? Sql.rows(
                    db,
                    "SELECT id,headword FROM lexicon.entries WHERE headword=? ORDER BY id LIMIT 11",
                    token)
                : Json.MAPPER.createArrayNode();
        JsonNode customRows = customMatches(db, token);
        // 两类身份一起判断精确匹配，不能用公共查询键盖过一个精确自定义词头。
        if (publicRows.isEmpty() && customRows.isEmpty()) {
          if (model.lexicon.mounted())
            publicRows =
                Sql.rows(
                    db,
                    "SELECT id,headword FROM lexicon.entries WHERE normalized=? ORDER BY id LIMIT"
                        + " 11",
                    PublicLexicon.normalize(token));
        }
        for (JsonNode row : publicRows) {
          var ref =
              DesktopDataModel.wordRef(
                  row.path("id").asText(), "", row.path("headword").asText(), model.release(db));
          refs.put(DesktopDataModel.key(ref), ref);
        }
        for (JsonNode row : customRows) {
          var ref =
              DesktopDataModel.wordRef(
                  "", row.path("id").asText(), row.path("word").asText(), model.release(db));
          refs.put(DesktopDataModel.key(ref), ref);
        }
      }
      if (refs.size() > 10) result.put("complete", false);
      int count = 0;
      for (JsonNode ref : refs.values()) {
        if (count++ >= 10) break;
        var summary = summary(db, ref);
        if (summary.path("headword").isNull()) continue;
        var item =
            matches
                .addObject()
                .put("headword", summary.path("headword").asText())
                .put("inTarget", summary.path("inTarget").asBoolean())
                .put("collected", summary.path("collected").asBoolean())
                .put("learningStatus", summary.path("learning").path("status").asText())
                .put("score", summary.path("learning").path("score").asInt());
        item.set("word", ref);
      }
    }
    return out;
  }

  private JsonNode customMatches(Connection db, String token) throws Exception {
    return Sql.rows(
        db,
        "SELECT id,word FROM words w WHERE w.word=? AND NOT EXISTS(SELECT 1 FROM desktop_word_links"
            + " l WHERE l.word_id=w.id AND l.entry_id IS NOT NULL) ORDER BY id LIMIT 11",
        token);
  }

  ObjectNode encounter(Connection db, LmcpService.Caller caller, JsonNode p) throws Exception {
    model.synchronize(db);
    ObjectNode data = Json.object(p.path("data")).deepCopy();
    JsonNode ref = data.path("word");
    String event = p.path("eventId").asText(), notebook = p.path("notebookId").asText(null);
    validateRanges(
        data.path("originalSentence").asText(),
        data.path("surface").asText(),
        data.path("occurrenceRanges"));
    validateRanges(
        data.path("savedExcerpt").asText(),
        data.path("surface").asText(),
        data.path("excerptRanges"));
    validateSource(data.path("source"));
    var eventInput = Json.MAPPER.createObjectNode();
    eventInput.set("data", data);
    eventInput.set("notebookId", p.path("notebookId"));
    String fingerprint = LmcpContract.digest(eventInput);
    var duplicate = Sql.first(db, "SELECT * FROM lmcp_capture_events WHERE event_id=?", event);
    if (duplicate != null) {
      if (!duplicate.path("input_hash").asText().equals(fingerprint))
        throw LmcpService.error("EVENT_ID_REUSED", "遇见事件标识已用于不同内容");
      return Json.object(Json.MAPPER.readTree(duplicate.path("result").asText()));
    }
    JsonNode summary = summary(db, ref);
    JsonNode old = model.entity(db, "word", DesktopDataModel.key(ref));
    if (old != null && !old.path("deletedAt").isNull())
      throw LmcpService.error("ENTITY_DELETED", "该词已回收，请先在桌面恢复");
    if (ref.path("kind").asText().equals("dictionary") && entry(db, ref) == null)
      throw LmcpService.error("RESOURCE_UNAVAILABLE", "所需公共词资源不可用");
    if (notebook != null) {
      var group = model.entity(db, "notebook", notebook);
      if (group == null) throw LmcpService.error("ENTITY_NOT_FOUND", "单词本不存在");
      if (!group.path("deletedAt").isNull()) throw LmcpService.error("ENTITY_DELETED", "单词本已回收");
    }
    var policy = CapturePolicy.preferences(db);
    data = CapturePolicy.prepare(data, policy);
    String local = localId(db, ref);
    String repeated =
        CapturePolicy.duplicate(
            db, local, data.path("savedExcerpt").asText(), policy, service.businessClock.instant());
    if (repeated != null) {
      var result =
          Json.MAPPER
              .createObjectNode()
              .put("workspaceRevision", revision(db))
              .put("captureStatus", "duplicate-context");
      result.set(
          "entity",
          CapturePolicy.duplicateProjection(model.entity(db, "encounter", repeated), policy));
      result.set("capturePolicy", policy.json());
      Sql.execute(
          db,
          "INSERT INTO lmcp_capture_events VALUES(?,?,?)",
          event,
          fingerprint,
          result.toString());
      return result;
    }
    data.put("timeZone", DesktopPlan.state(db).path("studyZone").asText())
        .put("occurredAt", service.businessNow());
    data.set(
        "origin",
        Json.MAPPER
            .createObjectNode()
            .put("deviceId", LmcpService.meta(db, "desktopInstanceId"))
            .put("clientKind", "desktop"));
    if (Sql.first(db, "SELECT id FROM words WHERE id=?", local) == null) {
      PersonalLibrary.assertRoomForActivation(db);
      var personal =
          Json.MAPPER
              .createObjectNode()
              .put("note", data.path("annotation").path("note").asText())
              .put("collected", true);
      personal.set("word", ref);
      personal.putArray("notebookIds");
      model.mirrorWord(
          db, DesktopDataModel.record("word", DesktopDataModel.key(ref), 1, personal, null));
      local = DesktopDataModel.id(ref);
    } else {
      if (!summary.path("collected").asBoolean()) PersonalLibrary.assertRoomForActivation(db);
      Sql.execute(db, "UPDATE desktop_word_links SET manual_active=1 WHERE word_id=?", local);
    }
    var record = DesktopDataModel.record("encounter", event, 1, data, null);
    Sql.execute(
        db,
        "INSERT INTO encounters VALUES(?,?,?,?,?,?,0)",
        event,
        local,
        data.path("savedExcerpt").asText(),
        data.path("source").path("title").asText(),
        data.path("source").path("url").asText(""),
        service.businessNow());
    Sql.execute(
        db,
        "INSERT INTO encounter_details VALUES(?,?,?,?,?,?,?,?,?,?,?,NULL)",
        event,
        data.path("originalSentence").asText(),
        data.path("savedExcerpt").asText(),
        data.path("annotation").toString(),
        data.path("source").path("kind").asText(),
        caller.clientId(),
        service.businessNow(),
        service.businessNow(),
        data.path("timeZone").asText(),
        data.path("occurrenceRanges").toString(),
        data.path("excerptRanges").toString());
    if (notebook != null)
      Sql.execute(db, "INSERT OR IGNORE INTO word_books VALUES(?,?)", local, notebook);
    model.put(db, record);
    model.synchronize(db);
    var result =
        Json.MAPPER
            .createObjectNode()
            .put("workspaceRevision", revision(db))
            .put("captureStatus", "created");
    result.set("capturePolicy", policy.json());
    result.set("entity", record);
    Sql.execute(
        db, "INSERT INTO lmcp_capture_events VALUES(?,?,?)", event, fingerprint, result.toString());
    return result;
  }

  static void validateRanges(String sentence, String surface, JsonNode ranges) {
    int previous = -1;
    for (JsonNode range : ranges) {
      int from = range.path("start").asInt(), to = range.path("end").asInt();
      if (from < 0
          || from < previous
          || from >= to
          || to > sentence.length()
          || !sentence.substring(from, to).equals(surface)
          || from > 0 && Character.isLowSurrogate(sentence.charAt(from))
          || to < sentence.length() && Character.isLowSurrogate(sentence.charAt(to)))
        throw LmcpService.error("INVALID_OCCURRENCE_RANGE", "语境范围必须准确指向原文，使用 UTF-16 半开下标");
      previous = to;
    }
  }

  static void validateSource(JsonNode source) {
    if (source.path("kind").asText().equals("web")) {
      try {
        var uri = java.net.URI.create(source.path("url").asText());
        if (!List.of("https", "http").contains(uri.getScheme())
            || uri.getHost() == null
            || uri.getUserInfo() != null
            || uri.getFragment() != null) throw new IllegalArgumentException();
      } catch (Exception failure) {
        throw LmcpService.error("INVALID_SOURCE_URL", "网页来源必须是无账号密码和 fragment 的 HTTP 地址");
      }
    } else if (!source.path("kind").asText().equals("manual") || !source.path("url").isNull())
      throw LmcpService.error("INVALID_SOURCE_URL", "手动采集不应带网页地址");
  }

  ObjectNode navigation(Connection db, JsonNode p) throws Exception {
    var out = Json.MAPPER.createObjectNode().put("opened", false);
    if (p.path("target").asText().equals("word")) {
      JsonNode ref = p.path("word"), entry = entry(db, ref);
      if (ref.path("kind").asText().equals("dictionary") && entry == null)
        throw LmcpService.error("RESOURCE_UNAVAILABLE", "所需公共词资源不可用");
      if (ref.path("kind").asText().equals("custom")
          && model.entity(db, "word", DesktopDataModel.key(ref)) == null)
        throw LmcpService.error("ENTITY_NOT_FOUND", "自定义词不存在");
      out.put("uiWordId", localId(db, ref));
      out.put(
          "uiSearchTerm",
          entry != null ? entry.path("headword").asText() : ref.path("headword").asText());
    }
    return out;
  }

  private ObjectNode notebooks(Connection db, LmcpService.Caller caller, JsonNode p)
      throws Exception {
    String revision = revision(db);
    var page =
        LmcpCursor.read(
            service,
            db,
            "notebooks:" + caller.pairingId() + ":" + caller.pairing().path("epoch").asText(),
            p,
            revision);
    int limit = p.path("limit").asInt(100);
    var items = Json.MAPPER.createArrayNode();
    for (JsonNode row :
        Sql.rows(
            db,
            "SELECT * FROM desktop_entities WHERE entity_type='notebook' AND deleted_at IS NULL"
                + " ORDER BY entity_id LIMIT ? OFFSET ?",
            limit + 1,
            page.offset())) items.add(DesktopDataModel.fromRow(row));
    return LmcpCursor.result(items, page, limit).put("revision", revision);
  }

  private ObjectNode encounters(Connection db, LmcpService.Caller caller, JsonNode p)
      throws Exception {
    String revision = revision(db);
    if (p.has("from")
        && p.has("to")
        && p.path("from").asText().compareTo(p.path("to").asText()) > 0)
      throw LmcpService.error("INVALID_ARGUMENT", "开始日期不能晚于结束日期");
    var page =
        LmcpCursor.read(
            service,
            db,
            "encounters:" + caller.pairingId() + ":" + caller.pairing().path("epoch").asText(),
            p,
            revision);
    int limit = p.path("limit").asInt(20), accepted = 0;
    var items = Json.MAPPER.createArrayNode();
    ZoneId zone = ZoneId.of(DesktopPlan.state(db).path("studyZone").asText());
    for (JsonNode row :
        Sql.rows(
            db,
            "SELECT * FROM desktop_entities WHERE entity_type='encounter' AND deleted_at IS NULL"
                + " ORDER BY json_extract(payload,'$.occurredAt') DESC,entity_id")) {
      JsonNode data = Json.MAPPER.readTree(row.path("payload").asText());
      if (!data.path("word").equals(p.path("word"))) continue;
      if (Sql.first(
              db,
              "SELECT encounter_id FROM encounter_details WHERE encounter_id=? AND undone_at IS NOT"
                  + " NULL",
              row.path("entity_id").asText())
          != null) continue;
      String day =
          Instant.parse(data.path("occurredAt").asText()).atZone(zone).toLocalDate().toString();
      if (p.has("from") && day.compareTo(p.path("from").asText()) < 0
          || p.has("to") && day.compareTo(p.path("to").asText()) > 0) continue;
      if (accepted++ >= page.offset()) items.add(DesktopDataModel.fromRow(row));
      if (items.size() > limit) break;
    }
    return LmcpCursor.result(items, page, limit).put("revision", revision);
  }
}
