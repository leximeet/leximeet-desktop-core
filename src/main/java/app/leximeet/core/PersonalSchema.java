package app.leximeet.core;

import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;

// 当前本机连接结构，随空库一次性创建；V1 版本之前开发期不维护旧结构迁移。
final class PersonalSchema {
  static void create(Connection db) throws Exception {
    try (Statement sql = db.createStatement()) {
      sql.execute("CREATE TABLE personal_meta (key TEXT PRIMARY KEY,value TEXT NOT NULL)");
      for (String key : new String[] {"libraryId", "profileId", "databaseGeneration"})
        Sql.execute(
            db,
            "INSERT INTO personal_meta(key,value) VALUES(?,?)",
            key,
            UUID.randomUUID().toString());
      sql.execute("INSERT INTO personal_meta(key,value) VALUES('revision','1')");
      sql.execute(
          "CREATE TABLE word_books(word_id TEXT NOT NULL REFERENCES words(id),book_id"
              + " TEXT NOT NULL REFERENCES books(id),PRIMARY KEY(word_id,book_id))");
      sql.execute(
          "CREATE TABLE book_roles(role TEXT PRIMARY KEY,book_id TEXT NOT NULL REFERENCES"
              + " books(id))");
      sql.execute(
          "CREATE TABLE word_details(word_id TEXT PRIMARY KEY REFERENCES"
              + " words(id),definition_status TEXT NOT NULL)");
      sql.execute(
          "CREATE TABLE encounter_details(encounter_id TEXT PRIMARY KEY REFERENCES"
              + " encounters(id),original_sentence TEXT NOT NULL,saved_excerpt TEXT NOT"
              + " NULL,annotation TEXT NOT NULL,source_type TEXT NOT"
              + " NULL,source_client_id TEXT,occurred_at TEXT NOT NULL,received_at TEXT"
              + " NOT NULL,time_zone TEXT NOT NULL,occurrence_ranges TEXT NOT"
              + " NULL,excerpt_ranges TEXT NOT NULL,undone_at TEXT)");
      // 用数据库触发器覆盖桌面与浏览器写入，避免某条路径忘记使插件索引失效。
      for (String table :
          new String[] {
            "words",
            "books",
            "book_roles",
            "encounters",
            "word_books",
            "word_details",
            "encounter_details"
          }) {
        for (String operation : new String[] {"INSERT", "UPDATE", "DELETE"})
          sql.execute(
              "CREATE TRIGGER personal_revision_"
                  + table
                  + "_"
                  + operation
                  + " AFTER "
                  + operation
                  + " ON "
                  + table
                  + " BEGIN UPDATE personal_meta SET value=CAST(value AS"
                  + " INTEGER)+1 WHERE key='revision'; END");
      }
      // bookId 是桌面编辑的主归属；更改它时保留浏览器添加的其他归属。
      sql.execute(
          "CREATE TRIGGER primary_book_insert AFTER INSERT ON words WHEN NEW.book_id IS"
              + " NOT NULL BEGIN INSERT OR IGNORE INTO word_books(word_id,book_id)"
              + " VALUES(NEW.id,NEW.book_id); END");
      sql.execute(
          "CREATE TRIGGER primary_book_update AFTER UPDATE OF book_id ON words WHEN"
              + " OLD.book_id IS NOT NEW.book_id BEGIN DELETE FROM word_books WHERE"
              + " word_id=NEW.id AND book_id=OLD.book_id; INSERT OR IGNORE INTO"
              + " word_books(word_id,book_id) SELECT NEW.id,NEW.book_id WHERE NEW.book_id"
              + " IS NOT NULL; END");
    }
  }
}
