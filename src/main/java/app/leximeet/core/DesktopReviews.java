package app.leximeet.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

// FSRS 与目标解耦：一词一张卡，反馈事实、卡片投影与当天额度在同一事务内提交。
final class DesktopReviews {
  private final Clock clock;
  private final DesktopWords words;
  private final FsrsSchedulingService fsrs = new FsrsSchedulingService();

  DesktopReviews(Clock clock, DesktopWords words) {
    this.clock = clock;
    this.words = words;
  }

  ObjectNode record(Connection db, JsonNode input) throws Exception {
    return record(db, input, true);
  }

  // 练习评级由 Core 推导，自由练习不受计划额度限制；今日队列仍按计划选词。
  ObjectNode recordPractice(Connection db, JsonNode input, Instant occurredAt) throws Exception {
    return record(db, input, false, occurredAt);
  }

  private ObjectNode record(Connection db, JsonNode input, boolean planned) throws Exception {
    return record(db, input, planned, clock.instant());
  }

  private ObjectNode record(Connection db, JsonNode input, boolean planned, Instant now)
      throws Exception {
    Json.fields(input, "action", "wordId", "rating", "submissionId", "round", "kind");
    String submission = Json.id(input, "submissionId", true),
        requested = Json.id(input, "wordId", true);
    String rating = Json.choice(input, "rating", "good", "again", "hard", "good", "easy");
    String kind = Json.choice(input, "kind", "new", "new", "review");
    String canonical = words.detail(db, requested).path("id").asText();
    ObjectNode duplicate =
        Sql.first(db, "SELECT * FROM desktop_reviews WHERE submission_id=?", submission);
    if (duplicate != null) {
      if (!duplicate.path("undone_at").isNull()
          || !duplicate.path("word_id").asText().equals(canonical)
          || !duplicate.path("rating").asText().equals(rating)
          || !duplicate.path("kind").asText().equals(kind)
          || duplicate.path("round").asInt() != input.path("round").asInt())
        throw ApiException.conflict("提交标识已用于其他反馈或已经撤销");
      return duplicate;
    }
    String id = words.materialize(db, requested, false);
    ObjectNode card = Sql.first(db, "SELECT * FROM desktop_cards WHERE word_id=?", id);
    FsrsSchedulingService.MemoryState before =
        card == null
            ? fsrs.initial(now)
            : memory(Json.MAPPER.readTree(card.path("memory").asText()));
    // 自由练习可以反复进行，但提前答对不提前拉长 FSRS 间隔。错误仍拉回短期复习。
    if (!planned
        && rating.equals("good")
        && card != null
        && before.lastReviewAt() != null
        && now.isBefore(before.dueAt()))
      return Json.MAPPER.createObjectNode().put("completed", false).put("scheduled", false);
    if (input.path("round").asInt() != before.round())
      throw ApiException.conflict("此词条已产生新反馈，请刷新后重试");
    if (planned && kind.equals("new") && card != null && card.path("learned").asBoolean())
      throw ApiException.conflict("已经学过的词不能再次占用新学额度");
    if (planned && kind.equals("review") && (card == null || !card.path("learned").asBoolean()))
      throw ApiException.conflict("尚未学过的词应计入新学");
    String day = LocalDate.ofInstant(now, clock.getZone()).toString();
    var profile = Sql.first(db, "SELECT * FROM desktop_profile WHERE id=1");
    int cap = profile.path(kind.equals("new") ? "daily_new" : "daily_review").asInt();
    boolean confirmedEncounter =
        kind.equals("new")
            && Sql.first(
                    db,
                    "SELECT 1 FROM encounters e JOIN words w ON w.id=e.word_id WHERE e.word_id=?"
                        + " AND w.deleted_at IS NULL AND e.id NOT IN(SELECT encounter_id FROM"
                        + " encounter_details WHERE undone_at IS NOT NULL) LIMIT 1",
                    id)
                != null;
    if (planned
        && legacyCompleted(db, day, kind) >= cap
        && !confirmedEncounter
        && Sql.first(
                db,
                "SELECT 1 AS n FROM desktop_reviews WHERE word_id=? AND day=? AND kind=? AND"
                    + " completed=1 AND undone_at IS NULL",
                id,
                day,
                kind)
            == null) throw ApiException.conflict("当天额度已完成，请调整计划后继续");
    String strict =
        Json.MAPPER
            .readTree(
                Sql.first(db, "SELECT payload FROM settings WHERE id=1").path("payload").asText())
            .path("reviewStrictness")
            .asText("normal");
    boolean complete =
        (!planned && kind.equals("review") && rating.equals("good"))
            || planned && rating.equals("easy")
            || (planned && !strict.equals("strict") && rating.equals("good"))
            || (planned && strict.equals("lenient") && rating.equals("hard"));
    FsrsSchedulingService.MemoryState after = fsrs.review(id, before, rating, now);
    String reviewId = UUID.randomUUID().toString();
    Sql.execute(
        db,
        "INSERT INTO desktop_reviews VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,NULL)",
        reviewId,
        submission,
        id,
        rating,
        kind,
        before.round(),
        FsrsSchedulingService.ALGORITHM_VERSION,
        json(before).toString(),
        json(after).toString(),
        complete ? 1 : 0,
        day,
        clock.getZone().getId(),
        profile.path("goal").asText(null),
        now.toString());
    Sql.execute(
        db,
        "INSERT INTO desktop_cards VALUES(?,?,?,?,?) ON CONFLICT(word_id) DO UPDATE SET"
            + " memory=excluded.memory,due_at=excluded.due_at,learned=MAX(desktop_cards.learned,excluded.learned)",
        id,
        card == null ? before.dueAt().toString() : card.path("initial_due").asText(),
        json(after).toString(),
        after.dueAt().toString(),
        complete ? 1 : 0);
    Sql.execute(
        db,
        "UPDATE words SET status='learning',revision=revision+1,updated_at=? WHERE id=?",
        now.toString(),
        id);
    return Sql.first(
        db,
        "SELECT r.id,r.completed,c.due_at AS dueAt FROM desktop_reviews r JOIN desktop_cards c ON"
            + " r.word_id=c.word_id WHERE r.id=?",
        reviewId);
  }

  void undo(Connection db, String id) throws Exception {
    ObjectNode review = Sql.first(db, "SELECT * FROM desktop_reviews WHERE id=?", id);
    if (review == null) throw ApiException.notFound("反馈不存在");
    if (!review.path("undone_at").isNull()) return;
    String wordId = review.path("word_id").asText();
    ObjectNode latest =
        Sql.first(
            db,
            "SELECT id FROM desktop_reviews WHERE word_id=? AND undone_at IS NULL ORDER BY round"
                + " DESC LIMIT 1",
            wordId);
    if (!id.equals(latest.path("id").asText())) throw ApiException.conflict("只能撤销该词最近一次反馈");
    Sql.execute(
        db, "UPDATE desktop_reviews SET undone_at=? WHERE id=?", clock.instant().toString(), id);
    Sql.execute(
        db,
        "UPDATE desktop_practice_facts SET undone_at=? WHERE submission_id=?",
        clock.instant().toString(),
        review.path("submission_id").asText());
    DesktopFamiliarity.saveProjection(
        db, wordId, DesktopFamiliarity.assess(db, wordId, clock.instant()));
    ObjectNode card =
        Sql.first(db, "SELECT initial_due FROM desktop_cards WHERE word_id=?", wordId);
    FsrsSchedulingService.MemoryState state =
        fsrs.initial(Instant.parse(card.path("initial_due").asText()));
    boolean learned = false;
    for (JsonNode fact :
        Sql.rows(
            db,
            "SELECT * FROM desktop_reviews WHERE word_id=? AND undone_at IS NULL ORDER BY round",
            wordId)) {
      state =
          fsrs.review(
              wordId,
              state,
              fact.path("rating").asText(),
              Instant.parse(fact.path("created_at").asText()));
      learned |= fact.path("completed").asBoolean();
    }
    Sql.execute(
        db,
        "UPDATE desktop_cards SET memory=?,due_at=?,learned=? WHERE word_id=?",
        json(state).toString(),
        state.dueAt().toString(),
        learned ? 1 : 0,
        wordId);
    Sql.execute(
        db,
        "UPDATE words SET status=?,revision=revision+1 WHERE id=?",
        learned ? "learning" : "new",
        wordId);
  }

  private static int legacyCompleted(Connection db, String day, String kind) throws Exception {
    return Sql.first(
            db,
            "SELECT COUNT(DISTINCT word_id) AS n FROM desktop_reviews WHERE day=? AND kind=? AND"
                + " completed=1 AND undone_at IS NULL",
            day,
            kind)
        .path("n")
        .asInt();
  }

  static int completed(Connection db, String day, String kind) throws Exception {
    if (kind.equals("new"))
      return Sql.first(
              db, "SELECT COUNT(*) AS n FROM desktop_familiarity WHERE graduated_day=?", day)
          .path("n")
          .asInt();
    return Sql.first(
            db,
            "SELECT COUNT(DISTINCT word_id) AS n FROM desktop_practice_facts WHERE study_day=? AND"
                + " review_completed=1 AND undone_at IS NULL",
            day)
        .path("n")
        .asInt();
  }

  static ObjectNode json(FsrsSchedulingService.MemoryState value) {
    ObjectNode node =
        Json.MAPPER
            .createObjectNode()
            .put("round", value.round())
            .put("state", value.state())
            .put("dueAt", value.dueAt().toString());
    node.set("step", Json.MAPPER.valueToTree(value.step()));
    node.set("stability", Json.MAPPER.valueToTree(value.stability()));
    node.set("difficulty", Json.MAPPER.valueToTree(value.difficulty()));
    if (value.lastReviewAt() == null) node.putNull("lastReviewAt");
    else node.put("lastReviewAt", value.lastReviewAt().toString());
    return node;
  }

  static FsrsSchedulingService.MemoryState memory(JsonNode node) {
    return new FsrsSchedulingService.MemoryState(
        node.path("round").asInt(),
        node.path("state").asText(),
        node.path("step").isNull() ? null : node.path("step").asInt(),
        node.path("stability").isNull() ? null : node.path("stability").asDouble(),
        node.path("difficulty").isNull() ? null : node.path("difficulty").asDouble(),
        Instant.parse(node.path("dueAt").asText()),
        node.path("lastReviewAt").isNull()
            ? null
            : Instant.parse(node.path("lastReviewAt").asText()));
  }

  // 文件恢复时重放每张卡；不信任文件中的可重建记忆状态、轮次或到期投影。
  static void verify(Connection db) throws Exception {
    FsrsSchedulingService fsrs = new FsrsSchedulingService();
    for (JsonNode card : Sql.rows(db, "SELECT * FROM desktop_cards")) {
      String id = card.path("word_id").asText();
      var state = fsrs.initial(Instant.parse(card.path("initial_due").asText()));
      boolean learned = false;
      for (JsonNode fact :
          Sql.rows(
              db,
              "SELECT * FROM desktop_reviews WHERE word_id=? AND undone_at IS NULL ORDER BY round",
              id)) {
        if (!fact.path("algorithm").asText().equals(FsrsSchedulingService.ALGORITHM_VERSION)
            || fact.path("round").asInt() != state.round()
            || !json(state).equals(Json.MAPPER.readTree(fact.path("before_state").asText())))
          throw ApiException.badRequest("备份反馈顺序或调度前状态无效");
        var after =
            fsrs.review(
                id,
                state,
                fact.path("rating").asText(),
                Instant.parse(fact.path("created_at").asText()));
        if (!json(after).equals(Json.MAPPER.readTree(fact.path("after_state").asText())))
          throw ApiException.badRequest("备份反馈调度后状态无效");
        if (!LocalDate.ofInstant(
                Instant.parse(fact.path("created_at").asText()),
                ZoneId.of(fact.path("zone").asText()))
            .toString()
            .equals(fact.path("day").asText())) throw ApiException.badRequest("备份反馈日期无效");
        learned |= fact.path("completed").asBoolean();
        state = after;
      }
      if (!json(state).equals(Json.MAPPER.readTree(card.path("memory").asText()))
          || !state.dueAt().toString().equals(card.path("due_at").asText())
          || learned != card.path("learned").asBoolean())
        throw ApiException.badRequest("备份 FSRS 投影无法由反馈事实重放");
    }
  }
}
