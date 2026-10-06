package app.leximeet.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.openspacedrepetition.Card;
import io.github.openspacedrepetition.Rating;
import io.github.openspacedrepetition.Scheduler;
import io.github.openspacedrepetition.State;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * FSRS-6 的窄适配层。
 *
 * <p>这里固定实现、参数和学习步骤，并关闭区间随机模糊。数据库只保存原始反馈事实与 可丢弃的当前投影；撤销时从初始到期时间按事实顺序重放，因此同一输入永远产生同一结果。
 * 严格度和“本轮完成”只用于产品会话统计，不参与记忆状态计算。
 */
final class FsrsSchedulingService {
  static final String ALGORITHM_VERSION = "fsrs-6/java-fsrs-1.0.0";
  static final String SOURCE_REVISION = "c561ee6c56239b621a0a45b16a6c2e688441eae5";
  static final double DESIRED_RETENTION = 0.9;
  static final int MAXIMUM_INTERVAL_DAYS = 36_500;
  static final double[] PARAMETERS = {
    0.2172, 1.1771, 3.2602, 16.1507, 7.0114, 0.57, 2.0966, 0.0069,
    1.5261, 0.112, 1.0178, 1.849, 0.1133, 0.3127, 2.2934, 0.2191,
    3.0004, 0.7536, 0.3332, 0.1437, 0.2
  };
  private static final Duration[] LEARNING_STEPS = {Duration.ofMinutes(1), Duration.ofMinutes(10)};
  private static final Duration[] RELEARNING_STEPS = {Duration.ofMinutes(10)};

  // 数据库中的完整卡片投影；null 数值保留 FSRS 的“尚未初始化”语义。
  record MemoryState(
      int round,
      String state,
      Integer step,
      Double stability,
      Double difficulty,
      Instant dueAt,
      Instant lastReviewAt) {}

  record ReviewFact(int round, String rating, Instant reviewedAt, String algorithmVersion) {}

  ObjectNode profile() {
    ObjectNode output = Json.MAPPER.createObjectNode();
    output.put("algorithmVersion", ALGORITHM_VERSION);
    output.put("sourceRevision", SOURCE_REVISION);
    output.put("desiredRetention", DESIRED_RETENTION);
    output.put("maximumIntervalDays", MAXIMUM_INTERVAL_DAYS);
    output.put("enableFuzzing", false);
    output.putArray("learningStepsMinutes").add(1).add(10);
    output.putArray("relearningStepsMinutes").add(10);
    var parameters = output.putArray("parameters");
    for (double value : PARAMETERS) parameters.add(value);
    return output;
  }

  String profileJson() {
    try {
      return Json.MAPPER.writeValueAsString(profile());
    } catch (Exception error) {
      throw new IllegalStateException("无法序列化固定 FSRS 参数", error);
    }
  }

  void requireProfile(String version, String parametersJson) {
    if (!ALGORITHM_VERSION.equals(version))
      throw ApiException.badRequest("当前格式不支持该 FSRS 算法版本：" + version);
    try {
      JsonNode actual = Json.MAPPER.readTree(parametersJson);
      if (!profile().equals(actual)) throw ApiException.badRequest("FSRS 参数与版本不匹配");
    } catch (ApiException error) {
      throw error;
    } catch (Exception error) {
      throw ApiException.badRequest("FSRS 参数不是有效 JSON");
    }
  }

  MemoryState initial(Instant dueAt) {
    return new MemoryState(1, "learning", 0, null, null, dueAt, null);
  }

  MemoryState review(String taskId, MemoryState before, String rating, Instant reviewedAt) {
    // 用户可主动提前复习，但不能提交早于上一条事实的“时间旅行”记录。
    if (before.lastReviewAt() != null && reviewedAt.isBefore(before.lastReviewAt()))
      throw ApiException.conflict("复习时间不能早于上一轮反馈");
    Card card = toCard(taskId, before);
    Card next;
    try {
      next = scheduler().reviewCard(card, rating(rating), reviewedAt).card();
    } catch (RuntimeException error) {
      // 调用方在同一事务中先算后写；异常不会留下半条反馈或部分卡片状态。
      throw new IllegalStateException("FSRS 调度失败，原反馈与到期状态未改变", error);
    }
    return fromCard(before.round() + 1, next);
  }

  MemoryState replay(String taskId, Instant initialDueAt, List<ReviewFact> facts) {
    MemoryState state = initial(initialDueAt);
    for (ReviewFact fact : facts) {
      if (!ALGORITHM_VERSION.equals(fact.algorithmVersion()))
        throw ApiException.conflict("复习历史包含当前无法重放的算法版本");
      if (fact.round() != state.round()) throw ApiException.conflict("复习历史轮次不连续，拒绝伪造算法状态");
      state = review(taskId, state, fact.rating(), fact.reviewedAt());
    }
    return state;
  }

  double retrievability(MemoryState state, Instant now) {
    if (state.lastReviewAt() == null || state.stability() == null) return 0;
    return scheduler().getCardRetrievability(toCard("projection", state), now);
  }

  private Scheduler scheduler() {
    return Scheduler.builder()
        .parameters(PARAMETERS.clone())
        .desiredRetention(DESIRED_RETENTION)
        .learningSteps(LEARNING_STEPS.clone())
        .relearningSteps(RELEARNING_STEPS.clone())
        .maximumInterval(MAXIMUM_INTERVAL_DAYS)
        .enableFuzzing(false)
        .build();
  }

  private static Card toCard(String taskId, MemoryState value) {
    return Card.builder()
        .cardId(taskId.hashCode())
        .state(State.valueOf(value.state().toUpperCase(java.util.Locale.ROOT)))
        .step(value.step())
        .stability(value.stability())
        .difficulty(value.difficulty())
        .due(value.dueAt())
        .lastReview(value.lastReviewAt())
        .build();
  }

  private static MemoryState fromCard(int round, Card card) {
    return new MemoryState(
        round,
        card.getState().name().toLowerCase(java.util.Locale.ROOT),
        card.getStep(),
        card.getStability(),
        card.getDifficulty(),
        card.getDue(),
        card.getLastReview());
  }

  private static Rating rating(String value) {
    return switch (value) {
      case "again" -> Rating.AGAIN;
      case "hard" -> Rating.HARD;
      case "good" -> Rating.GOOD;
      case "easy" -> Rating.EASY;
      default -> throw ApiException.badRequest("未知复习反馈");
    };
  }
}
