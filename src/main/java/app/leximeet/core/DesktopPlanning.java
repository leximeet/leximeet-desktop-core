package app.leximeet.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Connection;
import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;

// 学习规划 = 一个目标 + 一份日程。预览只读，确认后在一次事务中保存；取消没有副作用。
final class DesktopPlanning {
  static void validateGoal(Connection db, String goal, PublicLexicon lexicon) throws Exception {
    lexicon.require();
    if (!goal.equals("dictionary")
        && Sql.first(db, "SELECT id FROM lexicon.catalogs WHERE id=?", goal) == null)
      throw ApiException.badRequest("请选择主题词库或本地词典");
  }

  static void save(Connection db, JsonNode input, PublicLexicon lexicon, Clock clock)
      throws Exception {
    String goal = Json.text(input, "goal", 100, true);
    validateGoal(db, goal, lexicon);
    ObjectNode old = DesktopPlan.state(db);
    DesktopWords.integer(input, "dailyNew", old.path("dailyNew").asInt(), 1, 50);
    DesktopPlan.save(db, input);
    boolean same =
        goal.equals(old.path("goal").asText()) && !old.path("planId").asText("").isBlank();
    Sql.execute(
        db,
        "UPDATE desktop_profile SET goal=?,plan_id=?,started_on=?,saved_at=? WHERE id=1",
        goal,
        same ? old.path("planId").asText() : UUID.randomUUID().toString(),
        same ? old.path("startedOn").asText() : LocalDate.now(clock).toString(),
        clock.instant().toString());
  }

  static ObjectNode progress(Connection db, String goal, PublicLexicon lexicon) throws Exception {
    ObjectNode result =
        Json.MAPPER.createObjectNode().put("total", 0).put("learned", 0).put("remaining", 0);
    if (goal.isEmpty() || !lexicon.mounted()) return result;
    boolean dictionary = goal.equals("dictionary");
    long total =
        Sql.first(
                db,
                dictionary
                    ? "SELECT COUNT(*) AS n FROM lexicon.entries e WHERE "
                        + DesktopWords.activeEntry("e")
                    : "SELECT COUNT(DISTINCT e.id) AS n FROM lexicon.entries e JOIN lexicon.members m"
                        + " ON m.entry_id=e.id WHERE m.catalog_id=? AND "
                        + DesktopWords.activeEntry("e"),
                dictionary ? new Object[0] : new Object[] {goal})
            .path("n")
            .asLong();
    String restriction =
        dictionary
            ? "l.entry_id IN(SELECT id FROM lexicon.entries)"
            : "l.entry_id IN(SELECT entry_id FROM lexicon.members WHERE catalog_id=?)";
    long learned =
        Sql.first(
                db,
                "SELECT COUNT(DISTINCT l.entry_id) AS n FROM desktop_familiarity c JOIN"
                    + " desktop_word_links l ON l.word_id=c.word_id JOIN words w ON w.id=c.word_id"
                    + " WHERE w.deleted_at IS NULL AND c.graduated_at IS NOT"
                    + " NULL AND "
                    + restriction,
                dictionary ? new Object[0] : new Object[] {goal})
            .path("n")
            .asLong();
    return result
        .put("total", total)
        .put("learned", learned)
        .put("remaining", Math.max(0, total - learned));
  }

  static ObjectNode preview(
      Connection db, JsonNode input, PublicLexicon lexicon, Clock clock, JsonNode queue)
      throws Exception {
    Json.fields(input, "kind", "goal", "dailyNew", "daysAhead");
    String goal = Json.text(input, "goal", 100, true);
    validateGoal(db, goal, lexicon);
    int daily = DesktopWords.integer(input, "dailyNew", 10, 1, 50);
    int daysAhead = DesktopWords.integer(input, "daysAhead", 5, 1, 7);
    ObjectNode result = progress(db, goal, lexicon);
    LocalDate today = LocalDate.now(clock);
    int used = DesktopReviews.completed(db, today.toString(), "new");
    long remaining = result.path("remaining").asLong();
    int allowance = Math.max(0, daily - used);
    var firstWords = Json.MAPPER.createArrayNode();
    var included = new java.util.HashSet<String>();
    var plan = DesktopPlan.state(db);
    boolean current =
        goal.equals(plan.path("goal").asText())
            && plan.path("planEnabled").asBoolean()
            && daily == plan.path("dailyNew").asInt();
    if (current) {
      // 今日使用真实任务队列；额外采集词可能先占额度，因此不能虚构还能安排的目标词。
      for (JsonNode task : queue.path("tasks")) {
        if (!task.path("kind").asText().equals("new")) continue;
        String id = task.path("id").asText();
        JsonNode item =
            Sql.first(
                db,
                "SELECT e.id, e.headword AS word, e.meaning FROM lexicon.entries e"
                    + " LEFT JOIN desktop_word_links l ON l.entry_id=e.id WHERE (e.id=? OR l.word_id=?) AND "
                    + DesktopWords.predicate(goal)
                    + " AND "
                    + DesktopWords.activeEntry("e")
                    + " LIMIT 1",
                id,
                id);
        if (item != null && included.add(item.path("id").asText())) firstWords.add(item);
      }
    }
    // 每次只读取最多 7 天的新词，词序与任务来源一致；不为预测创建个人词条或复习事实。
    var pending =
        Sql.rows(
            db,
            "SELECT DISTINCT e.id,e.headword AS word,e.meaning FROM lexicon.entries e"
                + " LEFT JOIN words w ON "
                + DesktopWords.personalJoin("e")
                + " LEFT JOIN desktop_familiarity c ON c.word_id=w.id WHERE "
                + DesktopWords.predicate(goal)
                + " AND c.graduated_at IS NULL AND "
                + DesktopWords.activeEntry("e")
                + " ORDER BY CASE WHEN c.status='learning' THEN 0 ELSE 1 END,"
                + DesktopWords.order(goal)
                + ",e.id LIMIT ?",
            daily * daysAhead + firstWords.size());
    if (!current)
      for (JsonNode item : pending) {
        if (firstWords.size() >= allowance) break;
        if (included.add(item.path("id").asText())) firstWords.add(item);
      }
    long first = firstWords.size(), after = Math.max(0, remaining - first);
    long days = remaining == 0 ? 0 : 1 + (after + daily - 1) / daily;
    result
        .put("days", days)
        .put("todayUsed", used)
        .put("todayRemaining", allowance)
        .put("dailyNew", daily)
        .put("endOn", today.plusDays(Math.max(0, days - 1)).toString());
    var future = Json.MAPPER.createArrayNode();
    for (JsonNode item : pending)
      if (!included.contains(item.path("id").asText())) future.add(item);
    var dates = result.putArray("firstDays");
    for (int i = 0; i < Math.min(daysAhead, days); i++) {
      long count = i == 0 ? first : Math.min(daily, Math.max(0, after - (long) (i - 1) * daily));
      var row = dates.addObject().put("day", today.plusDays(i).toString()).put("count", count);
      var words = row.putArray("words");
      if (i == 0) {
        for (int j = 0; j < Math.min(50, firstWords.size()); j++) words.add(firstWords.get(j));
      } else {
        int offset = (i - 1) * daily;
        for (int j = offset; j < Math.min(future.size(), offset + count); j++)
          words.add(future.get(j));
      }
      row.put("wordsTruncated", words.size() < count);
    }
    return result;
  }
}
