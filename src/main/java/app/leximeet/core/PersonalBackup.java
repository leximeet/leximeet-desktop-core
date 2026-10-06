package app.leximeet.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Connection;

// 基础读取投影中的个人语境与词本关系；完整备份由 PortableBackup 负责。
final class PersonalBackup {
  private static final String DETAILS =
      "SELECT e.id AS encounterId,COALESCE(d.original_sentence,e.context) AS"
          + " originalSentence,COALESCE(d.saved_excerpt,e.context) AS"
          + " savedExcerpt,COALESCE(d.annotation,'{}') AS"
          + " annotation,COALESCE(d.source_type,'desktop') AS sourceType,d.source_client_id AS"
          + " sourceClientId,COALESCE(d.occurred_at,e.created_at) AS"
          + " occurredAt,COALESCE(d.received_at,e.created_at) AS"
          + " receivedAt,COALESCE(d.time_zone,'') AS"
          + " timeZone,COALESCE(d.occurrence_ranges,'[]') AS"
          + " occurrenceRanges,COALESCE(d.excerpt_ranges,'[]') AS excerptRanges,d.undone_at"
          + " AS undoneAt FROM encounters e LEFT JOIN encounter_details d ON"
          + " d.encounter_id=e.id";

  static void snapshotExtras(Connection db, ObjectNode output) throws Exception {
    output.set(
        "wordBooks",
        Sql.rows(
            db,
            "SELECT word_id AS wordId,book_id AS bookId FROM word_books ORDER BY"
                + " word_id,book_id"));
    output.set(
        "wordDetails",
        Sql.rows(
            db,
            "SELECT w.id AS wordId,COALESCE(d.definition_status,CASE WHEN w.meaning=''"
                + " THEN 'pending' ELSE 'available' END) AS definitionStatus FROM words"
                + " w LEFT JOIN word_details d ON d.word_id=w.id ORDER BY w.id"));
    output.set("encounterDetails", details(db, false));
  }

  private static JsonNode details(Connection db, boolean all) throws Exception {
    var rows = Sql.rows(db, DETAILS + (all ? "" : " WHERE d.undone_at IS NULL") + " ORDER BY e.id");
    for (JsonNode value : rows) {
      ObjectNode item = (ObjectNode) value;
      for (String key : new String[] {"annotation", "occurrenceRanges", "excerptRanges"})
        item.set(key, Json.MAPPER.readTree(item.path(key).asText()));
    }
    return rows;
  }
}
