package app.leximeet.core;

import static app.leximeet.core.DesktopWorkspaceTest.*;
import static app.leximeet.core.LmcpServiceTest.object;
import static app.leximeet.core.LmcpServiceTest.uuid;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// 真实 SQLite 七日领域旅程；窗口、应用替换与系统通知的证明由 Desktop 的独立应用测试补充。
class DesktopSevenDayTest {
  @TempDir Path directory;

  JsonNode detail(LeximeetService s) throws Exception {
    return s.desktop().query(object().put("kind", "detail").put("wordId", ALPHA));
  }

  void answer(LeximeetService s, String mode, boolean correct) throws Exception {
    var q =
        s.desktop()
            .query(
                object()
                    .put("kind", "practiceQuestion")
                    .put("wordId", ALPHA)
                    .put("mode", mode)
                    .put("attemptId", uuid()));
    var input =
        object()
            .put("action", "practiceRecord")
            .put("wordId", ALPHA)
            .put("mode", mode)
            .put("attemptId", q.path("attemptId").asText())
            .put("submissionId", uuid());
    if (mode.equals("word-list")) input.put("signal", correct ? "familiar" : "unfamiliar");
    else if (mode.equals("meaning-choice")) {
      String choice = "";
      for (JsonNode option : q.path("options"))
        if (option.path("text").asText().equals(q.path("meaning").asText()) == correct)
          choice = option.path("id").asText();
      assertFalse(choice.isEmpty());
      input.put("choiceId", choice);
    } else input.put("answer", correct ? "alpha" : "wrong");
    s.desktop().command(input);
    // 丢 ACK 的相同意图重放仍只产生一个事实。
    s.desktop().command(input);
  }

  @Test
  void threeLearningDaysRestAndThreeLearningDaysPreserveQueueNotesAndMasteryCycle()
      throws Exception {
    var f = new DesktopWorkspaceTest();
    f.directory = directory;
    var index = f.index(directory);
    var time = new CapturePolicyTransactionTest.MutableClock(CLOCK.instant());
    int beforeRest = 0;
    String protectedSince = "";
    for (int day = 0; day < 7; day++) {
      time.value = CLOCK.instant().plus(Duration.ofDays(day));
      try (var s = new LeximeetService(directory, time)) {
        f.mount(s, index);
        assertEquals("2026-10-0" + (day + 1), s.desktop().state().path("today").asText());
        if (day == 0) {
          f.goal(s, "dictionary");
          s.desktop()
              .command(
                  object()
                      .put("action", "capture")
                      .put("word", "alpha")
                      .put("context", "alpha appears here.")
                      .put("note", "**学习笔记**"));
          for (String mode : DesktopPractice.MODES) answer(s, mode, true);
          assertEquals(18, detail(s).path("familiarity").path("score").asInt());
        } else if (day == 1) {
          answer(s, "copy", true);
          answer(s, "copy", true);
          assertEquals(20, detail(s).path("familiarity").path("score").asInt());
          assertEquals(
              "2026-10-03",
              java.time.Instant.parse(
                      detail(s).path("familiarity").path("reviewEligibleAt").asText())
                  .atZone(time.getZone())
                  .toLocalDate()
                  .toString());
        } else if (day == 2) {
          for (int i = 0; i < 5; i++) answer(s, "recall", true);
          assertEquals(30, detail(s).path("familiarity").path("score").asInt());
          protectedSince = detail(s).path("familiarity").path("masteryCycleAt").asText();
          beforeRest =
              s.lmcp()
                  .database
                  .read(db -> Sql.first(db, "SELECT COUNT(*) AS n FROM desktop_practice_facts"))
                  .path("n")
                  .asInt();
        } else if (day == 3) {
          assertEquals(30, detail(s).path("familiarity").path("score").asInt());
          assertEquals(
              beforeRest,
              s.lmcp()
                  .database
                  .read(db -> Sql.first(db, "SELECT COUNT(*) AS n FROM desktop_practice_facts"))
                  .path("n")
                  .asInt());
        } else {
          answer(s, "copy", true);
          assertEquals(30, detail(s).path("familiarity").path("score").asInt());
          assertEquals(
              protectedSince, detail(s).path("familiarity").path("masteryCycleAt").asText());
        }
        if (day > 0) assertEquals("**学习笔记**", detail(s).path("note").asText());
        assertEquals(1, s.snapshot().path("encounters").size());
        assertTrue(
            s.lmcp()
                    .database
                    .read(
                        db ->
                            Sql.first(
                                db,
                                "SELECT COUNT(*) AS n FROM desktop_practice_facts GROUP BY"
                                    + " submission_id HAVING COUNT(*)>1"))
                == null);
      }
    }
    // 满分保护不由日常重开刷新；到精确衰减阈值释放待复习。
    time.value = java.time.Instant.parse(protectedSince).plus(Duration.ofDays(23));
    try (var s = new LeximeetService(directory, time)) {
      f.mount(s, index);
      assertEquals(20, detail(s).path("familiarity").path("score").asInt());
      assertEquals("review", detail(s).path("status").asText());
      for (int i = 0; i < 25; i++) answer(s, "word-list", false);
      assertEquals(0, detail(s).path("familiarity").path("score").asInt());
      assertTrue(detail(s).path("familiarity").path("unfamiliarWord").asBoolean());
      assertEquals("**学习笔记**", detail(s).path("note").asText());
    }
  }
}
