package app.leximeet.core;

import static app.leximeet.core.DesktopWorkspaceTest.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// 真实冻结题目、事务和撤销重放：每日主动完成量不受 FSRS 自动排期限制。
class DesktopLearningReplayTest {
  @TempDir Path directory;
  final DesktopWorkspaceTest fixture = new DesktopWorkspaceTest();

  ObjectNode record(String mode, String attempt, String answer) {
    return Json.MAPPER
        .createObjectNode()
        .put("action", "practiceRecord")
        .put("wordId", ALPHA)
        .put("mode", mode)
        .put("answer", answer)
        .put("attemptId", attempt)
        .put("submissionId", UUID.randomUUID().toString());
  }

  JsonNode detail(LeximeetService service) throws Exception {
    return service.desktop().query(json("{\"kind\":\"detail\",\"wordId\":\"" + ALPHA + "\"}"));
  }

  JsonNode memory(LeximeetService service) throws Exception {
    var card =
        service
            .lmcp()
            .database
            .read(db -> Sql.first(db, "SELECT memory FROM desktop_cards WHERE word_id=?", ALPHA));
    return Json.MAPPER.readTree(card.path("memory").asText());
  }

  int completed(LeximeetService service, ObjectNode submission) throws Exception {
    return service
        .lmcp()
        .database
        .read(
            db ->
                Sql.first(
                        db,
                        "SELECT review_completed FROM desktop_practice_facts WHERE submission_id=?",
                        submission.path("submissionId").asText())
                    .path("review_completed")
                    .asInt());
  }

  void undo(LeximeetService service, ObjectNode submission) throws Exception {
    service
        .desktop()
        .command(
            Json.MAPPER
                .createObjectNode()
                .put("action", "undoPractice")
                .put("submissionId", submission.path("submissionId").asText()));
  }

  @Test
  void eligibleEarlyRecallCountsOncePerWordAndReplayKeepsExistingFsrsSchedule() throws Exception {
    var index = fixture.index(directory);
    var time = new CapturePolicyTransactionTest.MutableClock(CLOCK.instant());
    JsonNode expectedMemory;
    JsonNode expectedLearning;
    try (var service = new LeximeetService(directory, time)) {
      fixture.mount(service, index);
      fixture.goal(service, "dictionary");
      // 五次真实默写，每次等到实际 FSRS 到期，形成毕业后仍在未来的记忆日程。
      for (int i = 0; i < 5; i++) {
        if (i > 0) time.value = Instant.parse(detail(service).path("dueAt").asText());
        service.desktop().command(record("recall", "graduate-" + i, "alpha"));
      }
      time.value =
          Instant.parse(detail(service).path("familiarity").path("reviewEligibleAt").asText());
      assertEquals("review", detail(service).path("familiarity").path("status").asText());
      assertTrue(Instant.parse(detail(service).path("dueAt").asText()).isAfter(time.instant()));
      assertEquals(0, service.desktop().state().path("queue").path("reviews").size());
      assertEquals(0, service.desktop().state().path("queue").path("reviewDone").asInt());
      expectedMemory = memory(service);

      var first = record("recall", "early-first", "alpha");
      var second = record("recall", "early-second", "alpha");
      service.desktop().command(first);
      assertEquals(1, completed(service, first), "合格提前主动成功必须保留完成事件");
      service.desktop().command(second);
      assertEquals(1, completed(service, second));
      assertEquals(1, service.desktop().state().path("queue").path("reviewDone").asInt());
      assertEquals(expectedMemory, memory(service), "提前答对不延长被动 FSRS 日程");
      assertEquals(0, service.desktop().state().path("queue").path("reviews").size());
      expectedLearning = detail(service).path("familiarity");

      // 通过公开撤销命令触发完整事件重放，不直接改资料或手工设置到期时间。
      var temporary = record("copy", "temporary-copy", "alpha");
      service.desktop().command(temporary);
      undo(service, temporary);
      assertEquals(expectedLearning, detail(service).path("familiarity"));
      assertEquals(1, completed(service, first));
      assertEquals(1, completed(service, second));
      assertEquals(1, service.desktop().state().path("queue").path("reviewDone").asInt());
      assertEquals(expectedMemory, memory(service));
      assertEquals(0, service.desktop().state().path("queue").path("reviews").size());
    }
    try (var service = new LeximeetService(directory, time)) {
      fixture.mount(service, index);
      assertEquals(expectedLearning, detail(service).path("familiarity"));
      assertEquals(expectedMemory, memory(service));
      assertEquals(1, service.desktop().state().path("queue").path("reviewDone").asInt());
    }
  }

  @Test
  void replayRetainsEligibilityFirstIndependentSuccessAndMasteredRestExclusions() throws Exception {
    var index = fixture.index(directory);
    var time = new CapturePolicyTransactionTest.MutableClock(CLOCK.instant());
    try (var service = new LeximeetService(directory, time)) {
      fixture.mount(service, index);
      fixture.goal(service, "dictionary");
      for (int i = 0; i < 10; i++)
        service.desktop().command(record("copy", "graduate-" + i, "alpha"));
      var sameDay = record("recall", "same-day", "alpha");
      service.desktop().command(sameDay);
      assertEquals(0, completed(service, sameDay), "毕业当天不计复习完成量");
      time.value =
          Instant.parse(detail(service).path("familiarity").path("reviewEligibleAt").asText());
      var copy = record("copy", "not-recall", "alpha");
      service.desktop().command(copy);
      var reveal = record("word-list", "revealed", "").put("signal", "reveal");
      service.desktop().command(reveal);
      var aided = record("word-list", "revealed", "").put("signal", "familiar");
      service.desktop().command(aided);
      var wrong = record("recall", "corrected", "wrong");
      service.desktop().command(wrong);
      var corrected = record("recall", "corrected", "alpha");
      service.desktop().command(corrected);
      for (var excluded : new ObjectNode[] {sameDay, copy, reveal, aided, wrong, corrected})
        assertEquals(0, completed(service, excluded));
      assertEquals(0, service.desktop().state().path("queue").path("reviewDone").asInt());

      var independent = record("recall", "independent", "alpha");
      service.desktop().command(independent);
      assertEquals(1, completed(service, independent));
      for (int i = 0; i < 7; i++) service.desktop().command(record("copy", "master-" + i, "alpha"));
      assertEquals("mastered", detail(service).path("familiarity").path("status").asText());
      var rest = record("recall", "mastered-rest", "alpha");
      service.desktop().command(rest);
      assertEquals(0, completed(service, rest), "熟悉休息期主动练习不计复习额度");
      undo(service, rest);
      for (var excluded : new ObjectNode[] {sameDay, copy, reveal, aided, wrong, corrected})
        assertEquals(0, completed(service, excluded));
      assertEquals(1, completed(service, independent));
      assertEquals(1, service.desktop().state().path("queue").path("reviewDone").asInt());
    }
  }
}
