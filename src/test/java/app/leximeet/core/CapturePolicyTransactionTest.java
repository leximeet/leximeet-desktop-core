package app.leximeet.core;

import static app.leximeet.core.DesktopWorkspaceTest.*;
import static app.leximeet.core.LmcpServiceTest.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// 使用真实 SQLite 事务覆盖手动、剪贴板和已授权插件的共同采集边界。
class CapturePolicyTransactionTest {
  @TempDir Path directory;

  DesktopWorkspaceTest fixture() {
    var f = new DesktopWorkspaceTest();
    f.directory = directory;
    return f;
  }

  ObjectNode manual(String context) {
    return object().put("action", "capture").put("word", "alpha").put("context", context);
  }

  ObjectNode publicRef() {
    return DesktopDataModel.wordRef(ALPHA, "", "alpha", "0.0.3");
  }

  @Test
  void caseVariantsAcrossEntryPointsReuseSafeContextButDistinctSentencesStaySeparate()
      throws Exception {
    var f = fixture();
    try (var s = new LeximeetService(directory, CLOCK)) {
      f.mount(s, f.index(directory));
      f.goal(s, "dictionary");
      s.updateSettings(object().put("clipboardCaptureEnabled", true));
      var first = s.desktop().command(manual("Alpha is useful.")).path("captureResult");
      var preflight = object().put("kind", "clipboardCandidates").put("text", "ALPHA is useful.");
      preflight.putArray("words").add("ALPHA").add("alpha");
      assertTrue(s.desktop().query(preflight).path("matches").isEmpty());
      var clipboard =
          s.desktop()
              .command(
                  object()
                      .put("action", "captureClipboard")
                      .put("goal", "dictionary")
                      .put("wordId", ALPHA)
                      .put("context", "alpha is useful."));
      assertEquals(
          "duplicate-context", clipboard.path("captureResult").path("captureStatus").asText());
      var auth = pair(s).path("authorization");
      var input = capture(publicRef(), uuid(), uuid());
      input
          .withObject("data")
          .put("surface", "ALPHA")
          .put("originalSentence", "ALPHA is useful.")
          .put("savedExcerpt", "ALPHA is useful.");
      var repeated = success(rpc(s, "recordEncounter", input, auth));
      assertEquals("duplicate-context", repeated.path("captureStatus").asText());
      assertEquals(first.path("entity").path("entityId"), repeated.path("entity").path("entityId"));
      assertEquals(
          "Alpha is useful.", repeated.path("entity").path("data").path("savedExcerpt").asText());
      assertEquals(1, s.snapshot().path("encounters").size());
      assertEquals(
          "created",
          s.desktop()
              .command(manual("Alpha is useful!"))
              .path("captureResult")
              .path("captureStatus")
              .asText());
      assertEquals(
          "created",
          s.desktop()
              .command(manual("Alpha appears in another sentence."))
              .path("captureResult")
              .path("captureStatus")
              .asText());
      assertEquals(3, s.snapshot().path("encounters").size());
      assertEquals(1, s.snapshot().path("words").size());
    }
  }

  @Test
  void defaultsAndStrictSettingsPersistAcrossRestart() throws Exception {
    JsonNode expected;
    try (var s = new LeximeetService(directory, CLOCK)) {
      var settings = s.snapshot().path("settings");
      assertEquals(7, settings.path("captureDuplicateWindowDays").asInt());
      assertTrue(settings.path("captureSensitiveRedactionEnabled").asBoolean());
      assertEquals(500, settings.path("captureContextMaxLength").asInt());
      for (String invalid :
          new String[] {
            "{\"captureDuplicateWindowDays\":\"7\"}",
            "{\"captureDuplicateWindowDays\":366}",
            "{\"captureDuplicateWindowDays\":-1}",
            "{\"captureSensitiveRedactionEnabled\":1}",
            "{\"captureContextMaxLength\":119}",
            "{\"captureContextMaxLength\":2001}",
            "{\"captureContextMaxLength\":500.5}"
          }) {
        assertThrows(ApiException.class, () -> s.updateSettings(json(invalid)));
        assertEquals(settings, s.snapshot().path("settings"));
      }
      expected =
          s.updateSettings(
                  object()
                      .put("captureDuplicateWindowDays", 0)
                      .put("captureSensitiveRedactionEnabled", false)
                      .put("captureContextMaxLength", 120))
              .path("settings");
    }
    try (var s = new LeximeetService(directory, CLOCK)) {
      assertEquals(expected, s.snapshot().path("settings"));
    }
  }

  @Test
  void manualAndClipboardAreSafeBeforeNotificationAndDuplicateHasNoSideEffects() throws Exception {
    var f = fixture();
    try (var s = new LeximeetService(directory, CLOCK)) {
      f.mount(s, f.index(directory));
      f.goal(s, "dictionary");
      s.updateSettings(object().put("clipboardCaptureEnabled", true));
      String raw =
          "Another sentence. Alpha uses mail=a@example.com and 13800138000. Private ending.";
      var preflight = object().put("kind", "clipboardCandidates").put("text", raw);
      preflight.putArray("words").add("alpha");
      var context = s.desktop().query(preflight).path("matches").get(0).path("context").asText();
      assertEquals("Alpha uses mail=xxx and xxx.", context);
      var first = s.desktop().command(manual(raw).put("note", "password=hidden"));
      var created = first.path("captureResult");
      assertEquals("created", created.path("captureStatus").asText());
      assertFalse(created.toString().contains("a@example.com"));
      assertFalse(created.toString().contains("13800138000"));
      assertEquals(
          "password=xxx",
          s.desktop()
              .query(object().put("kind", "detail").put("wordId", ALPHA))
              .path("note")
              .asText());
      assertTrue(s.desktop().query(preflight).path("matches").isEmpty());
      JsonNode before = s.snapshot();
      var repeated = s.desktop().command(manual(context).put("note", "must not replace"));
      assertEquals(
          "duplicate-context", repeated.path("captureResult").path("captureStatus").asText());
      assertEquals(
          created.path("entity").path("entityId"),
          repeated.path("captureResult").path("entity").path("entityId"));
      assertEquals(before.path("words"), s.snapshot().path("words"));
      assertEquals(before.path("encounters"), s.snapshot().path("encounters"));
      var clip =
          s.desktop()
              .command(
                  object()
                      .put("action", "captureClipboard")
                      .put("goal", "dictionary")
                      .put("wordId", ALPHA)
                      .put("context", context));
      assertFalse(clip.path("clipboardAccepted").asBoolean());
      assertEquals("duplicate-context", clip.path("captureResult").path("captureStatus").asText());
      assertEquals(1, s.snapshot().path("encounters").size());
      assertEquals(
          10,
          s.desktop()
              .query(object().put("kind", "detail").put("wordId", ALPHA))
              .path("familiarity")
              .path("score")
              .asInt());
    }
  }

  @Test
  void connectedCaptureUsesOwnerPolicyAndReplayKeepsOriginalSafeReceipt() throws Exception {
    var f = fixture();
    try (var s = new LeximeetService(directory, CLOCK)) {
      f.mount(s, f.index(directory));
      var auth = pair(s).path("authorization");
      assertEquals(
          7,
          success(rpc(s, "getWorkspace", object(), auth))
              .path("capturePolicy")
              .path("duplicateWindowDays")
              .asInt());
      var input = capture(publicRef(), uuid(), uuid());
      input
          .withObject("data")
          .put("originalSentence", "alpha mail a@example.com.")
          .put("savedExcerpt", "alpha mail a@example.com.");
      var first = success(rpc(s, "recordEncounter", input, auth));
      assertEquals(
          "alpha mail xxx.", first.path("entity").path("data").path("savedExcerpt").asText());
      s.updateSettings(object().put("captureSensitiveRedactionEnabled", false));
      assertEquals(first, success(rpc(s, "recordEncounter", input, auth)));
      var retried = input.deepCopy().put("mutationId", uuid());
      assertEquals(first, success(rpc(s, "recordEncounter", retried, auth)));
      retried.withObject("data").withObject("annotation").put("note", "changed");
      error("EVENT_ID_REUSED", rpc(s, "recordEncounter", retried.put("mutationId", uuid()), auth));
      s.updateSettings(object().put("captureSensitiveRedactionEnabled", true));
      var distinctEvent = input.deepCopy().put("eventId", uuid()).put("mutationId", uuid());
      distinctEvent.withObject("data").withObject("annotation").put("note", "ignored new note");
      var duplicate = success(rpc(s, "recordEncounter", distinctEvent, auth));
      assertEquals("duplicate-context", duplicate.path("captureStatus").asText());
      assertEquals(first.path("entity"), duplicate.path("entity"));
      assertEquals(1, s.snapshot().path("encounters").size());
      assertEquals(
          duplicate,
          success(
                  rpc(
                      s,
                      "getOperation",
                      object().put("mutationId", distinctEvent.path("mutationId").asText()),
                      auth))
              .path("result"));
    }
  }

  @Test
  void duplicateWindowIsRollingUtcInclusiveAndZeroDisablesIt() throws Exception {
    var f = fixture();
    var time = new MutableClock(CLOCK.instant());
    try (var s = new LeximeetService(directory, time)) {
      f.mount(s, f.index(directory));
      var created = s.desktop().command(manual("alpha appears.")).path("captureResult");
      time.value = CLOCK.instant().plusSeconds(7 * 86400);
      assertEquals(
          "duplicate-context",
          s.desktop()
              .command(manual("alpha appears."))
              .path("captureResult")
              .path("captureStatus")
              .asText());
      time.value = time.value.plusMillis(1);
      var next = s.desktop().command(manual("alpha appears.")).path("captureResult");
      assertEquals("created", next.path("captureStatus").asText());
      assertNotEquals(
          created.path("entity").path("entityId"), next.path("entity").path("entityId"));
      s.updateSettings(object().put("captureDuplicateWindowDays", 0));
      assertEquals(
          "created",
          s.desktop()
              .command(manual("alpha appears."))
              .path("captureResult")
              .path("captureStatus")
              .asText());
      assertEquals(3, s.snapshot().path("encounters").size());
    }
  }

  @Test
  void sensitiveSelectionRejectsWithoutWordOrReceiptAndIdentitiesAreNeverTextMerged()
      throws Exception {
    var f = fixture();
    try (var s = new LeximeetService(directory, CLOCK)) {
      f.mount(s, f.index(directory));
      f.goal(s, "dictionary");
      var preflight = object().put("kind", "clipboardCandidates").put("text", "alpha@example.com");
      preflight.putArray("words").add("alpha");
      assertTrue(s.desktop().query(preflight).path("matches").isEmpty());
      var rejected =
          assertThrows(ApiException.class, () -> s.desktop().command(manual("alpha@example.com")));
      assertEquals("SENSITIVE_SELECTION", rejected.code());
      assertTrue(s.snapshot().path("words").isEmpty());
      var auth = pair(s).path("authorization");
      success(rpc(s, "recordEncounter", capture(custom(uuid()), uuid(), uuid()), auth));
      assertEquals(
          "created",
          success(rpc(s, "recordEncounter", capture(custom(uuid()), uuid(), uuid()), auth))
              .path("captureStatus")
              .asText());
      assertEquals(2, s.snapshot().path("encounters").size());
    }
  }

  @Test
  void allPersistentTextAndRejectedReceiptsExcludeOriginalSecrets() throws Exception {
    var f = fixture();
    try (var s = new LeximeetService(directory, CLOCK)) {
      f.mount(s, f.index(directory));
      var auth = pair(s).path("authorization");
      var input = capture(publicRef(), uuid(), uuid());
      input
          .withObject("data")
          .put("originalSentence", "alpha uses capture_secret@example.com.")
          .put("savedExcerpt", "alpha uses capture_secret@example.com.");
      input.withObject("data").withObject("annotation").put("note", "password=CAPTURE_SECRET_9281");
      input
          .withObject("data")
          .withObject("source")
          .put("title", "token=CAPTURE_SECRET_9281")
          .put("url", "https://example.invalid/?token=CAPTURE_SECRET_9281");
      var created = success(rpc(s, "recordEncounter", input, auth));
      assertFalse(created.toString().contains("CAPTURE_SECRET_9281"));
      var repeated = input.deepCopy().put("eventId", uuid()).put("mutationId", uuid());
      repeated
          .withObject("data")
          .withObject("annotation")
          .put("note", "password=DUPLICATE_SECRET_5832");
      assertEquals(
          "duplicate-context",
          success(rpc(s, "recordEncounter", repeated, auth)).path("captureStatus").asText());
      var bad = capture(publicRef(), uuid(), uuid());
      bad.withObject("data")
          .put("originalSentence", "alpha@example.com")
          .put("savedExcerpt", "alpha@example.com");
      error("SENSITIVE_SELECTION", rpc(s, "recordEncounter", bad, auth));
      // 不只检查主表，也检查幂等回执、事件投影等全部个人表的实际文本列。
      s.lmcp()
          .database
          .read(
              db -> {
                for (JsonNode table :
                    Sql.rows(
                        db,
                        "SELECT name FROM main.sqlite_master WHERE type='table' AND name NOT LIKE"
                            + " 'sqlite_%'")) {
                  String name = table.path("name").asText().replace("\"", "\"\"");
                  for (JsonNode column : Sql.rows(db, "PRAGMA main.table_info(\"" + name + "\")")) {
                    String field = column.path("name").asText().replace("\"", "\"\"");
                    for (JsonNode value :
                        Sql.rows(
                            db,
                            "SELECT \""
                                + field
                                + "\" AS value FROM main.\""
                                + name
                                + "\" WHERE typeof(\""
                                + field
                                + "\")='text'")) {
                      String text = value.path("value").asText();
                      for (String secret :
                          new String[] {
                            "capture_secret@example.com",
                            "CAPTURE_SECRET_9281",
                            "DUPLICATE_SECRET_5832",
                            "alpha@example.com"
                          }) assertFalse(text.contains(secret), "敏感明文出现在 " + name + "." + field);
                    }
                  }
                }
                return null;
              });
    }
  }

  @Test
  void turningOnRedactionProjectsDuplicateReceiptWithoutRewritingHistory() throws Exception {
    var f = fixture();
    try (var s = new LeximeetService(directory, CLOCK)) {
      f.mount(s, f.index(directory));
      var auth = pair(s).path("authorization");
      s.updateSettings(object().put("captureSensitiveRedactionEnabled", false));
      var input = capture(publicRef(), uuid(), uuid());
      input.withObject("data").withObject("annotation").put("note", "password=OLD_SECRET");
      input.withObject("data").withObject("source").put("title", "token=OLD_SECRET");
      var original = success(rpc(s, "recordEncounter", input, auth));
      assertTrue(original.toString().contains("OLD_SECRET"));
      s.updateSettings(object().put("captureSensitiveRedactionEnabled", true));
      String revision = success(rpc(s, "getWorkspace", object(), auth)).path("revision").asText();
      var receipt =
          success(
              rpc(
                  s,
                  "recordEncounter",
                  input.deepCopy().put("eventId", uuid()).put("mutationId", uuid()),
                  auth));
      assertEquals("duplicate-context", receipt.path("captureStatus").asText());
      assertEquals(
          original.path("entity").path("entityId"), receipt.path("entity").path("entityId"));
      assertEquals(
          original.path("entity").path("revision"), receipt.path("entity").path("revision"));
      assertFalse(receipt.toString().contains("OLD_SECRET"));
      assertEquals(
          revision, success(rpc(s, "getWorkspace", object(), auth)).path("revision").asText());
      JsonNode history =
          s.lmcp().database.read(db -> Sql.first(db, "SELECT annotation FROM encounter_details"));
      assertTrue(history.path("annotation").asText().contains("OLD_SECRET"));
    }
  }

  @Test
  void automaticCaptureIgnoresSensitiveOccurrencesAndKeepsFirstSafeSentence() throws Exception {
    var f = fixture();
    try (var s = new LeximeetService(directory, CLOCK)) {
      f.mount(s, f.index(directory));
      f.goal(s, "dictionary");
      String text = "An Alpha arrived; password=Alpha. Next alpha is ripe.";
      var input = object().put("kind", "clipboardCandidates").put("text", text);
      input.putArray("words").add("alpha");
      assertEquals(
          "An Alpha arrived; password=xxx.",
          s.desktop().query(input).path("matches").get(0).path("context").asText());
      assertEquals(
          "An Alpha arrived; password=xxx.",
          s.desktop()
              .command(manual(text))
              .path("captureResult")
              .path("entity")
              .path("data")
              .path("savedExcerpt")
              .asText());
      var sensitiveFirst = "password=alpha. Later alpha is ripe.";
      assertEquals(
          "Later alpha is ripe.",
          s.desktop()
              .command(manual(sensitiveFirst))
              .path("captureResult")
              .path("entity")
              .path("data")
              .path("savedExcerpt")
              .asText());
      assertEquals(2, s.snapshot().path("encounters").size());
    }
  }

  static final class MutableClock extends Clock {
    Instant value;

    MutableClock(Instant value) {
      this.value = value;
    }

    @Override
    public ZoneId getZone() {
      return ZoneId.of("Asia/Shanghai");
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return Clock.fixed(value, zone);
    }

    @Override
    public Instant instant() {
      return value;
    }
  }
}
