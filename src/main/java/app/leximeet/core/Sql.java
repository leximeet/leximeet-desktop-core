package app.leximeet.core;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Connection;
import java.sql.PreparedStatement;

// 参数只进入占位符；SQL 和表名由领域用例持有，不接受客户端 SQL。
final class Sql {
  static void execute(Connection db, String sql, Object... args) throws Exception {
    try (var statement = db.prepareStatement(sql)) {
      bind(statement, args);
      statement.executeUpdate();
    }
  }

  static int executeCount(Connection db, String sql, Object... args) throws Exception {
    try (var statement = db.prepareStatement(sql)) {
      bind(statement, args);
      return statement.executeUpdate();
    }
  }

  static ArrayNode rows(Connection db, String sql, Object... args) throws Exception {
    ArrayNode rows = Json.MAPPER.createArrayNode();
    try (var statement = db.prepareStatement(sql)) {
      bind(statement, args);
      try (var result = statement.executeQuery()) {
        var metadata = result.getMetaData();
        while (result.next()) {
          ObjectNode row = rows.addObject();
          for (int i = 1; i <= metadata.getColumnCount(); i++)
            row.set(metadata.getColumnLabel(i), Json.MAPPER.valueToTree(result.getObject(i)));
        }
      }
    }
    return rows;
  }

  static ObjectNode first(Connection db, String sql, Object... args) throws Exception {
    ArrayNode rows = rows(db, sql, args);
    return rows.isEmpty() ? null : (ObjectNode) rows.get(0);
  }

  static String meta(Connection db, String key) throws Exception {
    return first(db, "SELECT value FROM personal_meta WHERE key=?", key).path("value").asText();
  }

  private static void bind(PreparedStatement statement, Object... args) throws Exception {
    for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
  }
}
