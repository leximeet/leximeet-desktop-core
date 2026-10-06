package app.leximeet.core;

import java.sql.Connection;
import java.util.UUID;

// 新资料一次初始化。配对与会话属于授权；学习、词卡与捕获事实属于桌面业务。
final class LmcpSchema {
  static final String[] AUTH_TABLES = {
    "lmcp_host_grants", "lmcp_operations", "lmcp_sessions", "lmcp_pairings"
  };
  static final String[] BUSINESS_TABLES = {
    "desktop_entities",
    "desktop_word_identity",
    "lmcp_capture_events",
    "desktop_practice_sessions",
    "desktop_practice_items",
    "desktop_questions",
    "desktop_question_drafts",
    "desktop_attempt_questions",
    "desktop_practice_scopes",
    "desktop_practice_drafts"
  };

  static void clearAuthorization(Connection db) throws Exception {
    for (String table : AUTH_TABLES) Sql.execute(db, "DELETE FROM " + table);
    Sql.execute(
        db,
        "DELETE FROM lmcp_meta WHERE key='invitationSecret' OR key LIKE 'invitation:%' OR key LIKE"
            + " 'connection:%'");
  }

  static void create(Connection db) throws Exception {
    for (String sql :
        new String[] {
          "CREATE TABLE lmcp_meta(key TEXT PRIMARY KEY,value TEXT NOT NULL)",
          "CREATE TABLE lmcp_pairings(pairing_id TEXT PRIMARY KEY,client_id TEXT NOT"
              + " NULL,display_name TEXT NOT NULL,origin TEXT NOT NULL,token_hash TEXT NOT"
              + " NULL,epoch TEXT NOT NULL DEFAULT '1',revoked INTEGER NOT NULL DEFAULT"
              + " 0,created_at TEXT NOT NULL,UNIQUE(client_id,origin))",
          "CREATE TABLE lmcp_sessions(session_id TEXT PRIMARY KEY,pairing_id TEXT NOT NULL"
              + " REFERENCES lmcp_pairings(pairing_id),token_hash TEXT NOT NULL,connection_id TEXT"
              + " NOT NULL,expires_at TEXT NOT NULL,revoked INTEGER NOT NULL DEFAULT 0)",
          "CREATE TABLE lmcp_operations(pairing_id TEXT NOT NULL REFERENCES"
              + " lmcp_pairings(pairing_id),epoch TEXT NOT NULL,mutation_id TEXT NOT NULL,method"
              + " TEXT NOT NULL,input_hash TEXT NOT NULL,status TEXT NOT NULL,result TEXT NOT"
              + " NULL,created_at TEXT NOT NULL,connection_id TEXT NOT NULL,delivery_token"
              + " TEXT,owner TEXT NOT NULL,PRIMARY KEY(pairing_id,epoch,mutation_id))",
          "CREATE TABLE lmcp_capture_events(event_id TEXT PRIMARY KEY,input_hash TEXT NOT"
              + " NULL,result TEXT NOT NULL)",
          "CREATE TABLE desktop_entities(entity_type TEXT NOT NULL,entity_id TEXT NOT NULL,revision"
              + " TEXT NOT NULL,deleted_at TEXT,payload TEXT NOT NULL,PRIMARY"
              + " KEY(entity_type,entity_id))",
          "CREATE TABLE desktop_word_identity(word_id TEXT PRIMARY KEY,payload TEXT NOT NULL)",
          "CREATE TABLE desktop_practice_sessions(session_id TEXT PRIMARY KEY,owner_id TEXT NOT"
              + " NULL,payload TEXT NOT NULL,updated_at TEXT NOT NULL)",
          "CREATE TABLE desktop_practice_items(session_id TEXT NOT NULL REFERENCES"
              + " desktop_practice_sessions(session_id),position INTEGER NOT NULL,word_id TEXT NOT"
              + " NULL,word_ref TEXT NOT NULL,PRIMARY KEY(session_id,position))",
          "CREATE TABLE desktop_questions(question_id TEXT PRIMARY KEY,session_id TEXT NOT NULL"
              + " REFERENCES desktop_practice_sessions(session_id),position INTEGER NOT NULL,mode"
              + " TEXT NOT NULL,payload TEXT NOT NULL,answer TEXT NOT NULL,correct_choice"
              + " TEXT,assisted INTEGER NOT NULL DEFAULT 0,UNIQUE(session_id,position,mode))",
          "CREATE TABLE desktop_attempt_questions(attempt_id TEXT PRIMARY KEY,question_id TEXT NOT"
              + " NULL REFERENCES desktop_questions(question_id))",
          "CREATE TABLE desktop_question_drafts(session_id TEXT NOT NULL,position INTEGER NOT"
              + " NULL,mode TEXT NOT NULL,text TEXT NOT NULL,PRIMARY"
              + " KEY(session_id,position,mode))",
          "CREATE TABLE desktop_practice_scopes(scope TEXT PRIMARY KEY,session_id TEXT NOT NULL"
              + " REFERENCES desktop_practice_sessions(session_id))",
          "CREATE TABLE desktop_practice_drafts(session_id TEXT NOT NULL REFERENCES"
              + " desktop_practice_sessions(session_id),position INTEGER NOT NULL,mode TEXT NOT"
              + " NULL,payload TEXT NOT NULL,PRIMARY KEY(session_id,position,mode))",
          "CREATE INDEX desktop_event_sequence ON"
              + " desktop_entities(entity_type,json_extract(payload,'$.origin.deviceId'),json_extract(payload,'$.deviceSeq'))",
          "CREATE INDEX desktop_event_entry ON"
              + " desktop_entities(entity_type,json_extract(payload,'$.word.entryId'))",
          "CREATE INDEX desktop_event_custom ON"
              + " desktop_entities(entity_type,json_extract(payload,'$.word.customId'))",
          "CREATE INDEX desktop_retraction_target ON"
              + " desktop_entities(entity_type,json_extract(payload,'$.targetType'),json_extract(payload,'$.targetId'))",
          "CREATE TABLE lmcp_host_grants(grant_id TEXT PRIMARY KEY,pairing_id TEXT NOT"
              + " NULL,connection_id TEXT NOT NULL,token_hash TEXT NOT NULL,capabilities TEXT NOT"
              + " NULL,expires_at TEXT NOT NULL,revoked INTEGER NOT NULL DEFAULT 0)"
        }) Sql.execute(db, sql);
    for (String key : new String[] {"desktopInstanceId", "workspaceId", "generation"})
      Sql.execute(db, "INSERT INTO lmcp_meta VALUES(?,?)", key, UUID.randomUUID().toString());
    for (String key :
        new String[] {"revision", "learningRevision", "draftRevision", "preferencesRevision"})
      Sql.execute(db, "INSERT INTO lmcp_meta VALUES(?,'1')", key);
    Sql.execute(
        db,
        "INSERT INTO lmcp_meta VALUES('preferences',?)",
        "{\"defaultMode\":\"word-list\",\"repeatCount\":1,\"feedbackSound\":true,\"cardFields\":[\"phonetic\",\"definition\",\"translation\",\"example\",\"note\"]}");
  }

  private LmcpSchema() {}
}
