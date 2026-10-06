package app.leximeet.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;

// 合并保留原事件顺序和时间；所有积分、完成量、FSRS 都重新计算，绝不相加最终分数。
final class DesktopLearningReplay {
  private final DesktopDataModel workspace;

  DesktopLearningReplay(DesktopDataModel workspace) {
    this.workspace = workspace;
  }

  String ensureWord(Connection db, JsonNode ref) throws Exception {
    String id = DesktopDataModel.localWordId(db, ref);
    if (Sql.first(db, "SELECT id FROM words WHERE id=?", id) == null) {
      var data = Json.MAPPER.createObjectNode().put("note", "").put("collected", false);
      data.set("word", ref);
      data.putArray("notebookIds");
      workspace.mirrorWord(
          db, DesktopDataModel.record("word", DesktopDataModel.key(ref), 1, data, null));
    }
    return id;
  }

  void importRetraction(Connection db, JsonNode record) throws Exception {
    workspace.service.contract.entity(record);
    JsonNode data = record.path("data");
    workspace.service.checkEventTime(data.path("occurredAt").asText());
    var old = workspace.entity(db, "retraction", record.path("entityId").asText());
    if (old != null) {
      if (!old.path("data").equals(data))
        throw LmcpService.error("IDEMPOTENCY_CONFLICT", "同一撤销事件的内容不同");
      return;
    }
    var target =
        workspace.entity(db, data.path("targetType").asText(), data.path("targetId").asText());
    if (target == null) throw LmcpService.error("INVALID_REFERENCE", "撤销目标事件不存在");
    String at = Instant.parse(data.path("occurredAt").asText()).toString();
    if (data.path("targetType").asText().equals("practice")) {
      String word = DesktopDataModel.localWordId(db, target.path("data").path("word"));
      Sql.execute(
          db,
          "UPDATE desktop_practice_facts SET undone_at=COALESCE(undone_at,?) WHERE submission_id=?",
          at,
          data.path("targetId").asText());
      workspace.put(db, record);
      replay(db, word);
      LmcpService.bump(db, "learningRevision");
    } else {
      Sql.execute(
          db,
          "UPDATE encounter_details SET undone_at=COALESCE(undone_at,?) WHERE encounter_id=?",
          at,
          data.path("targetId").asText());
      workspace.put(db, record);
    }
  }

  void replay(Connection db, String word) throws Exception {
    var all = DesktopFamiliarity.orderedFacts(db, word);
    ArrayNode history = Json.MAPPER.createArrayNode();
    var seen = new HashSet<String>();
    Instant previous = Instant.MIN;
    var card = Sql.first(db, "SELECT initial_due FROM desktop_cards WHERE word_id=?", word);
    Instant initial =
        card == null
            ? workspace.service.businessClock.instant()
            : Instant.parse(card.path("initial_due").asText());
    var seed =
        Sql.first(
            db,
            "SELECT MIN(json_extract(payload,'$.initialDueAt')) AS at FROM desktop_entities WHERE"
                + " entity_type='practice' AND"
                + " (json_extract(payload,'$.word.entryId')=COALESCE((SELECT entry_id FROM"
                + " desktop_word_links WHERE word_id=?),?) OR"
                + " json_extract(payload,'$.word.customId')=?)",
            word,
            word,
            word);
    if (!seed.path("at").isNull() && Instant.parse(seed.path("at").asText()).isBefore(initial))
      initial = Instant.parse(seed.path("at").asText());
    var fsrs = new FsrsSchedulingService();
    var memory = fsrs.initial(initial);
    boolean scheduled = false;
    Sql.execute(db, "DELETE FROM desktop_reviews WHERE word_id=?", word);
    Sql.execute(db, "UPDATE desktop_practice_facts SET review_completed=0 WHERE word_id=?", word);
    for (JsonNode raw : all) {
      if (!raw.path("undone_at").isNull()
          || Instant.parse(raw.path("created_at").asText())
              .isAfter(workspace.service.businessClock.instant())) continue;
      ObjectNode fact = Json.object(raw).deepCopy();
      Instant at = Instant.parse(fact.path("created_at").asText());
      if (at.isBefore(previous)) at = previous;
      previous = at;
      fact.put("created_at", at.toString());
      var before = LearningPolicy.project(history, at);
      history.add(fact);
      if (!seen.add(fact.path("attempt_id").asText())) continue;
      var after = LearningPolicy.project(history, at);
      boolean
          independent =
              !fact.path("assisted").asBoolean()
                  && !fact.path("mode").asText().equals("copy")
                  && !fact.path("signal").asText().equals("reveal"),
          correct = fact.path("correct").asBoolean();
      String day = LocalDate.ofInstant(at, ZoneId.of(fact.path("zone").asText())).toString();
      // 完成量按复习资格和首次独立成功判断；FSRS 到期只约束自动队列与记忆排期。
      boolean reviewEligible =
          before.path("status").asText().equals("review")
              && !before.path("graduatedDay").asText().equals(day)
              && !at.isBefore(Instant.parse(before.path("reviewEligibleAt").asText()));
      boolean completed = independent && correct && reviewEligible;
      if (completed)
        Sql.execute(
            db,
            "UPDATE desktop_practice_facts SET review_completed=1 WHERE submission_id=?",
            fact.path("submission_id").asText());
      if (independent
          && (!correct || memory.lastReviewAt() == null || !at.isBefore(memory.dueAt()))) {
        String rating = correct ? "good" : "again",
            kind = before.path("graduatedAt").isNull() ? "new" : "review";
        var next = fsrs.review(word, memory, rating, at);
        Sql.execute(
            db,
            "INSERT INTO desktop_reviews VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,NULL)",
            fact.path("id").asText(),
            fact.path("submission_id").asText(),
            word,
            rating,
            kind,
            memory.round(),
            FsrsSchedulingService.ALGORITHM_VERSION,
            DesktopReviews.json(memory).toString(),
            DesktopReviews.json(next).toString(),
            completed ? 1 : 0,
            day,
            fact.path("zone").asText(),
            null,
            at.toString());
        memory = next;
        scheduled = true;
      }
    }
    var projection = LearningPolicy.project(history, workspace.service.businessClock.instant());
    DesktopFamiliarity.saveProjection(db, word, projection);
    Sql.execute(
        db, "UPDATE words SET status=? WHERE id=?", projection.path("status").asText(), word);
    if (card != null || scheduled)
      Sql.execute(
          db,
          "INSERT INTO desktop_cards VALUES(?,?,?,?,?) ON CONFLICT(word_id) DO UPDATE SET"
              + " initial_due=excluded.initial_due,memory=excluded.memory,due_at=excluded.due_at,learned=excluded.learned",
          word,
          initial.toString(),
          DesktopReviews.json(memory).toString(),
          memory.dueAt().toString(),
          projection.path("graduatedAt").isNull() ? 0 : 1);
  }
}
