package app.leximeet.core;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Connection;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

// 桌面词库的有界批量操作。同一个事务先校验全部身份与归属，再修改个人引用。
final class DesktopLibraryOperations {
  private DesktopLibraryOperations() {}

  static List<String> ids(JsonNode input, String key) {
    JsonNode values = input.path(key);
    if (!values.isArray() || values.isEmpty() || values.size() > 100)
      throw ApiException.badRequest(key + " 必须包含 1 至 100 个 ID");
    var result = new ArrayList<String>();
    var unique = new HashSet<String>();
    for (JsonNode value : values) {
      String id = Json.id(Json.MAPPER.createObjectNode().set("id", value), "id", true);
      if (!unique.add(id)) throw ApiException.badRequest(key + " 不能包含重复 ID");
      result.add(id);
    }
    return List.copyOf(result);
  }

  static void trash(
      Connection db, JsonNode input, DesktopWords words, Clock clock, boolean restore, boolean bulk)
      throws Exception {
    Json.fields(input, "action", bulk ? "wordIds" : "wordId");
    List<String> requested = bulk ? ids(input, "wordIds") : List.of(Json.id(input, "wordId", true));
    var resolved = new HashSet<String>();
    for (String id : requested)
      if (!resolved.add(words.detail(db, id).path("id").asText()))
        throw ApiException.badRequest("不能重复选择同一单词的不同身份别名");
    boolean changed = false;
    for (String requestedId : requested) {
      JsonNode detail = words.detail(db, requestedId);
      boolean deleted = detail.path("deletedAt").isTextual();
      if (restore != deleted) continue;
      // 虚拟目标词只写个人墓碑，不复制词典、不激活主动采集身份。
      String id = restore ? detail.path("id").asText() : words.materialize(db, requestedId, false);
      if (restore && detail.path("manualActive").asBoolean())
        PersonalLibrary.assertRoomForActivation(db);
      Sql.execute(
          db,
          "UPDATE words SET deleted_at=?,revision=revision+1,updated_at=? WHERE id=?",
          restore ? null : clock.instant().toString(),
          clock.instant().toString(),
          id);
      // manual_active 表示来源事实，回收/恢复不改它，避免目标引用被错误变成额外收藏。
      changed = true;
    }
    if (changed) {
      // 词库成员改变后重建本机冻结队列，旧题/答题事件保留供审计和撤销。
      Sql.execute(db, "DELETE FROM desktop_practice_scopes");
    }
  }

  static void addToBooks(Connection db, JsonNode input, DesktopWords words, Clock clock)
      throws Exception {
    Json.fields(input, "action", "wordIds", "bookIds");
    List<String> requested = ids(input, "wordIds");
    var relations = new WordRelationsService();
    var selection = relations.books(db, input);
    if (selection.ids().isEmpty()) throw ApiException.badRequest("请选择至少一个单词本");
    var resolved = new HashSet<String>();
    for (String id : requested) {
      JsonNode detail = words.detail(db, id);
      if (!resolved.add(detail.path("id").asText()))
        throw ApiException.badRequest("不能重复选择同一单词的不同身份别名");
      if (detail.path("deletedAt").isTextual()) throw ApiException.conflict("请先恢复回收站中的单词");
      var existing =
          Sql.rows(
              db, "SELECT book_id FROM word_books WHERE word_id=?", detail.path("id").asText());
      var combined = new HashSet<>(selection.ids());
      for (JsonNode row : existing) combined.add(row.path("book_id").asText());
      if (combined.size() > 100) throw ApiException.badRequest("每个单词最多加入 100 个单词本");
    }
    for (String requestedId : requested) {
      String id = words.materialize(db, requestedId, true);
      int inserted = 0;
      for (String book : selection.ids())
        inserted += Sql.executeCount(db, "INSERT OR IGNORE INTO word_books VALUES(?,?)", id, book);
      if (inserted > 0)
        Sql.execute(
            db,
            "UPDATE words SET revision=revision+1,updated_at=? WHERE id=?",
            clock.instant().toString(),
            id);
    }
  }
}
