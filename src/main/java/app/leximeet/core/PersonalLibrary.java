package app.leximeet.core;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Connection;

// 「我的词库」是唯一不可删改的个人词条视图。其他单词本只保存关系。 活动词条上限按未回收的个人词条计数；回收站不占名额。容量夹具绕过此上限。
final class PersonalLibrary {
  static final int ACTIVE_LIMIT = 10_000;
  static final String LIBRARY = "personal_library";
  static final String PLUGIN = "browser_capture";

  // 系统词本使用固定身份，备份恢复才不会在新资料空间再复制一份。
  static final String LIBRARY_ID = "00000000-0000-4000-8000-000000000001";

  static final String READING_ID = "00000000-0000-4000-8000-000000000002";
  static final String DAILY_ID = "00000000-0000-4000-8000-000000000003";

  private PersonalLibrary() {}

  // 空库初始化时创建系统词库和两本可删除的普通词本。重复调用保持幂等。
  static void seed(Connection db, String timestamp) throws Exception {
    // 系统词库的角色一经建立，就不再补种可删除的普通词本；否则用户删除后重启又会出现。
    boolean firstStart =
        Sql.first(db, "SELECT book_id FROM book_roles WHERE role=?", LIBRARY) == null;
    ensureFixed(db, LIBRARY_ID, "我的词库", "#2F6F4E", timestamp, LIBRARY);
    if (firstStart) {
      ensureFixed(db, READING_ID, "技术阅读", "#3D6B8C", timestamp, null);
      ensureFixed(db, DAILY_ID, "日常遇见", "#C47B4A", timestamp, null);
    }
  }

  private static void ensureFixed(
      Connection db, String id, String name, String color, String timestamp, String role)
      throws Exception {
    if (Sql.first(db, "SELECT id FROM books WHERE id=?", id) == null)
      Sql.execute(
          db,
          "INSERT INTO books(id,name,color,created_at) VALUES(?,?,?,?)",
          id,
          name,
          color,
          timestamp);
    if (role != null && Sql.first(db, "SELECT role FROM book_roles WHERE role=?", role) == null)
      Sql.execute(db, "INSERT INTO book_roles(role,book_id) VALUES(?,?)", role, id);
  }

  static boolean protectedBook(Connection db, String bookId) throws Exception {
    ObjectNode role = Sql.first(db, "SELECT role FROM book_roles WHERE book_id=?", bookId);
    if (role == null) return false;
    String value = role.path("role").asText();
    return LIBRARY.equals(value) || PLUGIN.equals(value);
  }

  static void rejectReservedName(String name) {
    if ("我的词库".equals(name) || "浏览器摘录".equals(name))
      throw ApiException.conflict("「" + name + "」是系统词本，不能另建同名单词本");
  }

  static int activeCount(Connection db) throws Exception {
    return Sql.first(
            db,
            "SELECT COUNT(*) AS n FROM words w LEFT JOIN desktop_word_links l ON l.word_id=w.id"
                + " WHERE w.deleted_at IS NULL AND COALESCE(l.manual_active,1)=1")
        .path("n")
        .asInt();
  }

  // 新建或从回收站恢复时调用。已有活动词条的重复遇见不占新名额。
  static void assertRoomForActivation(Connection db) throws Exception {
    if (activeCount(db) >= ACTIVE_LIMIT)
      throw ApiException.conflict("我的词库已有 " + ACTIVE_LIMIT + " 个活动词条。回收站不占名额；请整理后再收集新词");
  }
}
