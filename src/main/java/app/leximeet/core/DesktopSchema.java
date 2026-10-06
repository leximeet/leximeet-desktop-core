package app.leximeet.core;

import java.sql.Connection;

// 首次正式发布的完整结构。空库一次创建；拒绝预发布资料，不执行逐版 ALTER 迁移。
final class DesktopSchema {
  static final int VERSION = 100;
  static final String[] TABLES = {
    "desktop_profile",
    "desktop_learning_origin",
    "desktop_dictionary",
    "desktop_word_links",
    "desktop_cards",
    "desktop_reviews",
    "desktop_practice_facts",
    "desktop_familiarity",
    "desktop_reminder_questions",
    "desktop_guide",
    "desktop_study_session"
  };

  // 开发期不同试验曾使用同一版本号；结构不符时拒绝，绝不尝试修补或清空旧文件。
  static void assertCurrent(Connection db) throws Exception {
    String sql =
        "SELECT type,name,tbl_name,sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' ORDER BY type,name";
    try (Connection expected = java.sql.DriverManager.getConnection("jdbc:sqlite::memory:")) {
      Database.createSchema(expected);
      if (!Sql.rows(db, sql).equals(Sql.rows(expected, sql)))
        throw new StartupProblem("DATA_STRUCTURE_MISMATCH", "资料结构不符合当前 1.0.0 版本；原文件已保留，请使用独立资料目录");
    }
  }

  static void create(Connection db) throws Exception {
    for (String sql :
        new String[] {
          """
          CREATE TABLE desktop_profile(id INTEGER PRIMARY KEY CHECK(id=1), goal TEXT,
            daily_new INTEGER NOT NULL DEFAULT 10 CHECK(daily_new BETWEEN 1 AND 50),
            daily_review INTEGER NOT NULL DEFAULT 20 CHECK(daily_review BETWEEN 0 AND 500),
            study_time TEXT NOT NULL DEFAULT '08:00', revision INTEGER NOT NULL DEFAULT 1,
            plan_enabled INTEGER NOT NULL DEFAULT 0 CHECK(plan_enabled IN(0,1)),
            reminder_enabled INTEGER NOT NULL DEFAULT 1 CHECK(reminder_enabled IN(0,1)),
            study_start TEXT NOT NULL DEFAULT '08:00', study_end TEXT NOT NULL DEFAULT '20:00',
            study_zone TEXT NOT NULL DEFAULT '', reminder_interval INTEGER NOT NULL DEFAULT 30 CHECK(reminder_interval=30),
            plan_id TEXT, started_on TEXT, saved_at TEXT, reminder_modes TEXT NOT NULL DEFAULT '["meaning-choice"]')
          """,
          "INSERT INTO desktop_profile(id) VALUES(1)",
          "CREATE TABLE desktop_dictionary(id INTEGER PRIMARY KEY CHECK(id=1), payload TEXT NOT"
              + " NULL)",
          "CREATE TABLE desktop_word_links(word_id TEXT PRIMARY KEY REFERENCES words(id), entry_id"
              + " TEXT, manual_active INTEGER NOT NULL DEFAULT 1 CHECK(manual_active IN(0,1)))",
          "CREATE INDEX desktop_entry_links ON desktop_word_links(entry_id)",
          "CREATE TABLE desktop_cards(word_id TEXT PRIMARY KEY REFERENCES words(id), initial_due"
              + " TEXT NOT NULL, memory TEXT NOT NULL, due_at TEXT NOT NULL, learned INTEGER NOT"
              + " NULL DEFAULT 0 CHECK(learned IN(0,1)))",
          "CREATE INDEX desktop_cards_due ON desktop_cards(due_at)",
          """
          CREATE TABLE desktop_reviews(id TEXT PRIMARY KEY, submission_id TEXT NOT NULL UNIQUE,
            word_id TEXT NOT NULL REFERENCES words(id), rating TEXT NOT NULL CHECK(rating IN('again','hard','good','easy')),
            kind TEXT NOT NULL CHECK(kind IN('new','review')), round INTEGER NOT NULL, algorithm TEXT NOT NULL,
            before_state TEXT NOT NULL, after_state TEXT NOT NULL, completed INTEGER NOT NULL CHECK(completed IN(0,1)),
            day TEXT NOT NULL, zone TEXT NOT NULL, goal TEXT, created_at TEXT NOT NULL, undone_at TEXT)
          """,
          "CREATE INDEX desktop_reviews_day ON desktop_reviews(day,kind,word_id) WHERE undone_at IS"
              + " NULL AND completed=1",
          """
          CREATE TABLE desktop_practice_facts(id TEXT PRIMARY KEY,submission_id TEXT NOT NULL UNIQUE,
            word_id TEXT NOT NULL,mode TEXT NOT NULL,correct INTEGER NOT NULL CHECK(correct IN(0,1)),created_at TEXT NOT NULL,
            signal TEXT NOT NULL,assisted INTEGER NOT NULL CHECK(assisted IN(0,1)),study_day TEXT NOT NULL,
            attempt_id TEXT NOT NULL,rule_version TEXT NOT NULL,device_id TEXT NOT NULL,device_seq TEXT NOT NULL,
            logical_clock TEXT NOT NULL,zone TEXT NOT NULL,evidence TEXT NOT NULL,undone_at TEXT,
            review_completed INTEGER NOT NULL DEFAULT 0,origin TEXT NOT NULL DEFAULT 'practice')
          """,
          "CREATE INDEX desktop_practice_word ON desktop_practice_facts(word_id,created_at)",
          "CREATE INDEX desktop_practice_attempt ON desktop_practice_facts(attempt_id)",
          "CREATE UNIQUE INDEX desktop_practice_sequence ON"
              + " desktop_practice_facts(device_id,device_seq)",
          "CREATE TABLE desktop_learning_origin(id INTEGER PRIMARY KEY CHECK(id=1),device_id TEXT"
              + " NOT NULL,sequence TEXT NOT NULL DEFAULT '0')",
          """
          CREATE TABLE desktop_familiarity(word_id TEXT PRIMARY KEY REFERENCES words(id) ON DELETE CASCADE,
            score INTEGER NOT NULL CHECK(score BETWEEN 0 AND 30),status TEXT NOT NULL,graduated_at TEXT,
            graduated_day TEXT,eligible_at TEXT,first_recall_due TEXT,next_decay_at TEXT,computed_at TEXT NOT NULL)
          """,
          "CREATE INDEX desktop_familiarity_stage ON desktop_familiarity(status,eligible_at)",
          "CREATE INDEX desktop_familiarity_decay ON desktop_familiarity(next_decay_at)",
          "CREATE TABLE desktop_guide(id INTEGER PRIMARY KEY CHECK(id=1),payload TEXT NOT NULL)",
          "INSERT INTO desktop_guide"
              + " VALUES(1,'{\"flowVersion\":3,\"active\":true,\"completed\":[],\"started\":false}')",
          "CREATE TABLE desktop_study_session(id INTEGER PRIMARY KEY CHECK(id=1),payload TEXT NOT"
              + " NULL)",
          "INSERT INTO desktop_study_session VALUES(1,'{\"phase\":\"idle\"}')",
          "CREATE TABLE desktop_reminder_questions(id TEXT PRIMARY KEY,word_id TEXT NOT NULL"
              + " REFERENCES words(id),mode TEXT NOT NULL,payload TEXT NOT NULL,created_at TEXT NOT"
              + " NULL,expires_at TEXT NOT NULL,answered_at TEXT,answer TEXT)"
        }) Sql.execute(db, sql);
    LmcpSchema.create(db);
    Sql.execute(
        db,
        "INSERT INTO desktop_learning_origin VALUES(1,?,'0')",
        LmcpService.meta(db, "desktopInstanceId"));
  }
}
