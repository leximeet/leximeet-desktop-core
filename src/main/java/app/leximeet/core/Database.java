package app.leximeet.core;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;

// 单数据空间、单进程、单写入者。所有业务写入显式提交或回滚；服务串行访问连接。 目录必须由调用方提供，开发 / demo / e2e 不会意外落入正式 userData。
final class Database implements AutoCloseable {
  private static final int SCHEMA_VERSION = DesktopSchema.VERSION;
  private final Path dataDir;
  private final FileChannel lockChannel;
  private final FileLock lock;
  private final Connection connection;

  Database(Path directory) throws Exception {
    dataDir = directory.toAbsolutePath().normalize();
    Files.createDirectories(dataDir);
    try {
      Files.setPosixFilePermissions(dataDir, PosixFilePermissions.fromString("rwx------"));
    } catch (UnsupportedOperationException ignored) {
      // Windows 继承用户目录 ACL。
    }
    lockChannel =
        FileChannel.open(
            dataDir.resolve("core.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
    FileLock acquired;
    try {
      acquired = lockChannel.tryLock();
    } catch (OverlappingFileLockException error) {
      lockChannel.close();
      throw new StartupProblem("DATA_IN_USE", "此数据目录已被另一个 Core 使用");
    }
    if (acquired == null) {
      lockChannel.close();
      throw new StartupProblem("DATA_IN_USE", "此数据目录已被另一个 Core 使用");
    }
    lock = acquired;
    Connection opened = null;
    try {
      opened = DriverManager.getConnection("jdbc:sqlite:" + dataDir.resolve("leximeet.sqlite"));
      connection = opened;
      int version = schemaVersion();
      if (version != 0 && version != SCHEMA_VERSION)
        throw new StartupProblem(
            "UNSUPPORTED_DATA_SCHEMA",
            "当前 1.0.0 仅支持 schema "
                + SCHEMA_VERSION
                + "，拒绝预发布测试库 schema "
                + version
                + "；请使用新的隔离数据目录");
      if (version == SCHEMA_VERSION) DesktopSchema.assertCurrent(connection);
      if (version == 0) {
        if (Sql.first(
                connection,
                "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'"
                    + " LIMIT 1")
            != null)
          throw new StartupProblem("DATA_STRUCTURE_MISMATCH", "不识别没有版本标记的既有资料库；原文件已保留，请使用独立资料目录");
      }
      try (Statement statement = connection.createStatement()) {
        statement.execute("PRAGMA foreign_keys=ON");
        statement.execute("PRAGMA busy_timeout=5000");
        statement.execute("PRAGMA journal_mode=WAL");
        statement.execute("PRAGMA synchronous=FULL");
      }
      if (version == 0) initialize();

    } catch (Exception error) {
      if (opened != null) opened.close();
      lock.release();
      lockChannel.close();
      throw error;
    }
  }

  Path dataDir() {
    return dataDir;
  }

  private int schemaVersion() throws Exception {
    try (Statement statement = connection.createStatement();
        var result = statement.executeQuery("PRAGMA user_version")) {
      result.next();
      return result.getInt(1);
    }
  }

  // 完整 schema 在同一事务里创建；失败只回滚本次初始化，不留下半成品版本。
  private void initialize() throws Exception {
    transaction(
        db -> {
          createSchema(db);
          return null;
        });
  }

  // 初始化和既有结构核验使用同一份定义，避免版本号相同但字段结构不同被误接纳。
  static void createSchema(Connection db) throws Exception {
    try (Statement sql = db.createStatement()) {
      sql.execute(
          "CREATE TABLE books (id TEXT PRIMARY KEY, name TEXT NOT NULL, color TEXT NOT NULL,"
              + " created_at TEXT NOT NULL)");
      sql.execute(
          "CREATE TABLE words (id TEXT PRIMARY KEY, word TEXT NOT NULL, normalized TEXT NOT"
              + " NULL UNIQUE, meaning TEXT NOT NULL DEFAULT '', phonetic TEXT NOT NULL DEFAULT '', note"
              + " TEXT NOT NULL DEFAULT '', book_id TEXT REFERENCES books(id), status TEXT"
              + " NOT NULL DEFAULT 'new', revision INTEGER NOT NULL DEFAULT 1"
              + " CHECK(revision>=1), created_at TEXT NOT NULL, updated_at TEXT NOT NULL,"
              + " deleted_at TEXT)");
      sql.execute(
          "CREATE TABLE encounters (id TEXT PRIMARY KEY, word_id TEXT NOT NULL REFERENCES"
              + " words(id), context TEXT NOT NULL CHECK(length(context)>0), source_title"
              + " TEXT NOT NULL, source_url TEXT NOT NULL, created_at TEXT NOT NULL, is_demo"
              + " INTEGER NOT NULL DEFAULT 0)");
      sql.execute(
          "CREATE TABLE settings (id INTEGER PRIMARY KEY CHECK(id=1), payload TEXT NOT" + " NULL)");
      sql.execute(
          "INSERT INTO settings VALUES(1, '"
              + SettingsSupport.DEFAULT_JSON.replace("'", "''")
              + "')");
      sql.execute("CREATE INDEX encounters_by_word ON encounters(word_id, created_at)");
      PersonalSchema.create(db);
      DesktopSchema.create(db);
      sql.execute("PRAGMA user_version=" + SCHEMA_VERSION);
    }
  }

  @FunctionalInterface
  interface Work<T> {
    T execute(Connection connection) throws Exception;
  }

  synchronized <T> T read(Work<T> work) throws Exception {
    return work.execute(connection);
  }

  // SQLite 自己在一致的读视图中生成单文件副本，避免复制 WAL 时遗漏已提交事实。
  synchronized void vacuumInto(Path target) throws Exception {
    Sql.execute(connection, "VACUUM INTO ?", target.toAbsolutePath().toString());
  }

  // 文件恢复只接受受控临时副本。校验和去授权在任何原库写入之前完成； 业务表一次提交，异常由 transaction 回滚，旧资料不会出现半恢复状态。
  synchronized ObjectNode restoreFrom(Path file) throws Exception {
    if (Files.exists(file) && Files.isSameFile(dataDir.resolve("leximeet.sqlite"), file))
      throw ApiException.badRequest("不能将当前数据库当作导入文件");
    PortableBackup.validate(connection, file);
    PortableBackup.sanitize(file);
    PortableBackup.validate(connection, file);
    try (var attach = connection.prepareStatement("ATTACH DATABASE ? AS imported")) {
      attach.setString(1, file.toAbsolutePath().toString());
      attach.execute();
    }
    try {
      return transaction(
          db -> {
            try (Statement sql = db.createStatement()) {
              sql.execute("PRAGMA defer_foreign_keys=ON");
            }
            // 常量表名由 Core 持有，不从文件或 HTTP 请求生成 SQL。
            LmcpSchema.clearAuthorization(db);
            for (String table :
                new String[] {
                  "desktop_attempt_questions",
                  "desktop_practice_drafts",
                  "desktop_practice_scopes",
                  "desktop_question_drafts",
                  "desktop_questions",
                  "desktop_practice_items",
                  "desktop_practice_sessions",
                  "desktop_entities",
                  "desktop_word_identity",
                  "lmcp_capture_events"
                }) Sql.execute(db, "DELETE FROM " + table);
            for (String table :
                new String[] {
                  "desktop_reminder_questions",
                  "desktop_study_session",
                  "desktop_reviews",
                  "desktop_cards",
                  "desktop_word_links",
                  "desktop_practice_facts",
                  "desktop_familiarity",
                  "desktop_guide",
                  "desktop_profile",
                  "desktop_dictionary",
                  "encounter_details",
                  "word_details",
                  "word_books",
                  "book_roles",
                  "encounters",
                  "words",
                  "books",
                  "settings"
                }) Sql.execute(db, "DELETE FROM main." + table);
            for (String table : new String[] {"books", "words"})
              Sql.execute(db, "INSERT INTO main." + table + " SELECT * FROM imported." + table);
            // words 插入触发器会补主词本关系；先清空，再逐行复制原有完整关系。
            Sql.execute(db, "DELETE FROM main.word_books");
            for (String table :
                new String[] {
                  "word_books",
                  "encounters",
                  "encounter_details",
                  "word_details",
                  "settings",
                  "book_roles"
                })
              Sql.execute(db, "INSERT INTO main." + table + " SELECT * FROM imported." + table);
            // 本机事件身份与序号不从备份复制，避免两个设备写出同一来源的事件。
            for (String table : DesktopSchema.TABLES)
              if (!table.equals("desktop_learning_origin"))
                Sql.execute(db, "INSERT INTO main." + table + " SELECT * FROM imported." + table);
            for (String table : LmcpSchema.BUSINESS_TABLES)
              Sql.execute(db, "INSERT INTO main." + table + " SELECT * FROM imported." + table);
            String sourceWorkspace =
                Sql.first(db, "SELECT value FROM imported.lmcp_meta WHERE key='workspaceId'")
                    .path("value")
                    .asText();
            Sql.execute(
                db,
                "UPDATE desktop_entities SET entity_id=? WHERE entity_type='studyPlan' AND"
                    + " entity_id=?",
                LmcpService.meta(db, "workspaceId"),
                sourceWorkspace);
            String sourceDesktop =
                Sql.first(db, "SELECT value FROM imported.lmcp_meta WHERE key='desktopInstanceId'")
                    .path("value")
                    .asText();
            Sql.execute(
                db,
                "UPDATE desktop_practice_sessions SET owner_id=? WHERE owner_id=?",
                LmcpService.meta(db, "desktopInstanceId"),
                sourceDesktop);
            Sql.execute(
                db,
                "UPDATE lmcp_meta SET value=? WHERE key='generation'",
                UUID.randomUUID().toString());
            for (String key :
                new String[] {
                  "preferences",
                  "revision",
                  "preferencesRevision",
                  "learningRevision",
                  "draftRevision"
                })
              Sql.execute(
                  db,
                  "UPDATE lmcp_meta SET value=(SELECT value FROM imported.lmcp_meta WHERE key=?)"
                      + " WHERE key=?",
                  key,
                  key);
            // 文件恢复不会恢复配对凭据；工作区换世代使任何旧会话和游标都无法继续使用。
            for (String key : new String[] {"libraryId", "profileId", "databaseGeneration"})
              Sql.execute(
                  db,
                  "UPDATE main.personal_meta SET value=? WHERE key=?",
                  UUID.randomUUID().toString(),
                  key);
            Sql.execute(db, "UPDATE main.personal_meta SET value='1' WHERE key='revision'");
            try (Statement sql = db.createStatement();
                var invalid = sql.executeQuery("PRAGMA foreign_key_check")) {
              if (invalid.next()) throw ApiException.badRequest("备份恢复后存在无效引用");
            }
            ObjectNode result = Json.MAPPER.createObjectNode().put("restored", true);
            for (String table :
                new String[] {"words", "encounters", "desktop_reviews", "desktop_practice_facts"})
              result.put(
                  table.equals("desktop_reviews")
                      ? "reviews"
                      : table.equals("desktop_practice_facts") ? "practiceFacts" : table,
                  Sql.first(db, "SELECT COUNT(*) AS count FROM main." + table)
                      .path("count")
                      .asLong());
            return result;
          });
    } finally {
      try (Statement sql = connection.createStatement()) {
        sql.execute("DETACH DATABASE imported");
      }
    }
  }

  synchronized <T> T transaction(Work<T> work) throws Exception {
    connection.setAutoCommit(false);
    try {
      T value = work.execute(connection);
      connection.commit();
      return value;
    } catch (Exception error) {
      connection.rollback();
      throw error;
    } finally {
      connection.setAutoCommit(true);
    }
  }

  @Override
  public synchronized void close() throws Exception {
    try {
      connection.close();
    } finally {
      try {
        lock.release();
      } finally {
        lockChannel.close();
      }
    }
  }
}
