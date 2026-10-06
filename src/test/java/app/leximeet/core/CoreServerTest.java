package app.leximeet.core;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// 真正监听动态端口并经 HTTP 验证授权、安全边界及持久化，不以方法 mock 代替网关。
class CoreServerTest {
  @TempDir Path directory;
  private static final String TOKEN = "test_0123456789abcdefghijklmnopqrstuvwxyz";
  private CoreServer server;
  private HttpClient client;

  @BeforeEach
  void start() throws Exception {
    server =
        new CoreServer(directory.resolve("http-test"), TOKEN, Clock.systemDefaultZone(), false);
    server.start();
    client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
  }

  @AfterEach
  void stop() throws Exception {
    client.close();
    server.close();
  }

  private HttpRequest.Builder request(String path) {
    return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
        .timeout(Duration.ofSeconds(5));
  }

  private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> json(String method, String path, String body) throws Exception {
    return send(
        request(path)
            .header("Authorization", "Bearer " + TOKEN)
            .header("Content-Type", "application/json")
            .method(method, HttpRequest.BodyPublishers.ofString(body)));
  }

  @Test
  void handshakeHealthRequiresCurrentPairingToken() throws Exception {
    assertTrue(server.port() > 0);
    assertEquals(401, send(request("/health").GET()).statusCode());
    assertEquals(
        401, send(request("/health").header("Authorization", "Bearer wrong").GET()).statusCode());
    var response = send(request("/health").header("Authorization", "Bearer " + TOKEN).GET());
    assertEquals(200, response.statusCode());
    JsonNode health = Json.MAPPER.readTree(response.body());
    assertTrue(health.path("ok").asBoolean());
    assertEquals("1", health.path("protocolVersion").asText());
    assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
  }

  @Test
  void lmcpRpcStaysPrivateAndOldRouteIsRetired() throws Exception {
    var body = Json.MAPPER.createObjectNode().put("action", "state");
    assertEquals(
        401,
        send(request("/api/lmcp/manage")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())))
            .statusCode());
    assertEquals(
        403,
        send(request("/api/lmcp/manage")
                .header("Authorization", "Bearer " + TOKEN)
                .header("Origin", "chrome-extension://" + "a".repeat(32))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())))
            .statusCode());
    assertEquals(404, json("POST", "/api/connector/rpc", "{}").statusCode());
    var response = json("POST", "/api/lmcp/manage", body.toString());
    assertEquals(200, response.statusCode());
    assertTrue(Json.MAPPER.readTree(response.body()).path("discoveredClients").isArray());
    assertFalse(response.body().contains("invitationToken"));
    assertFalse(response.body().contains(TOKEN));
    assertFalse(response.body().contains(directory.toString()));
  }

  @Test
  void rejectsWebAndNullOriginsEvenWithValidToken() throws Exception {
    for (String origin :
        new String[] {"https://evil.example", "http://localhost:5173", "null", "file://"}) {
      var response =
          send(
              request("/api/snapshot")
                  .header("Authorization", "Bearer " + TOKEN)
                  .header("Origin", origin)
                  .GET());
      assertEquals(403, response.statusCode());
      assertFalse(response.headers().firstValue("Access-Control-Allow-Origin").isPresent());
    }
  }

  @Test
  void rejectsDnsRebindingHostUsingRawHttp() throws Exception {
    try (Socket socket = new Socket("127.0.0.1", server.port())) {
      socket.setSoTimeout(3000);
      socket
          .getOutputStream()
          .write(
              ("GET /health HTTP/1.1\r\nHost: attacker.example\r\nAuthorization: Bearer "
                      + TOKEN
                      + "\r\nConnection: close\r\n\r\n")
                  .getBytes(StandardCharsets.US_ASCII));
      String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(response.startsWith("HTTP/1.1 403"));
    }
  }

  @Test
  void validatesContentTypeJsonUnknownFieldsAndHttpMethods() throws Exception {
    assertEquals(
        415,
        send(request("/api/desktop/command")
                .header("Authorization", "Bearer " + TOKEN)
                .POST(HttpRequest.BodyPublishers.ofString("{}")))
            .statusCode());
    assertEquals(400, json("POST", "/api/desktop/command", "{broken").statusCode());
    assertEquals(
        400,
        json(
                "POST",
                "/api/desktop/command",
                "{\"word\":\"one\",\"word\":\"two\",\"meaning\":\"test\"}")
            .statusCode());
    assertEquals(400, json("POST", "/api/desktop/command", "{}{}").statusCode());
    assertEquals(400, json("POST", "/api/desktop/command", "[]").statusCode());
    assertEquals(
        400, json("POST", "/api/settings", "{\"dataDir\":\"/tmp/attacker\"}").statusCode());
    assertEquals(405, json("DELETE", "/api/desktop/command", "{}").statusCode());
    assertEquals(404, json("POST", "/api/unknown", "{}").statusCode());
    assertEquals(400, json("POST", "/api/desktop/command?token=secret", "{}").statusCode());
  }

  @Test
  void oversizedDeclaredBodyRejectedBeforeReadingContent() throws Exception {
    try (Socket socket = new Socket("127.0.0.1", server.port())) {
      socket.setSoTimeout(3000);
      socket
          .getOutputStream()
          .write(
              ("POST /api/desktop/command HTTP/1.1\r\nHost: 127.0.0.1:"
                      + server.port()
                      + "\r\nAuthorization: Bearer "
                      + TOKEN
                      + "\r\nContent-Type: application/json\r\nContent-Length: "
                      + (CoreServer.MAX_BODY_BYTES + 1)
                      + "\r\nConnection: close\r\n\r\n")
                  .getBytes(StandardCharsets.US_ASCII));
      // 只读取响应起始行；客户端故意不发送正文，证明上限检查发生在正文读取之前。
      var reader =
          new java.io.BufferedReader(
              new java.io.InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
      assertTrue(reader.readLine().startsWith("HTTP/1.1 413"));
    }
  }

  @Test
  void importUsesSeparateBoundAndStillRejectsOversizedDeclaredBody() throws Exception {
    try (Socket socket = new Socket("127.0.0.1", server.port())) {
      socket.setSoTimeout(3000);
      socket
          .getOutputStream()
          .write(
              ("POST /api/import HTTP/1.1\r\nHost: 127.0.0.1:"
                      + server.port()
                      + "\r\nAuthorization: Bearer "
                      + TOKEN
                      + "\r\nContent-Type: application/json\r\nContent-Length: "
                      + (CoreServer.MAX_BACKUP_BYTES + 1L)
                      + "\r\nConnection: close\r\n\r\n")
                  .getBytes(StandardCharsets.US_ASCII));
      var reader =
          new java.io.BufferedReader(
              new java.io.InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
      assertTrue(reader.readLine().startsWith("HTTP/1.1 413"));
    }
  }

  @Test
  void httpCurrentCommandsCaptureEditTrashRestoreExportAndRestartRoundTrip() throws Exception {
    String route = "/api/desktop/command";
    assertEquals(
        200,
        json(
                "POST",
                route,
                "{\"action\":\"capture\",\"word\":\"subtle\",\"note\":\"微妙的笔记\",\"context\":\"A subtle difference.\"}")
            .statusCode());
    JsonNode word = currentSnapshot().path("words").get(0);
    String id = word.path("id").asText();
    assertEquals(
        200,
        json(
                "POST",
                route,
                "{\"action\":\"saveNote\",\"wordId\":\""
                    + id
                    + "\",\"note\":\"自己的笔记\",\"bookIds\":[],\"expectedRevision\":"
                    + word.path("revision").asInt()
                    + "}")
            .statusCode());
    assertEquals(
        200, json("POST", route, "{\"action\":\"trash\",\"wordId\":\"" + id + "\"}").statusCode());
    assertTrue(currentSnapshot().path("words").get(0).path("trashed").asBoolean());
    assertEquals(
        200,
        json("POST", route, "{\"action\":\"restore\",\"wordId\":\"" + id + "\"}").statusCode());
    assertFalse(currentSnapshot().path("words").get(0).path("trashed").asBoolean());
    var backup = send(request("/api/export").header("Authorization", "Bearer " + TOKEN).GET());
    assertEquals(
        "leximeet-backup", JsonBackup.MAPPER.readTree(backup.body()).path("format").asText());
    assertEquals(200, json("POST", "/api/import", backup.body()).statusCode());
    server.close();
    server =
        new CoreServer(directory.resolve("http-test"), TOKEN, Clock.systemDefaultZone(), false);
    server.start();
    JsonNode snapshot = currentSnapshot();
    assertEquals("自己的笔记", snapshot.path("words").get(0).path("note").asText());
    assertEquals(1, snapshot.path("encounters").size());
  }

  private JsonNode currentSnapshot() throws Exception {
    return Json.MAPPER.readTree(
        send(request("/api/snapshot").header("Authorization", "Bearer " + TOKEN).GET()).body());
  }

  @Test
  void cardLayoutHasNamedRouteAndRejectsStaleOrGenericSettingWrites() throws Exception {
    String body =
        "{\"expectedRevision\":0,\"level\":\"minimal\",\"mode\":\"preset\",\"customSections\":[\"meaning\"]}";
    assertEquals(
        401,
        send(request("/api/card-layout")
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body)))
            .statusCode());
    assertEquals(405, json("POST", "/api/card-layout", body).statusCode());
    JsonNode saved = Json.MAPPER.readTree(json("PUT", "/api/card-layout", body).body());
    assertEquals("minimal", saved.path("settings").path("cardLayout").path("level").asText());
    assertEquals(1, saved.path("settings").path("cardLayout").path("revision").asInt());
    assertEquals(409, json("PUT", "/api/card-layout", body).statusCode());
    assertEquals(400, json("PATCH", "/api/settings", "{\"cardLayout\":{}}").statusCode());
  }

  @Test
  void retiredExperimentRoutesReturnNotFoundWithoutReadingPrivateBody() throws Exception {
    for (String route :
        new String[] {
          "/api/words",
          "/api/words/example",
          "/api/words/example/trash",
          "/api/words/example/restore",
          "/api/books",
          "/api/books/example/delete",
          "/api/tags",
          "/api/tags/example",
          "/api/tags/example/delete",
          "/api/today-queue",
          "/api/plan",
          "/api/plan/preview",
          "/api/reviews",
          "/api/reviews/example/undo",
          "/api/metrics",
          "/api/vocabulary/preview",
          "/api/vocabulary/import",
          "/api/vocabulary/export",
          "/api/words/example/proficiency",
          "/api/proficiency-events/example/undo"
        }) {
      assertEquals(404, json("POST", route, "{private-malformed").statusCode(), route);
    }
    JsonNode snapshot =
        Json.MAPPER.readTree(
            send(request("/api/snapshot").header("Authorization", "Bearer " + TOKEN).GET()).body());
    assertFalse(snapshot.has("plan"));
    assertFalse(snapshot.has("planHistory"));
    assertFalse(snapshot.has("reviews"));
    assertFalse(snapshot.has("tags"));
    assertFalse(snapshot.has("wordTags"));
    assertEquals(400, json("PATCH", "/api/settings", "{\"proficiencyEvents\":[]}").statusCode());
  }

  @Test
  void diagnosticsRequireAuthorizationAndOnlyCollectWhileExplicitlyEnabled() throws Exception {
    assertEquals(401, send(request("/api/diagnostics").GET()).statusCode());
    assertEquals(
        403,
        send(request("/api/diagnostics")
                .header("Authorization", "Bearer " + TOKEN)
                .header("Origin", "https://untrusted.example")
                .GET())
            .statusCode());
    var disabled =
        send(request("/api/diagnostics").header("Authorization", "Bearer " + TOKEN).GET());
    assertEquals("{\"enabled\":false}", disabled.body());
    assertEquals(
        200, json("PATCH", "/api/settings", "{\"developerMonitoringEnabled\":true}").statusCode());
    assertEquals(
        200,
        send(request("/api/snapshot").header("Authorization", "Bearer " + TOKEN).GET())
            .statusCode());
    assertEquals(
        400,
        json(
                "POST",
                "/api/desktop/command",
                "{\"action\":\"collect\",\"word\":\"private-word\",\"unknown\":\"private-note\"}")
            .statusCode());
    JsonNode before =
        Json.MAPPER.readTree(
            send(request("/api/diagnostics").header("Authorization", "Bearer " + TOKEN).GET())
                .body());
    for (int index = 0; index < 3; index++) {
      JsonNode polled =
          Json.MAPPER.readTree(
              send(request("/api/diagnostics").header("Authorization", "Bearer " + TOKEN).GET())
                  .body());
      assertEquals(before.path("requests"), polled.path("requests"));
    }
    assertEquals(2, before.path("requests").size());
    assertEquals(1, before.path("requests").get(1).path("errorCount").asInt());
    assertFalse(before.toString().contains("private-word"));
    assertFalse(before.toString().contains("private-note"));
    assertFalse(before.toString().contains(TOKEN));
    assertEquals(
        200, json("PATCH", "/api/settings", "{\"developerMonitoringEnabled\":false}").statusCode());
    assertEquals(
        "{\"enabled\":false}",
        send(request("/api/diagnostics").header("Authorization", "Bearer " + TOKEN).GET()).body());
    assertEquals(
        200, json("PATCH", "/api/settings", "{\"developerMonitoringEnabled\":true}").statusCode());
    assertTrue(
        Json.MAPPER
            .readTree(
                send(request("/api/diagnostics").header("Authorization", "Bearer " + TOKEN).GET())
                    .body())
            .path("requests")
            .isEmpty());
  }
}
