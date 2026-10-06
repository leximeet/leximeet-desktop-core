package app.leximeet.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;

// 有界词库读模型：先按来源顺序选出当前页，再关联个人覆盖和记忆。 公共词典可有八十多万词，个人关系和词卡不能在分页前对整本逐词装配。
final class DesktopWordPage {
  private static final String LAST = "999999999";

  static ObjectNode query(Connection db, JsonNode input, java.time.Instant now) throws Exception {
    Json.fields(
        input,
        "kind",
        "scope",
        "catalogId",
        "bookId",
        "search",
        "learningStatus",
        "sort",
        "partOfSpeech",
        "offset",
        "limit",
        "wordId",
        "from",
        "to");
    String scope =
        Json.choice(
            input,
            "scope",
            "library",
            "library",
            "dictionary",
            "catalog",
            "book",
            "trash",
            "manual");
    int offset = DesktopWords.integer(input, "offset", 0, 0, 1_000_000);
    int limit = DesktopWords.integer(input, "limit", 50, 1, 100);
    String learningStatus =
        Json.choice(
            input,
            "learningStatus",
            "all",
            "all",
            "new",
            "learning",
            "review",
            "mastered",
            "unfamiliar");
    String sort =
        Json.choice(
            input,
            "sort",
            scope.equals("dictionary") || scope.equals("catalog") ? "source" : "recent",
            "recent",
            "alphabetical",
            "source");
    String part =
        Json.choice(
            input,
            "partOfSpeech",
            "all",
            "all",
            "noun",
            "verb",
            "adjective",
            "adverb",
            "pronoun",
            "preposition",
            "conjunction",
            "determiner",
            "interjection");
    boolean hasParts =
        Sql.first(
                db,
                "SELECT 1 FROM lexicon.sqlite_master WHERE type='table'"
                    + " AND name='entry_parts_of_speech'")
            != null;
    if (!part.equals("all") && !hasParts) throw ApiException.conflict("词性索引正在准备，请完成词典索引后重试");
    String search = PublicLexicon.normalize(Json.text(input, "search", 100, false));
    String goal =
        scope.equals("dictionary")
            ? "dictionary"
            : scope.equals("catalog")
                ? Json.text(input, "catalogId", 100, true)
                : DesktopWords.goal(db);
    List<Object> parameters = new ArrayList<>();
    String candidates;
    if (List.of("manual", "book", "trash").contains(scope)) {
      candidates = personal(scope, goal, input, search, parameters);
    } else {
      candidates =
          publicWords(
              goal, search, parameters, !scope.equals("dictionary") && !scope.equals("catalog"));
      if (scope.equals("library"))
        candidates += " UNION ALL " + personal(scope, goal, input, search, parameters);
    }
    if (!part.equals("all")) {
      candidates =
          "SELECT c.* FROM ("
              + candidates
              + ") c WHERE EXISTS(SELECT 1 FROM"
              + " lexicon.entry_parts_of_speech ep WHERE ep.entry_id=c.entry_id AND ep.pos=?)";
      parameters.add(part);
    }
    DesktopFamiliarity.refresh(db, now);
    // 先过滤全量候选集，再计数/分页；0 分是额外熟悉程度条件，不覆盖学习经历。
    if (!learningStatus.equals("all")) {
      String predicate =
          learningStatus.equals("unfamiliar")
              ? "f.score=0"
              : "COALESCE(f.status,'new')='" + learningStatus + "'";
      candidates =
          "SELECT c.* FROM ("
              + candidates
              + ") c LEFT JOIN desktop_familiarity f"
              + " ON f.word_id=COALESCE(c.word_id,(SELECT word_id FROM desktop_word_links"
              + " WHERE entry_id=c.entry_id ORDER BY word_id LIMIT 1)) WHERE "
              + predicate;
    }
    long total =
        Sql.first(db, "SELECT COUNT(*) AS n FROM (" + candidates + ")", parameters.toArray())
            .path("n")
            .asLong();
    parameters.add(limit);
    parameters.add(offset);
    // UNION ALL 的个人分支已排除目标交集，无需对全量公共 ID 做 UNION 去重。
    // 页内稳定排序仍使用来源位置和身份；最后只对最多 100 条记录关联笔记、状态和记忆。
    String order =
        sort.equals("alphabetical")
            ? "normalized,entry_id,word_id"
            : sort.equals("recent")
                ? "updated_at DESC,sort_position,normalized,entry_id,word_id"
                : "sort_position,normalized,entry_id,word_id";
    String page = "SELECT * FROM (" + candidates + ") ORDER BY " + order + " LIMIT ? OFFSET ?";
    var rows =
        Sql.rows(
            db,
            "SELECT COALESCE(w.id,e.id) AS id,e.id AS entryId,COALESCE(w.word,e.headword) AS"
                + " word,COALESCE(NULLIF(w.meaning,''),e.meaning,'') AS"
                + " meaning,COALESCE(w.phonetic,'') AS phonetic,COALESCE(w.note,'') AS"
                + " note,COALESCE(w.status,'new') AS status,COALESCE(w.revision,0) AS"
                + " revision,COALESCE(l.manual_active,0) AS manualActive,w.deleted_at AS"
                + " deletedAt,card.due_at AS dueAt FROM ("
                + page
                + ") p LEFT JOIN lexicon.entries e ON e.id=p.entry_id LEFT JOIN words w ON"
                + " w.id=COALESCE(p.word_id,(SELECT word_id FROM desktop_word_links WHERE"
                + " entry_id=p.entry_id ORDER BY word_id LIMIT 1)) LEFT JOIN desktop_word_links l"
                + " ON l.word_id=w.id LEFT JOIN desktop_cards card ON card.word_id=w.id ORDER BY"
                + " "
                + java.util.Arrays.stream(order.split(","))
                    .map(field -> "p." + field)
                    .collect(java.util.stream.Collectors.joining(","))
                + "",
            parameters.toArray());
    for (JsonNode row : rows) {
      var parts = Json.MAPPER.createArrayNode();
      if (hasParts && !row.path("entryId").isNull())
        for (JsonNode item :
            Sql.rows(
                db,
                "SELECT pos FROM lexicon.entry_parts_of_speech WHERE" + " entry_id=? ORDER BY pos",
                row.path("entryId").asText())) parts.add(item.path("pos").asText());
      ((ObjectNode) row).set("partsOfSpeech", parts);
      var familiarity = DesktopFamiliarity.assess(db, row.path("id").asText(), now);
      ((ObjectNode) row).set("familiarity", familiarity);
      ((ObjectNode) row).put("learningStatus", DesktopFamiliarity.learningStatus(familiarity));
      if (familiarity.path("samples").asInt() > 0)
        ((ObjectNode) row).put("status", familiarity.path("status").asText());
    }
    return Json.MAPPER
        .createObjectNode()
        .put("total", total)
        .put("offset", offset)
        .put("limit", limit)
        .set("words", rows);
  }

  private static String position(String goal) {
    if (goal.isEmpty() || goal.equals("dictionary")) return "COALESCE(e.position," + LAST + ")";
    return "COALESCE((SELECT position FROM lexicon.members WHERE entry_id=e.id AND catalog_id='"
        + goal.replace("'", "''")
        + "'),"
        + LAST
        + ")";
  }

  private static String publicWords(
      String goal, String search, List<Object> parameters, boolean activeOnly) {
    // 整本分页直接使用 entries_order 索引；COALESCE 包裹会让 SQLite 失去顺序索引。
    String position = goal.isEmpty() || goal.equals("dictionary") ? "e.position" : position(goal);
    String sql =
        "SELECT e.id AS entry_id,e.normalized,"
            + position
            + " AS sort_position,NULL AS word_id,COALESCE((SELECT w.updated_at FROM words w"
            + " JOIN desktop_word_links l ON l.word_id=w.id WHERE l.entry_id=e.id ORDER BY"
            + " w.updated_at DESC LIMIT 1),'') AS updated_at FROM lexicon.entries e WHERE "
            + DesktopWords.predicate(goal);
    if (activeOnly) sql += " AND " + DesktopWords.activeEntry("e");
    if (!search.isEmpty()) {
      // 搜索需考虑旧手动释义覆盖，使用 entry_id 索引查个人小表；不关联全量记忆和词卡正文。
      String meaning =
          "COALESCE((SELECT NULLIF(w.meaning,'') FROM desktop_word_links l JOIN words w"
              + " ON w.id=l.word_id WHERE l.entry_id=e.id ORDER BY w.id LIMIT 1),e.meaning)";
      sql += search("e.normalized", meaning, search, parameters);
    }
    return sql;
  }

  private static String personal(
      String scope, String goal, JsonNode input, String search, List<Object> parameters) {
    String normalized = "COALESCE(e.normalized,LOWER(w.word))";
    String sql =
        "SELECT e.id AS entry_id,"
            + normalized
            + " AS normalized,"
            + position(goal)
            + " AS sort_position,w.id AS word_id,w.updated_at AS updated_at FROM words w LEFT JOIN desktop_word_links l ON"
            + " l.word_id=w.id LEFT JOIN lexicon.entries e ON e.id=l.entry_id WHERE "
            + (scope.equals("trash")
                ? "w.deleted_at IS NOT NULL"
                : "w.deleted_at IS NULL AND COALESCE(l.manual_active,1)=1");
    if (scope.equals("library"))
      sql += " AND (e.id IS NULL OR NOT (" + DesktopWords.predicate(goal) + "))";
    if (scope.equals("book")) {
      sql += " AND w.id IN(SELECT word_id FROM word_books WHERE book_id=?)";
      parameters.add(Json.id(input, "bookId", true));
    }
    if (!search.isEmpty())
      sql += search(normalized, "COALESCE(NULLIF(w.meaning,''),e.meaning,'')", search, parameters);
    return sql;
  }

  private static String search(
      String normalized, String meaning, String search, List<Object> parameters) {
    String escaped =
        "%" + search.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    parameters.add(escaped);
    parameters.add(escaped);
    return " AND (" + normalized + " LIKE ? ESCAPE '\\' OR " + meaning + " LIKE ? ESCAPE '\\')";
  }
}
