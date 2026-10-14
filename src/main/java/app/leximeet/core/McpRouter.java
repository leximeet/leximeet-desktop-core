package app.leximeet.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Connection;
import java.util.Locale;

/** 将路由式 action 转到既有桌面查询与收藏命令，不另开一套学习写入。 */
final class McpRouter {
  private final DesktopWorkspace desktop;

  McpRouter(DesktopWorkspace desktop) {
    this.desktop = desktop;
  }

  ObjectNode query(Connection db, String action, JsonNode args, boolean detail) throws Exception {
    McpCatalog.requireQuery(action);
    return switch (action) {
      case "lookup_word" -> lookup(db, args, detail);
      case "search_words" -> search(db, args);
      case "get_examples" -> examples(db, args);
      case "get_word_status" -> status(db, args);
      case "list_due_reviews" -> dueReviews(db, args);
      case "get_learning_stats" -> stats(db);
      case "list_notebooks" -> notebooks(db, args);
      case "list_encounters" -> encounters(args);
      default -> throw McpService.invalidArgument("未知查询动作：" + action);
    };
  }

  ObjectNode write(String action, JsonNode args) throws Exception {
    McpCatalog.requireWrite(action);
    return addToVocab(args);
  }

  private ObjectNode lookup(Connection db, JsonNode args, boolean detail) throws Exception {
    String id = identity(args);
    ObjectNode card = desktop.words().detail(db, id);
    return detail ? card : compactWord(card, true);
  }

  private ObjectNode search(Connection db, JsonNode args) throws Exception {
    String q = text(args, "q", 120, true);
    Page page = Page.read(args);
    ArrayNode items = Json.MAPPER.createArrayNode();
    String like = q.toLowerCase(Locale.ROOT) + "%";
    String contains = "%" + q.toLowerCase(Locale.ROOT) + "%";
    if (desktop.lexicon().mounted()) {
      for (JsonNode row :
          Sql.rows(
              db,
              "SELECT id,headword FROM lexicon.entries WHERE lower(headword) LIKE ? OR normalized"
                  + " LIKE ? ORDER BY headword,id LIMIT ? OFFSET ?",
              like,
              PublicLexicon.normalize(q) + "%",
              page.limit + 1,
              page.offset)) {
        ObjectNode item =
            Json.MAPPER
                .createObjectNode()
                .put("word", row.path("headword").asText())
                .put("entryId", row.path("id").asText());
        items.add(item);
      }
    }
    if (items.size() <= page.limit) {
      for (JsonNode row :
          Sql.rows(
              db,
              "SELECT id,word FROM words WHERE deleted_at IS NULL AND (lower(word) LIKE ? OR"
                  + " normalized LIKE ?) ORDER BY word,id LIMIT ? OFFSET ?",
              contains,
              PublicLexicon.normalize(q) + "%",
              page.limit + 1 - items.size(),
              items.size() >= page.limit ? 0 : page.offset)) {
        ObjectNode item =
            Json.MAPPER
                .createObjectNode()
                .put("word", row.path("word").asText())
                .put("wordId", row.path("id").asText());
        items.add(item);
      }
    }
    return page.result(items, "words");
  }

  private ObjectNode examples(Connection db, JsonNode args) throws Exception {
    String id = identity(args);
    ObjectNode card = desktop.words().detail(db, id);
    int limit = bounded(args, "limit", 5, 1, 20);
    ArrayNode examples = Json.MAPPER.createArrayNode();
    JsonNode entry = card.path("entry");
    collectExamples(entry, examples, limit);
    if (examples.isEmpty()) {
      String meaning = card.path("meaning").asText("");
      if (!meaning.isBlank()) examples.add(meaning);
    }
    ObjectNode out =
        Json.MAPPER
            .createObjectNode()
            .put("word", card.path("word").asText())
            .put("entryId", card.path("entryId").asText(""));
    out.set("examples", examples);
    return out;
  }

  private static void collectExamples(JsonNode node, ArrayNode sink, int limit) {
    if (node == null || node.isMissingNode() || node.isNull() || sink.size() >= limit) return;
    if (node.isTextual()) {
      String text = node.asText().strip();
      if (text.length() > 8 && text.contains(" "))
        sink.add(text.length() > 240 ? text.substring(0, 240) : text);
      return;
    }
    if (node.isArray()) {
      for (JsonNode child : node) collectExamples(child, sink, limit);
      return;
    }
    if (!node.isObject()) return;
    for (String key : new String[] {"examples", "example", "sentences", "sample"})
      if (node.has(key)) collectExamples(node.get(key), sink, limit);
    if (sink.size() < limit && node.has("senses")) collectExamples(node.get("senses"), sink, limit);
  }

  private ObjectNode status(Connection db, JsonNode args) throws Exception {
    ObjectNode card = desktop.words().detail(db, identity(args));
    var familiarity = DesktopFamiliarity.assess(db, card.path("id").asText());
    ObjectNode out =
        Json.MAPPER
            .createObjectNode()
            .put("word", card.path("word").asText())
            .put("wordId", card.path("id").asText())
            .put("collected", card.path("manualActive").asBoolean())
            .put("status", familiarity.path("status").asText(card.path("status").asText("new")))
            .put("score", familiarity.path("score").asInt(10));
    out.put("inTrash", !card.path("deletedAt").isNull() && card.path("deletedAt").isTextual());
    return out;
  }

  private ObjectNode dueReviews(Connection db, JsonNode args) throws Exception {
    Page page = Page.read(args);
    ArrayNode items = Json.MAPPER.createArrayNode();
    for (JsonNode row : desktop.queue(db).path("reviews")) {
      items.add(
          Json.MAPPER
              .createObjectNode()
              .put("wordId", row.path("id").asText())
              .put("word", row.path("word").asText())
              .put("kind", row.path("kind").asText())
              .put("source", row.path("source").asText()));
    }
    return page.slice(items, "reviews");
  }

  private ObjectNode stats(Connection db) throws Exception {
    ObjectNode state = desktop.state();
    ObjectNode queue = (ObjectNode) state.path("queue");
    ObjectNode insights = (ObjectNode) state.path("insights");
    return Json.MAPPER
        .createObjectNode()
        .put("today", state.path("today").asText())
        .put("newDone", queue.path("newDone").asInt())
        .put("reviewDone", queue.path("reviewDone").asInt())
        .put("newRemaining", queue.path("newRemaining").asInt())
        .put("reviewRemaining", queue.path("reviewRemaining").asInt())
        .put("learned", insights.path("learned").asLong())
        .put("encounters", insights.path("encounters").asLong())
        .put("practice", insights.path("practice").asLong())
        .put("manualWords", insights.path("manualWords").asLong());
  }

  private ObjectNode notebooks(Connection db, JsonNode args) throws Exception {
    Page page = Page.read(args);
    ArrayNode items = Json.MAPPER.createArrayNode();
    for (JsonNode row : desktop.state().path("books")) {
      items.add(
          Json.MAPPER
              .createObjectNode()
              .put("id", row.path("id").asText())
              .put("name", row.path("name").asText())
              .put("count", row.path("count").asInt())
              .put("role", row.path("role").asText("")));
    }
    return page.slice(items, "notebooks");
  }

  private ObjectNode encounters(JsonNode args) throws Exception {
    Page page = Page.read(args);
    ObjectNode query = Json.MAPPER.createObjectNode().put("kind", "encounters");
    query.put("search", optional(args, "q", 100));
    query.put("from", optional(args, "from", 10));
    query.put("to", optional(args, "to", 10));
    query.put("limit", page.limit);
    query.put("offset", page.offset);
    ObjectNode raw = desktop.query(query);
    ObjectNode out = Json.MAPPER.createObjectNode().put("total", raw.path("total").asLong());
    ArrayNode items = out.putArray("encounters");
    for (JsonNode row : raw.path("encounters")) {
      items.add(
          Json.MAPPER
              .createObjectNode()
              .put("id", row.path("id").asText())
              .put("word", row.path("word").asText())
              .put("wordId", row.path("wordId").asText())
              .put("context", clip(row.path("context").asText(), 180))
              .put("createdAt", row.path("createdAt").asText()));
    }
    if (raw.path("encounters").size() == page.limit)
      out.put("nextCursor", Integer.toString(page.offset + page.limit));
    return out;
  }

  private ObjectNode addToVocab(JsonNode args) throws Exception {
    String word = text(args, "word", 120, true);
    String mutation = optional(args, "mutationId", 80);
    if (!mutation.isEmpty()) {
      ObjectNode replayed = replay(mutation);
      if (replayed != null) return replayed;
    }
    ObjectNode command = Json.MAPPER.createObjectNode().put("word", word);
    String note = optional(args, "note", 20000);
    if (!note.isEmpty()) command.put("note", note);
    String notebook = optional(args, "notebookId", 80);
    if (!notebook.isEmpty()) command.putArray("bookIds").add(notebook);
    String context = optional(args, "context", 20000);
    if (context.isEmpty()) command.put("action", "collect");
    else {
      command
          .put("action", "capture")
          .put("context", context)
          .put("sourceTitle", "AI 助手")
          .put("sourceUrl", "");
    }
    ObjectNode result = desktop.command(command);
    ObjectNode out =
        Json.MAPPER
            .createObjectNode()
            .put("ok", true)
            .put("word", word)
            .put(
                "captureStatus",
                result.path("captureResult").path("captureStatus").asText("collected"));
    if (!mutation.isEmpty()) remember(mutation, out);
    return out;
  }

  private ObjectNode replay(String mutationId) throws Exception {
    return desktop
        .database()
        .read(
            db -> {
              var row =
                  Sql.first(
                      db, "SELECT value FROM personal_meta WHERE key=?", mutationKey(mutationId));
              return row == null
                  ? null
                  : Json.object(Json.MAPPER.readTree(row.path("value").asText()));
            });
  }

  private void remember(String mutationId, ObjectNode result) throws Exception {
    desktop
        .database()
        .transaction(
            db -> {
              Sql.execute(
                  db,
                  "INSERT OR REPLACE INTO personal_meta(key,value) VALUES(?,?)",
                  mutationKey(mutationId),
                  result.toString());
              return null;
            });
  }

  private static String mutationKey(String mutationId) {
    if (!mutationId.matches("[A-Za-z0-9_-]{8,80}"))
      throw McpService.invalidArgument("mutationId 格式无效");
    return "mcp.mutation." + mutationId;
  }

  private static ObjectNode compactWord(ObjectNode card, boolean includeMeaning) {
    ObjectNode out =
        Json.MAPPER
            .createObjectNode()
            .put("word", card.path("word").asText())
            .put("wordId", card.path("id").asText())
            .put("entryId", card.path("entryId").asText(""))
            .put("collected", card.path("manualActive").asBoolean())
            .put("status", card.path("status").asText("new"));
    if (includeMeaning) out.put("meaning", clip(card.path("meaning").asText(), 160));
    return out;
  }

  private static String identity(JsonNode args) {
    for (String key : new String[] {"wordId", "entryId", "word", "q"}) {
      String value = optional(args, key, 120);
      if (!value.isEmpty()) return value;
    }
    throw McpService.invalidArgument("需要 q、word、wordId 或 entryId");
  }

  private static String text(JsonNode args, String key, int max, boolean required) {
    String value = optional(args, key, max);
    if (required && value.isEmpty()) throw McpService.invalidArgument(key + " 不能为空");
    return value;
  }

  private static String optional(JsonNode args, String key, int max) {
    if (!args.has(key) || args.get(key).isNull()) return "";
    if (!args.get(key).isTextual() && !args.get(key).isNumber())
      throw McpService.invalidArgument(key + " 必须是文本");
    String value = args.get(key).asText().strip();
    if (value.length() > max) throw McpService.invalidArgument(key + " 过长");
    return value;
  }

  private static int bounded(JsonNode args, String key, int fallback, int min, int max) {
    if (!args.has(key) || args.get(key).isNull()) return fallback;
    if (!args.get(key).canConvertToInt()) throw McpService.invalidArgument(key + " 必须是整数");
    int value = args.get(key).asInt();
    if (value < min || value > max) throw McpService.invalidArgument(key + " 超出范围");
    return value;
  }

  private static String clip(String text, int max) {
    if (text == null) return "";
    return text.length() <= max ? text : text.substring(0, max);
  }

  record Page(int limit, int offset) {
    static Page read(JsonNode args) {
      int limit = bounded(args, "limit", McpCatalog.DEFAULT_LIMIT, 1, McpCatalog.MAX_LIMIT);
      int offset = 0;
      if (args.has("cursor") && !args.get("cursor").isNull()) {
        String cursor = args.get("cursor").asText("");
        if (!cursor.matches("\\d{1,7}")) throw McpService.invalidArgument("cursor 无效");
        offset = Integer.parseInt(cursor);
      }
      return new Page(limit, offset);
    }

    ObjectNode result(ArrayNode items, String key) {
      boolean more = items.size() > limit;
      ArrayNode page = Json.MAPPER.createArrayNode();
      int end = Math.min(limit, items.size());
      for (int i = 0; i < end; i++) page.add(items.get(i));
      ObjectNode out = Json.MAPPER.createObjectNode();
      out.set(key, page);
      if (more) out.put("nextCursor", Integer.toString(offset + limit));
      return out;
    }

    ObjectNode slice(ArrayNode all, String key) {
      ArrayNode page = Json.MAPPER.createArrayNode();
      for (int i = offset; i < all.size() && page.size() < limit; i++) page.add(all.get(i));
      ObjectNode out = Json.MAPPER.createObjectNode();
      out.set(key, page);
      if (offset + limit < all.size()) out.put("nextCursor", Integer.toString(offset + limit));
      return out;
    }
  }
}
