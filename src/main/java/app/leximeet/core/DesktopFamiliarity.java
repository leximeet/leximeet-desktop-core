package app.leximeet.core;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Connection;
import java.time.Instant;

// 事件是真源；分数/阶段缓存只用于整库分页，跨时间边界时按事件重建。
final class DesktopFamiliarity {
  static ObjectNode assess(Connection db, String wordId) throws Exception {
    return assess(db, wordId, Instant.now());
  }

  static ObjectNode assess(Connection db, String wordId, Instant now) throws Exception {
    var effective = Json.MAPPER.createArrayNode();
    Instant previous = Instant.MIN;
    for (var raw : orderedFacts(db, wordId)) {
      if ((!raw.path("undone_at").isNull()
              && !Instant.parse(raw.path("undone_at").asText()).isAfter(now))
          || Instant.parse(raw.path("created_at").asText()).isAfter(now)) continue;
      var fact = Json.object(raw).deepCopy();
      Instant at = Instant.parse(fact.path("created_at").asText());
      if (at.isBefore(previous)) at = previous;
      previous = at;
      fact.put("created_at", at.toString());
      effective.add(fact);
    }
    return LearningPolicy.project(effective, now);
  }

  static com.fasterxml.jackson.databind.node.ArrayNode orderedFacts(Connection db, String wordId)
      throws Exception {
    return Sql.rows(
        db,
        "SELECT * FROM desktop_practice_facts WHERE word_id=? ORDER BY"
            + " length(logical_clock),logical_clock,device_id,length(device_seq),device_seq,id",
        wordId);
  }

  static void saveProjection(Connection db, String wordId, ObjectNode f) throws Exception {
    Sql.execute(
        db,
        """
        INSERT INTO desktop_familiarity(word_id,score,status,graduated_at,graduated_day,eligible_at,first_recall_due,next_decay_at,computed_at)
        VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT(word_id) DO UPDATE SET score=excluded.score,status=excluded.status,
        graduated_at=excluded.graduated_at,graduated_day=excluded.graduated_day,eligible_at=excluded.eligible_at,
        first_recall_due=excluded.first_recall_due,next_decay_at=excluded.next_decay_at,computed_at=excluded.computed_at
        """,
        wordId,
        f.path("score").asInt(),
        f.path("status").asText(),
        text(f, "graduatedAt"),
        text(f, "graduatedDay"),
        text(f, "reviewEligibleAt"),
        text(f, "firstRecallDueAt"),
        text(f, "nextDecayAt"),
        text(f, "asOf"));
  }

  private static String text(ObjectNode f, String key) {
    return f.path(key).isNull() ? null : f.path(key).asText(null);
  }

  // 查询只更新可丢弃的索引，不创建学习事件或修改 FSRS。
  static void refresh(Connection db, Instant now) throws Exception {
    for (var row :
        Sql.rows(
            db,
            "SELECT word_id FROM desktop_familiarity WHERE next_decay_at<=? OR computed_at>? OR"
                + " EXISTS(SELECT 1 FROM desktop_practice_facts f WHERE"
                + " f.word_id=desktop_familiarity.word_id AND f.created_at>computed_at AND"
                + " f.created_at<=?)",
            now.toString(),
            now.toString(),
            now.toString())) {
      String id = row.path("word_id").asText();
      saveProjection(db, id, assess(db, id, now));
    }
  }

  static void rebuild(Connection db) throws Exception {
    Sql.execute(db, "DELETE FROM desktop_familiarity");
    for (var word :
        Sql.rows(
            db,
            "SELECT DISTINCT f.word_id FROM desktop_practice_facts f JOIN words w ON"
                + " w.id=f.word_id")) {
      String id = word.path("word_id").asText();
      saveProjection(db, id, assess(db, id));
    }
  }

  static String learningStatus(ObjectNode familiarity) {
    return familiarity.path("status").asText("new");
  }
}
