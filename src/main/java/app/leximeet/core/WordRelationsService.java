package app.leximeet.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

// 词本归属的领域边界：完整集合在词条事务中校验并替换；任何无效、重复或已回收 ID 都使整笔回滚。
final class WordRelationsService {
  private static final int MAX_BOOKS_PER_WORD = 100;

  record Selection(boolean explicit, List<String> ids) {
    String firstOrEmpty() {
      return ids.isEmpty() ? "" : ids.getFirst();
    }
  }

  Selection books(Connection db, JsonNode input) throws Exception {
    if (!input.has("bookIds")) return new Selection(false, List.of());
    List<String> ids = ids(input, "bookIds", MAX_BOOKS_PER_WORD);
    for (String id : ids) require(db, id);
    return new Selection(true, ids);
  }

  // 已在同一个词条事务中调用；完整集合只有全部插入成功后才会提交。
  void replaceBooks(Connection db, String wordId, Selection books) throws Exception {
    if (!books.explicit()) return;
    Sql.execute(db, "DELETE FROM word_books WHERE word_id=?", wordId);
    for (String id : books.ids())
      Sql.execute(db, "INSERT INTO word_books(word_id,book_id) VALUES(?,?)", wordId, id);
  }

  private static List<String> ids(JsonNode input, String key, int max) {
    JsonNode values = input.get(key);
    if (values == null || !values.isArray() || values.size() > max)
      throw ApiException.badRequest(key + " 必须是最多 " + max + " 项的数组");
    List<String> ids = new ArrayList<>();
    Set<String> unique = new HashSet<>();
    for (JsonNode value : values) {
      ObjectNode wrapper = Json.MAPPER.createObjectNode().set("id", value);
      String id = Json.id(wrapper, "id", true);
      if (!unique.add(id)) throw ApiException.badRequest(key + " 不能包含重复 ID");
      ids.add(id);
    }
    return List.copyOf(ids);
  }

  private static void require(Connection db, String id) throws Exception {
    ObjectNode row = Sql.first(db, "SELECT id FROM books WHERE id=?", id);
    if (row == null
        || Sql.first(
                db,
                "SELECT entity_id FROM desktop_entities WHERE entity_type='notebook'"
                    + " AND entity_id=? AND deleted_at IS NOT NULL",
                id)
            != null) throw ApiException.notFound("单词本不存在");
  }
}
