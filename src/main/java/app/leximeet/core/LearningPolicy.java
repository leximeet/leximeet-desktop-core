package app.leximeet.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashSet;

// 统一学习模型 v2。事件决定经历，时钟决定自然衰减；积分不冒充 FSRS 记忆概率。
final class LearningPolicy {
  static final String VERSION = "leximeet.learning/2";
  static final int INITIAL_SCORE = 10, MAX_SCORE = 30;

  static ObjectNode project(Iterable<JsonNode> facts) {
    return project(facts, Instant.now());
  }

  // 同一有序事件集与同一 asOf 必须产生相同结果，供插件独立模式和未来同步重放。
  static ObjectNode project(Iterable<JsonNode> facts, Instant asOf) {
    var state = new Projection();
    var closed = new HashSet<String>();
    for (JsonNode fact : facts) {
      if (!fact.path("rule_version").asText().equals(VERSION)
          || !fact.path("undone_at").isNull() && !fact.path("undone_at").isMissingNode()) continue;
      Instant at = Instant.parse(fact.path("created_at").asText());
      if (at.isAfter(asOf)) continue;
      state.decay(at);
      state.lastDelta = 0;
      state.lastEffective = false;
      if (!closed.add(fact.path("attempt_id").asText(fact.path("id").asText()))) continue;
      state.samples++;
      if (state.started == null) state.started = at;
      String signal = fact.path("signal").asText("answer"), mode = fact.path("mode").asText();
      boolean aided = fact.path("assisted").asBoolean();
      boolean correct =
          signal.equals("familiar") || signal.equals("answer") && fact.path("correct").asBoolean();
      if (signal.equals("familiar")) state.familiar++;
      if (signal.equals("unfamiliar") || signal.equals("reveal")) state.unfamiliar++;
      int delta =
          signal.equals("reveal") || !correct
              ? -1
              : aided ? 0 : mode.equals("recall") || mode.equals("cloze") ? 2 : 1;
      int before = state.score;
      state.score = Math.clamp(before + delta, 0, MAX_SCORE);
      state.lastDelta = state.score - before;
      state.lastEffective = !aided && !mode.equals("copy") && !signal.equals("reveal");
      if (correct && state.lastEffective && !mode.equals("word-list")) state.independent++;
      if (state.graduated == null && state.score >= 20) {
        state.graduated = at;
        ZoneId zone = ZoneId.of(fact.path("zone").asText("UTC"));
        state.graduatedDay = at.atZone(zone).toLocalDate().toString();
        state.eligible = at.atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant();
        state.firstRecallDue = state.eligible;
      }
      if (before < MAX_SCORE && state.score == MAX_SCORE) {
        state.cycle = at;
        state.decayed = 0;
      } else if (state.cycle != null && state.score <= 20) {
        state.cycle = null;
        state.eligible = at;
      }
    }
    state.decay(asOf);
    return state.json().put("asOf", asOf.toString());
  }

  private static final class Projection {
    int score = INITIAL_SCORE, samples, familiar, unfamiliar, independent, lastDelta;
    long decayed;
    boolean lastEffective;
    Instant started, graduated, eligible, firstRecallDue, cycle;
    String graduatedDay;

    void decay(Instant at) {
      if (cycle == null) return;
      long elapsed = Duration.between(cycle, at).toSeconds();
      long count = Math.max(0, (elapsed - 3 * 86400) / (2 * 86400));
      long pending = Math.max(0, count - decayed);
      if (pending == 0) return;
      // 到 20 停止；用跨越阈值的准确时刻，而非下次打开应用的时刻释放复习资格。
      int lost = (int) Math.min(pending, Math.max(0, score - 20));
      score -= lost;
      if (score <= 20) {
        eligible = cycle.plusSeconds(3 * 86400L + (decayed + lost) * 2 * 86400L);
        cycle = null;
      }
      decayed = count;
    }

    ObjectNode json() {
      String status =
          cycle != null
              ? "mastered"
              : graduated != null ? "review" : started != null ? "learning" : "new";
      var node =
          Json.MAPPER
              .createObjectNode()
              .put("ruleVersion", VERSION)
              .put("score", score)
              .put("maximum", MAX_SCORE)
              .put("samples", samples)
              .put("familiar", familiar)
              .put("unfamiliar", unfamiliar)
              .put("independent", independent)
              .put("lastDelta", lastDelta)
              .put("lastEffective", lastEffective)
              .put("status", status)
              .put(
                  "label",
                  switch (status) {
                    case "mastered" -> "已熟悉";
                    case "review" -> "待复习";
                    case "learning" -> "学习中";
                    default -> "未学习";
                  })
              .put("needsReinforcement", score < INITIAL_SCORE)
              .put("unfamiliarWord", score == 0);
      put(node, "startedAt", started);
      put(node, "graduatedAt", graduated);
      node.put("graduatedDay", graduatedDay);
      put(node, "reviewEligibleAt", eligible);
      put(node, "firstRecallDueAt", firstRecallDue);
      put(node, "masteryCycleAt", cycle);
      put(
          node,
          "nextDecayAt",
          cycle == null ? null : cycle.plusSeconds(3 * 86400L + (decayed + 1) * 2 * 86400L));
      return node;
    }

    void put(ObjectNode node, String key, Instant value) {
      node.put(key, value == null ? null : value.toString());
    }
  }
}
