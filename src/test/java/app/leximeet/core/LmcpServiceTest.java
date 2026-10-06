package app.leximeet.core;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// 实际 SQLite 事务验证 LMCP 1.0.0 核心语义，不使用浏览器资料合并或模拟学习接口。
class LmcpServiceTest {
  @TempDir Path directory;
  static final String ORIGIN = "chrome-extension://" + "a".repeat(32),
      CONNECTION = uuid(),
      CLIENT = uuid();

  static ObjectNode object() {
    return Json.MAPPER.createObjectNode();
  }

  static String uuid() {
    return UUID.randomUUID().toString();
  }

  static JsonNode rpc(LeximeetService s, String method, JsonNode params, JsonNode auth) {
    return rpc(s, method, params, auth, CONNECTION, ORIGIN);
  }

  static JsonNode rpc(
      LeximeetService s,
      String method,
      JsonNode params,
      JsonNode auth,
      String connection,
      String origin) {
    var envelope =
        object()
            .put("apiMajor", 1)
            .put("requestId", uuid())
            .put("connectionId", connection)
            .put("method", method);
    envelope.set("params", params);
    if (auth != null) envelope.set("authorization", auth);
    var input = object().put("extensionOrigin", origin).put("connectionId", connection);
    input.set("envelope", envelope);
    return s.lmcp().rpc(input);
  }

  static JsonNode success(JsonNode r) {
    assertTrue(r.path("ok").asBoolean(), r.toPrettyString());
    return r.path("result");
  }

  static void error(String code, JsonNode r) {
    assertFalse(r.path("ok").asBoolean(), r.toPrettyString());
    assertEquals(code, r.path("error").path("code").asText(), r.toPrettyString());
  }

  static JsonNode pair(LeximeetService s) throws Exception {
    success(rpc(s, "hello", hello(s), null));
    var invitation =
        success(
                rpc(
                    s,
                    "requestConnection",
                    object().put("clientInstanceId", CLIENT).put("action", "request"),
                    null))
            .path("invitation");
    return success(rpc(s, "pair", pairParams(invitation, CLIENT), null));
  }

  static ObjectNode pairParams(JsonNode invitation, String client) {
    return object()
        .put("invitationId", invitation.path("invitationId").asText())
        .put("invitationToken", invitation.path("invitationToken").asText())
        .put("clientInstanceId", client);
  }

  static ObjectNode hello(LeximeetService s) {
    JsonNode c = s.lmcp().contract.metadata;
    var p =
        object()
            .put("apiMajor", 1)
            .put("connectionId", CONNECTION)
            .put("minApiVersion", "1.0.0")
            .put("clientKind", "browser")
            .put("clientInstanceId", CLIENT)
            .put("displayName", "隔离测试浏览器")
            .put("contractVersion", c.path("packageVersion").asText())
            .put("contractDigest", c.path("contractDigest").asText());
    p.set("requiredCapabilities", c.path("requiredCapabilities"));
    return p;
  }

  static ObjectNode custom(String id) {
    return object()
        .put("kind", "custom")
        .put("customId", id)
        .put("headword", "alpha")
        .put("language", "en");
  }

  static ObjectNode capture(JsonNode word, String event, String mutation) {
    var data =
        object()
            .put("surface", "alpha")
            .put("originalSentence", "alpha appears.")
            .put("savedExcerpt", "alpha appears.")
            .put("collectionIntent", "collect");
    data.set("word", word);
    data.putArray("occurrenceRanges").addObject().put("start", 0).put("end", 5);
    data.putArray("excerptRanges").addObject().put("start", 0).put("end", 5);
    data.set("annotation", object().put("note", "测试语境"));
    data.set(
        "source",
        object()
            .put("kind", "web")
            .put("title", "示例")
            .put("url", "https://example.invalid/article"));
    var p = object().put("eventId", event).put("mutationId", mutation).putNull("notebookId");
    p.set("data", data);
    return p;
  }

  LeximeetService service() throws Exception {
    return new LeximeetService(directory, DesktopWorkspaceTest.CLOCK);
  }

  @Test
  void fixedLocalContractAndRequiredCapabilitiesOnly() throws Exception {
    try (var s = service()) {
      var out = success(rpc(s, "hello", hello(s), null));
      assertEquals(19, out.path("methods").size());
      assertEquals(
          "61a44cc2f7f73fef81b007c844e1f2fa4deb955bf21a69236ee7b4f713808c49",
          out.path("contractDigest").asText());
      assertFalse(out.has("workspaceId"));
      // 正式版不同摘要不阻止连接；响应始终返回本机已经验证过的随包身份。
      var different =
          success(rpc(s, "hello", hello(s).put("contractDigest", "f".repeat(64)), null));
      assertEquals(out.path("contractDigest"), different.path("contractDigest"));
      var p = hello(s);
      p.putArray("requiredCapabilities").add("unknown/1");
      error("CAPABILITY_UNAVAILABLE", rpc(s, "hello", p, null));
      error("UNSUPPORTED_VERSION", rpc(s, "hello", hello(s).put("minApiVersion", "1.0.1"), null));
    }
  }

  @Test
  void newerReleasedClientCanUseItsDeclaredMinimumApi() throws Exception {
    try (var s = service()) {
      for (String version : new String[] {"1.0.1", "1.1.0"}) {
        var request =
            hello(s).put("contractVersion", version).put("contractDigest", "f".repeat(64));
        var result = success(rpc(s, "hello", request, null));
        assertEquals("1.0.0", result.path("apiVersion").asText());
        assertEquals("1.0.0", result.path("contractVersion").asText());
        assertFalse(result.has("workspaceId"));
        assertFalse(result.has("account"));
      }
    }
  }

  @Test
  void compatibilityDoesNotRelaxMajorSchemaOrConnectionIdentity() throws Exception {
    try (var s = service()) {
      for (String minimum : new String[] {"0.9.0", "2.0.0"})
        error("UNSUPPORTED_VERSION", rpc(s, "hello", hello(s).put("minApiVersion", minimum), null));
      error("INVALID_ARGUMENT", rpc(s, "hello", hello(s).put("apiMajor", 2), null));
      error("INVALID_ARGUMENT", rpc(s, "hello", hello(s).put("contractVersion", "2.0.0"), null));
      error("INVALID_ARGUMENT", rpc(s, "hello", hello(s).put("unexpected", true), null));
      error("STALE_CONNECTION", rpc(s, "hello", hello(s).put("connectionId", uuid()), null));
      error(
          "CONTRACT_MISMATCH",
          rpc(s, "hello", hello(s).put("contractVersion", "1.0.0-rc.6"), null));
    }
  }

  @Test
  void futurePrereleaseNegotiationRequiresExactCandidateIdentity() throws Exception {
    try (var s = service()) {
      // 纯兼容规则覆盖未来候选；上面的实际 RPC 用例验证正式版拒绝旧 rc。
      var metadata = s.lmcp().contract.metadata.deepCopy();
      ((ObjectNode) metadata).put("packageVersion", "1.1.0-rc.1");
      var request = hello(s).put("contractVersion", "1.1.0-rc.1");
      assertDoesNotThrow(() -> LmcpService.checkCompatibility(request, metadata));
      var differentDigest = request.deepCopy().put("contractDigest", "f".repeat(64));
      assertEquals(
          "CONTRACT_MISMATCH",
          assertThrows(
                  ApiException.class,
                  () -> LmcpService.checkCompatibility(differentDigest, metadata))
              .code());
      var differentVersion = request.deepCopy().put("contractVersion", "1.1.0-rc.2");
      assertEquals(
          "CONTRACT_MISMATCH",
          assertThrows(
                  ApiException.class,
                  () -> LmcpService.checkCompatibility(differentVersion, metadata))
              .code());
      var releasedPeer = request.deepCopy().put("contractVersion", "1.1.0");
      assertEquals(
          "CONTRACT_MISMATCH",
          assertThrows(
                  ApiException.class, () -> LmcpService.checkCompatibility(releasedPeer, metadata))
              .code());
    }
  }

  @Test
  void pairImmediatelyAuthorizesReadWithoutTransferringIndependentData() throws Exception {
    try (var s = service()) {
      var p = pair(s);
      assertTrue(p.has("owner"));
      assertFalse(p.has("attachmentId"));
      var workspace = success(rpc(s, "getWorkspace", object(), p.path("authorization")));
      assertEquals("unavailable", workspace.path("account").path("status").asText());
      assertEquals("desktop", workspace.path("account").path("source").asText());
      assertEquals("disabled", workspace.path("cloudSync").asText());
      assertEquals(0, s.snapshot().path("words").size());
      error("INVALID_ARGUMENT", rpc(s, "startPracticeSession", object(), p.path("authorization")));
    }
  }

  @Test
  void captureIsAtomicAndDeduplicatesMutationAndEventAcrossIntents() throws Exception {
    try (var s = service()) {
      var auth = pair(s).path("authorization");
      var p = capture(custom(uuid()), uuid(), uuid());
      var first = success(rpc(s, "recordEncounter", p, auth));
      assertEquals(first, success(rpc(s, "recordEncounter", p, auth)));
      var another = p.deepCopy().put("mutationId", uuid());
      assertEquals(first, success(rpc(s, "recordEncounter", another, auth)));
      assertEquals(1, s.snapshot().path("encounters").size());
      assertEquals(1, s.snapshot().path("words").size());
      var changed = p.deepCopy();
      changed.withObject("data").withObject("annotation").put("note", "不同笔记");
      error("IDEMPOTENCY_KEY_REUSED", rpc(s, "recordEncounter", changed, auth));
      changed.put("mutationId", uuid());
      error("EVENT_ID_REUSED", rpc(s, "recordEncounter", changed, auth));
      var operation =
          success(
              rpc(
                  s,
                  "getOperation",
                  object().put("mutationId", p.path("mutationId").asText()),
                  auth));
      assertEquals("applied", operation.path("status").asText());
      assertEquals(first, operation.path("result"));
      assertEquals(
          "unknown",
          success(rpc(s, "getOperation", object().put("mutationId", uuid()), auth))
              .path("status")
              .asText());
    }
  }

  @Test
  void rejectedCaptureKeepsReceiptAndRollsBackAllRelations() throws Exception {
    try (var s = service()) {
      var auth = pair(s).path("authorization");
      var p = capture(custom(uuid()), uuid(), uuid()).put("notebookId", uuid());
      error("ENTITY_NOT_FOUND", rpc(s, "recordEncounter", p, auth));
      assertEquals(0, s.snapshot().path("words").size());
      assertEquals(0, s.snapshot().path("encounters").size());
      assertEquals(
          "rejected",
          success(
                  rpc(
                      s,
                      "getOperation",
                      object().put("mutationId", p.path("mutationId").asText()),
                      auth))
              .path("status")
              .asText());
      error("ENTITY_NOT_FOUND", rpc(s, "recordEncounter", p, auth));
    }
  }

  @Test
  void invalidSourceAndUtf16RangesRejectBeforeWriting() throws Exception {
    try (var s = service()) {
      var auth = pair(s).path("authorization");
      var p = capture(custom(uuid()), uuid(), uuid());
      p.withObject("data").withObject("source").put("url", "https://example.invalid/#secret");
      error("INVALID_SOURCE_URL", rpc(s, "recordEncounter", p, auth));
      p = capture(custom(uuid()), uuid(), uuid());
      p.withObject("data").withArray("excerptRanges").get(0).withObject("").put("end", 4);
      error("INVALID_OCCURRENCE_RANGE", rpc(s, "recordEncounter", p, auth));
      assertEquals(0, s.snapshot().path("encounters").size());
    }
  }

  @Test
  void privateOverlayAndReadOnlyScoresUseSameDesktopData() throws Exception {
    try (var s = service()) {
      var auth = pair(s).path("authorization");
      var ref = custom(uuid());
      var p = capture(ref, uuid(), uuid());
      success(rpc(s, "recordEncounter", p, auth));
      var card = success(rpc(s, "getWord", object().set("word", ref), auth));
      assertEquals(10, card.path("learning").path("score").asInt());
      assertTrue(card.path("collected").asBoolean());
      assertEquals("测试语境", card.path("personal").path("note").asText());
      var matches = object().put("kind", "tokens");
      matches.putArray("tokens").add("alpha").add("absent");
      var m = success(rpc(s, "matchWords", matches, auth));
      assertEquals(2, m.path("results").size());
      assertEquals(ref, m.path("results").get(0).path("matches").get(0).path("word"));
      assertEquals(0, m.path("results").get(1).path("matches").size());
      var encounters = object().set("word", ref);
      assertEquals(1, success(rpc(s, "listEncounters", encounters, auth)).path("items").size());
    }
  }

  @Test
  void readLeaseAndRevisionInvalidationAreBounded() throws Exception {
    try (var s = service()) {
      var auth = pair(s).path("authorization");
      var workspace = success(rpc(s, "getWorkspace", object(), auth));
      assertFalse(
          success(
                  rpc(
                      s,
                      "getChanges",
                      object().put("sinceRevision", workspace.path("revision").asText()),
                      auth))
              .path("changed")
              .asBoolean());
      success(rpc(s, "recordEncounter", capture(custom(uuid()), uuid(), uuid()), auth));
      assertTrue(
          success(
                  rpc(
                      s,
                      "getChanges",
                      object().put("sinceRevision", workspace.path("revision").asText()),
                      auth))
              .path("changed")
              .asBoolean());
      error(
          "GENERATION_MISMATCH",
          rpc(s, "getChanges", object().put("sinceRevision", "99999999"), auth));
      assertEquals(
          30,
          java.time.Duration.between(
                  DesktopWorkspaceTest.CLOCK.instant(),
                  Instant.parse(workspace.path("readLeaseUntil").asText()))
              .toSeconds());
    }
  }

  @Test
  void disconnectRevokesAllConnectionSessionsButPreservesPairing() throws Exception {
    try (var s = service()) {
      JsonNode pair = pair(s),
          auth = pair.path("authorization"),
          renewed = success(rpc(s, "renewSession", object(), auth));
      var host = object().put("extensionId", "browser/1");
      host.putArray("capabilities").add("browser.context/1");
      success(rpc(s, "registerHost", host, auth));
      success(rpc(s, "disconnect", object(), renewed.path("authorization")));
      error("UNAUTHORIZED", rpc(s, "getWorkspace", object(), auth));
      error("CONNECTION_ENDED", rpc(s, "resumeSession", pair.path("pairingCredential"), null));
      var rows = s.lmcp().database.read(db -> Sql.rows(db, "SELECT revoked FROM lmcp_host_grants"));
      assertEquals(1, rows.get(0).path("revoked").asInt());
    }
  }

  @Test
  void revokedPairingCannotResumeOrRecoverOldReceipt() throws Exception {
    try (var s = service()) {
      JsonNode p = pair(s), auth = p.path("authorization");
      var encounter = capture(custom(uuid()), uuid(), uuid());
      success(rpc(s, "recordEncounter", encounter, auth));
      success(
          rpc(s, "revokePairing", object().put("pairingId", p.path("pairingId").asText()), auth));
      error("PAIRING_REVOKED", rpc(s, "resumeSession", p.path("pairingCredential"), null));
      error(
          "UNAUTHORIZED",
          rpc(
              s,
              "getOperation",
              object().put("mutationId", encounter.path("mutationId").asText()),
              auth));
      assertEquals(1, s.snapshot().path("encounters").size());
    }
  }

  @Test
  void nativePortAndOriginCannotReuseSession() throws Exception {
    try (var s = service()) {
      var auth = pair(s).path("authorization");
      error("STALE_CONNECTION", rpc(s, "getWorkspace", object(), auth, uuid(), ORIGIN));
      error(
          "PAIRING_REVOKED",
          rpc(
              s,
              "getWorkspace",
              object(),
              auth,
              CONNECTION,
              "chrome-extension://" + "b".repeat(32)));
    }
  }

  @Test
  void navigationIsDeliveredOnceAndCompletionHasTrustedReceipt() throws Exception {
    try (var s = service()) {
      JsonNode p = pair(s),
          auth = p.path("authorization"),
          nav = object().put("target", "library").put("mutationId", uuid());
      var first = success(rpc(s, "openInDesktop", nav, auth));
      assertTrue(first.has("navigationDelivery"));
      assertFalse(success(rpc(s, "openInDesktop", nav, auth)).has("navigationDelivery"));
      assertEquals(
          "pending",
          success(
                  rpc(
                      s,
                      "getOperation",
                      object().put("mutationId", nav.path("mutationId").asText()),
                      auth))
              .path("status")
              .asText());
      var complete = Json.object(first.path("navigationDelivery")).deepCopy();
      complete.remove("target");
      complete.put("action", "completeNavigation").put("opened", true);
      s.lmcp().manage(complete);
      assertTrue(success(rpc(s, "openInDesktop", nav, auth)).path("opened").asBoolean());
      assertTrue(
          success(
                  rpc(
                      s,
                      "getOperation",
                      object().put("mutationId", nav.path("mutationId").asText()),
                      auth))
              .path("result")
              .path("opened")
              .asBoolean());
    }
  }

  @Test
  void restartRevokesShortSessionsKeepsFactsAndPairing() throws Exception {
    JsonNode pair, auth;
    try (var s = service()) {
      pair = pair(s);
      auth = pair.path("authorization");
      success(rpc(s, "recordEncounter", capture(custom(uuid()), uuid(), uuid()), auth));
    }
    try (var s = service()) {
      error("UNAUTHORIZED", rpc(s, "getWorkspace", object(), auth));
      success(rpc(s, "resumeSession", pair.path("pairingCredential"), null));
      assertEquals(1, s.snapshot().path("encounters").size());
    }
  }
}
