package app.leximeet.core;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

// 官方 SDK 的固定数值基线；当前领域的事务与重放由 DesktopLearningReplayTest 验证。
class FsrsSchedulingServiceTest {

  @Test
  void officialJavaFsrsVectorAndMemoStateRemainExact() {
    FsrsSchedulingService fsrs = new FsrsSchedulingService();
    String[] ratings = {
      "good", "good", "good", "good", "good", "good", "again", "again", "good", "good", "good",
      "good", "good"
    };
    Instant reviewAt = Instant.parse("2022-11-29T12:30:00Z");
    var state = fsrs.initial(reviewAt);
    List<Integer> intervals = new ArrayList<>();
    for (String rating : ratings) {
      state = fsrs.review("official-vector", state, rating, reviewAt);
      intervals.add((int) ChronoUnit.DAYS.between(state.lastReviewAt(), state.dueAt()));
      reviewAt = state.dueAt();
    }
    assertEquals(
        List.of(0, 4, 14, 45, 135, 372, 0, 0, 2, 5, 10, 20, 40),
        intervals,
        "必须与 java-fsrs 1.0.0 的 testReviewCard 参考向量一致");

    String[] memoRatings = {"again", "good", "good", "good", "good", "good"};
    int[] elapsed = {0, 0, 1, 3, 8, 21};
    reviewAt = Instant.parse("2022-11-29T12:30:00Z");
    state = fsrs.initial(reviewAt);
    for (int index = 0; index < memoRatings.length; index++) {
      reviewAt = reviewAt.plus(Duration.ofDays(elapsed[index]));
      state = fsrs.review("memo-vector", state, memoRatings[index], reviewAt);
    }
    state = fsrs.review("memo-vector", state, "good", reviewAt);
    assertEquals(49.4472, state.stability(), 0.0001);
    assertEquals(6.8271, state.difficulty(), 0.0001);
  }
}
