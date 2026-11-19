package app.leximeet.core;

import static app.leximeet.core.LmcpServiceTest.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// 走真实 Core 和 SQLite，验证来源与权限不能由插件自报扩大。
class LmcpIdeaReadingTest {
  @TempDir Path directory;

  @Test
  void ideaHandshakeAndPairingOnlyAllowReading() throws Exception {
    try (var service = new LeximeetService(directory, DesktopWorkspaceTest.CLOCK)) {
      String origin = LmcpService.IDEA_ORIGIN;
      var hello = hello(service).put("clientKind", "ide");
      error("CAPABILITY_UNAVAILABLE", rpc(service, "hello", hello, null, CONNECTION, origin));
      hello.putArray("requiredCapabilities").add("lmcp.connection/1").add("desktop.reading/1");
      var discovered = success(rpc(service, "hello", hello, null, CONNECTION, origin));
      assertFalse(discovered.path("methods").toString().contains("recordEncounter"));
      assertFalse(discovered.has("workspaceId"));
      var status =
          success(
              rpc(
                  service,
                  "requestConnection",
                  object().put("clientInstanceId", CLIENT).put("action", "request"),
                  null,
                  CONNECTION,
                  origin));
      var ticket = status.path("invitation");
      assertEquals(
          0, service.lmcp().manage(object().put("action", "state")).path("clients").size());
      var paired =
          success(rpc(service, "pair", pairParams(ticket, CLIENT), null, CONNECTION, origin));
      assertEquals("[\"library:read\"]", paired.path("scopes").toString());
      var auth = paired.path("authorization");
      assertEquals(
          paired.path("owner"),
          success(rpc(service, "getWorkspace", object(), auth, CONNECTION, origin)).path("owner"));
      var params = object().put("kind", "tokens");
      params.putArray("tokens").add("alpha").add("beta");
      var matches = success(rpc(service, "matchWords", params, auth, CONNECTION, origin));
      assertEquals(2, matches.path("results").size());
      error(
          "FORBIDDEN",
          rpc(
              service,
              "recordEncounter",
              capture(custom(uuid()), uuid(), uuid()),
              auth,
              CONNECTION,
              origin));
      error("FORBIDDEN", rpc(service, "listNotebooks", object(), auth, CONNECTION, origin));
      error("PAIRING_REVOKED", rpc(service, "matchWords", params, auth, CONNECTION, ORIGIN));
      success(rpc(service, "disconnect", object(), auth, CONNECTION, origin));
      var control = object().put("clientInstanceId", CLIENT);
      control.set("pairingCredential", paired.path("pairingCredential"));
      JsonNode disconnected =
          success(rpc(service, "getConnectionStatus", control, null, CONNECTION, origin));
      assertEquals("disconnected", disconnected.path("connectionState").asText());
      error("UNAUTHORIZED", rpc(service, "matchWords", params, auth, CONNECTION, origin));
    }
  }
}
