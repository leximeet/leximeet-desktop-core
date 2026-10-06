package app.leximeet.core;

import static app.leximeet.core.LmcpServiceTest.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// 真实 SQLite 授权事务：发现没有业务权限，确认与断开可以跨端口、跨进程查证。
class LmcpInvitationTest {
  @TempDir Path directory;

  static final class Time extends Clock {
    Instant current = Instant.parse("2026-10-03T00:00:00Z");

    @Override
    public ZoneId getZone() {
      return ZoneId.of("UTC");
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return current;
    }

    void advance(long seconds) {
      current = current.plusSeconds(seconds);
    }
  }

  JsonNode status(LeximeetService s, JsonNode credential, String invitation) {
    var p = object().put("clientInstanceId", CLIENT);
    if (credential != null) p.set("pairingCredential", credential);
    if (invitation != null) p.put("invitationId", invitation);
    return success(rpc(s, "getConnectionStatus", p, null));
  }

  JsonNode request(LeximeetService s) {
    return success(
            rpc(
                s,
                "requestConnection",
                object().put("clientInstanceId", CLIENT).put("action", "request"),
                null))
        .path("invitation");
  }

  @Test
  void discoveryAndDesktopInvitationNeverAuthorizeOrExposePrivateData() throws Exception {
    try (var s = new LeximeetService(directory, new Time())) {
      error(
          "STALE_CONNECTION",
          rpc(
              s,
              "requestConnection",
              object().put("clientInstanceId", CLIENT).put("action", "request"),
              null));
      var h = success(rpc(s, "hello", hello(s), null));
      assertEquals("词遇 Desktop", h.path("displayName").asText());
      assertFalse(h.has("workspaceId"));
      var pending =
          s.lmcp()
              .manage(object().put("action", "requestConnection").put("clientInstanceId", CLIENT));
      var target = pending.path("discoveredClients").get(0);
      assertEquals("pending", target.path("invitation").path("state").asText());
      assertFalse(pending.toString().contains("invitationToken"));
      assertEquals(0, pending.path("clients").size());
      var result = status(s, null, null);
      assertEquals("unpaired", result.path("connectionState").asText());
      assertEquals("desktop", result.path("invitation").path("requestedBy").asText());
      assertFalse(result.has("workspaceId"));
      assertEquals(0, s.snapshot().path("words").size());
      error(
          "UNAUTHORIZED",
          rpc(
              s,
              "getWorkspace",
              object(),
              object()
                  .put("sessionId", uuid())
                  .put("sessionToken", "x".repeat(43))
                  .put("workspaceId", uuid())
                  .put("generation", uuid())
                  .put("authorizationEpoch", "1")));
    }
  }

  @Test
  void repeatedRequestsShareOneTicketAndCancellationIsExplicit() throws Exception {
    try (var s = new LeximeetService(directory, new Time())) {
      success(rpc(s, "hello", hello(s), null));
      var ticket = request(s);
      assertEquals(ticket, request(s));
      var cancelled =
          success(
              rpc(
                  s,
                  "requestConnection",
                  object()
                      .put("action", "cancel")
                      .put("clientInstanceId", CLIENT)
                      .put("invitationId", ticket.path("invitationId").asText()),
                  null));
      assertEquals("cancelled", cancelled.path("invitationState").asText());
      assertTrue(cancelled.path("invitation").isNull());
      error("INVITATION_CANCELLED", rpc(s, "pair", pairParams(ticket, CLIENT), null));
      assertEquals(0, s.lmcp().manage(object().put("action", "state")).path("clients").size());
      assertNotEquals(ticket.path("invitationId"), request(s).path("invitationId"));
    }
  }

  @Test
  void ticketIsBoundToActualOriginClientAndCurrentHelloPort() throws Exception {
    try (var s = new LeximeetService(directory, new Time())) {
      success(rpc(s, "hello", hello(s), null));
      var ticket = request(s);
      String otherClient = uuid(),
          otherPort = uuid(),
          otherOrigin = "chrome-extension://" + "b".repeat(32);
      success(
          rpc(
              s,
              "hello",
              hello(s).put("clientInstanceId", otherClient).put("connectionId", otherPort),
              null,
              otherPort,
              ORIGIN));
      error(
          "INVITATION_INVALID",
          rpc(s, "pair", pairParams(ticket, otherClient), null, otherPort, ORIGIN));
      success(
          rpc(s, "hello", hello(s).put("connectionId", otherPort), null, otherPort, otherOrigin));
      error(
          "INVITATION_INVALID",
          rpc(s, "pair", pairParams(ticket, CLIENT), null, otherPort, otherOrigin));
      error(
          "INVITATION_INVALID",
          rpc(s, "pair", pairParams(ticket, CLIENT).put("invitationToken", "x".repeat(43)), null));
    }
  }

  @Test
  void unacceptedTicketExpiresWithoutGrantingAuthority() throws Exception {
    var time = new Time();
    try (var s = new LeximeetService(directory, time)) {
      success(rpc(s, "hello", hello(s), null));
      var ticket = request(s);
      time.advance(120);
      success(rpc(s, "hello", hello(s), null));
      assertEquals(
          "expired",
          status(s, null, ticket.path("invitationId").asText()).path("invitationState").asText());
      error("INVITATION_EXPIRED", rpc(s, "pair", pairParams(ticket, CLIENT), null));
      assertEquals(0, s.lmcp().manage(object().put("action", "state")).path("clients").size());
    }
  }

  @Test
  void acceptedTicketRestoresSameCredentialAcrossPortAndRestartAfterExpiry() throws Exception {
    var time = new Time();
    JsonNode ticket, original;
    try (var s = new LeximeetService(directory, time)) {
      success(rpc(s, "hello", hello(s), null));
      ticket = request(s);
      original = success(rpc(s, "pair", pairParams(ticket, CLIENT), null));
      var meta =
          s.lmcp().database.read(db -> Sql.rows(db, "SELECT value FROM lmcp_meta")).toString();
      assertFalse(meta.contains(ticket.path("invitationToken").asText()));
      assertFalse(meta.contains(original.path("pairingCredential").path("pairingToken").asText()));
      error(
          "INVITATION_ALREADY_ACCEPTED",
          rpc(
              s,
              "requestConnection",
              object()
                  .put("action", "cancel")
                  .put("clientInstanceId", CLIENT)
                  .put("invitationId", ticket.path("invitationId").asText()),
              null));
    }
    time.advance(300);
    try (var s = new LeximeetService(directory, time)) {
      String nextPort = uuid();
      success(rpc(s, "hello", hello(s).put("connectionId", nextPort), null, nextPort, ORIGIN));
      var restored = success(rpc(s, "pair", pairParams(ticket, CLIENT), null, nextPort, ORIGIN));
      assertEquals(original.path("pairingCredential"), restored.path("pairingCredential"));
      assertNotEquals(
          original.path("authorization").path("sessionId"),
          restored.path("authorization").path("sessionId"));
      success(rpc(s, "getWorkspace", object(), restored.path("authorization"), nextPort, ORIGIN));
      error("UNAUTHORIZED", rpc(s, "getWorkspace", object(), original.path("authorization")));
      assertEquals(1, s.lmcp().manage(object().put("action", "state")).path("clients").size());
    }
  }

  @Test
  void desktopDisconnectPersistsAndControlCredentialCannotReadBusinessData() throws Exception {
    var time = new Time();
    JsonNode paired, ticket;
    try (var s = new LeximeetService(directory, time)) {
      success(rpc(s, "hello", hello(s), null));
      ticket = request(s);
      paired = success(rpc(s, "pair", pairParams(ticket, CLIENT), null));
      var auth = paired.path("authorization");
      var capture = capture(custom(uuid()), uuid(), uuid());
      success(rpc(s, "recordEncounter", capture, auth));
      var after =
          s.lmcp().manage(object().put("action", "disconnect").put("clientInstanceId", CLIENT));
      assertEquals("disconnected", after.path("clients").get(0).path("connectionState").asText());
      assertEquals(
          "disconnected",
          status(s, paired.path("pairingCredential"), null).path("connectionState").asText());
      error("UNAUTHORIZED", rpc(s, "getWorkspace", object(), auth));
      error("CONNECTION_ENDED", rpc(s, "resumeSession", paired.path("pairingCredential"), null));
      error("CONNECTION_ENDED", rpc(s, "pair", pairParams(ticket, CLIENT), null));
      assertEquals(1, s.snapshot().path("encounters").size());
    }
    try (var s = new LeximeetService(directory, time)) {
      success(rpc(s, "hello", hello(s), null));
      assertEquals(
          "disconnected",
          status(s, paired.path("pairingCredential"), null).path("connectionState").asText());
      var nextTicket = request(s);
      var next = success(rpc(s, "pair", pairParams(nextTicket, CLIENT), null));
      assertNotEquals(paired.path("pairingCredential"), next.path("pairingCredential"));
      error("PAIRING_REVOKED", rpc(s, "resumeSession", paired.path("pairingCredential"), null));
      assertEquals(1, s.snapshot().path("encounters").size());
    }
  }

  @Test
  void closedPortIsTemporaryAndRevocationRemainsDistinct() throws Exception {
    try (var s = new LeximeetService(directory, new Time())) {
      var paired = pair(s);
      s.lmcp().manage(object().put("action", "connectionClosed").put("connectionId", CONNECTION));
      success(rpc(s, "hello", hello(s), null));
      assertEquals(
          "connected",
          status(s, paired.path("pairingCredential"), null).path("connectionState").asText());
      var resumed = success(rpc(s, "resumeSession", paired.path("pairingCredential"), null));
      s.lmcp().manage(object().put("action", "revoke").put("clientInstanceId", CLIENT));
      assertEquals(
          "revoked",
          status(s, paired.path("pairingCredential"), null).path("connectionState").asText());
      error("PAIRING_REVOKED", rpc(s, "resumeSession", paired.path("pairingCredential"), null));
      error("UNAUTHORIZED", rpc(s, "getWorkspace", object(), resumed.path("authorization")));
      var other = paired.path("pairingCredential").deepCopy();
      ((com.fasterxml.jackson.databind.node.ObjectNode) other)
          .put("pairingToken", "wrong".repeat(10));
      error(
          "PAIRING_REVOKED",
          rpc(
              s,
              "getConnectionStatus",
              object().put("clientInstanceId", CLIENT).set("pairingCredential", other),
              null));
    }
  }

  @Test
  void livePollingKeepsDiscoveryButActualIdleExpires() throws Exception {
    var time = new Time();
    try (var s = new LeximeetService(directory, time)) {
      success(rpc(s, "hello", hello(s), null));
      for (int i = 0; i < 4; i++) {
        time.advance(30);
        status(s, null, null);
      }
      assertEquals(
          1, s.lmcp().manage(object().put("action", "state")).path("discoveredClients").size());
      time.advance(60);
      assertEquals(
          0, s.lmcp().manage(object().put("action", "state")).path("discoveredClients").size());
      error(
          "STALE_CONNECTION",
          rpc(s, "getConnectionStatus", object().put("clientInstanceId", CLIENT), null));
    }
  }

  @Test
  void statusDoesNotRefreshSessionsOrBusinessRevisions() throws Exception {
    try (var s = new LeximeetService(directory, new Time())) {
      var paired = pair(s);
      var before = s.lmcp().database.read(db -> Sql.rows(db, "SELECT * FROM lmcp_sessions"));
      var revision = s.lmcp().database.read(db -> LmcpService.meta(db, "revision"));
      for (int i = 0; i < 5; i++)
        assertEquals(
            "connected",
            status(s, paired.path("pairingCredential"), null).path("connectionState").asText());
      assertEquals(
          before, s.lmcp().database.read(db -> Sql.rows(db, "SELECT * FROM lmcp_sessions")));
      assertEquals(revision, s.lmcp().database.read(db -> LmcpService.meta(db, "revision")));
    }
  }
}
