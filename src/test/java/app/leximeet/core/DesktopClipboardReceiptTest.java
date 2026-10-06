package app.leximeet.core;

import static app.leximeet.core.DesktopWorkspaceTest.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// 真实事务和回环 HTTP：关闭语境查重、响应丢失及重启后仍只执行原采集一次。
class DesktopClipboardReceiptTest {
  @TempDir Path directory;
  private static final String TOKEN = "test_0123456789abcdefghijklmnopqrstuvwxyz";

  DesktopWorkspaceTest fixture() {
    var fixture = new DesktopWorkspaceTest();
    fixture.directory = directory;
    return fixture;
  }

  ObjectNode input() {
    return Json.MAPPER
        .createObjectNode()
        .put("action", "captureClipboard")
        .put("goal", "exam:test")
        .put("wordId", ALPHA)
        .put("context", "Alpha password=clipboard_secret_sentinel.")
        .put("operationId", UUID.randomUUID().toString());
  }

  void setup(LeximeetService service, Path file) throws Exception {
    fixture().mount(service, file);
    fixture().goal(service, "exam:test");
    service.updateSettings(
        json("{\"clipboardCaptureEnabled\":true,\"captureDuplicateWindowDays\":0}"));
  }

  long count(LeximeetService service, String table) throws Exception {
    return service
        .lmcp()
        .database
        .read(db -> Sql.first(db, "SELECT COUNT(*) AS n FROM " + table))
        .path("n")
        .asLong();
  }

  @Test
  void replayWithWindowDisabledPreservesEntityRevisionAndSafeReceipt() throws Exception {
    Path file = fixture().index(directory);
    try (var service = new LeximeetService(directory, CLOCK)) {
      setup(service, file);
      var request = input();
      var first = service.desktop().command(request);
      assertTrue(first.path("clipboardAccepted").asBoolean());
      assertFalse(first.path("captureOperationReplayed").asBoolean());
      assertEquals(
          "Alpha password=xxx.",
          first.path("captureResult").path("entity").path("data").path("savedExcerpt").asText());
      var words = service.lmcp().database.read(db -> Sql.rows(db, "SELECT * FROM words"));
      var entities =
          service
              .lmcp()
              .database
              .read(db -> Sql.rows(db, "SELECT * FROM desktop_entities ORDER BY entity_id"));
      var replay = service.desktop().command(request);
      assertTrue(replay.path("captureOperationReplayed").asBoolean());
      assertEquals(first.path("captureResult"), replay.path("captureResult"));
      assertEquals(words, service.lmcp().database.read(db -> Sql.rows(db, "SELECT * FROM words")));
      assertEquals(
          entities,
          service
              .lmcp()
              .database
              .read(db -> Sql.rows(db, "SELECT * FROM desktop_entities ORDER BY entity_id")));
      assertEquals(1, count(service, "encounters"));
      assertEquals(1, count(service, "lmcp_capture_events"));
      var receipt =
          service.lmcp().database.read(db -> Sql.first(db, "SELECT * FROM lmcp_capture_events"));
      assertEquals(
          "desktopClipboard:" + request.path("operationId").asText(),
          receipt.path("event_id").asText());
      assertEquals(64, receipt.path("input_hash").asText().length());
      assertFalse(receipt.toString().contains("clipboard_secret_sentinel"));
      assertFalse(first.toString().contains("clipboard_secret_sentinel"));
      service
          .lmcp()
          .database
          .read(
              db -> {
                for (var table :
                    Sql.rows(
                        db,
                        "SELECT name FROM main.sqlite_master WHERE type='table' AND name NOT LIKE"
                            + " 'sqlite_%'")) {
                  String name = table.path("name").asText();
                  assertFalse(
                      Sql.rows(db, "SELECT * FROM main.\"" + name + "\"")
                          .toString()
                          .contains("clipboard_secret_sentinel"),
                      "敏感明文出现在 " + name);
                }
                return null;
              });
      // 用户明确再次采集使用新操作 ID；关闭七天查重时应产生新的遇见。
      service
          .desktop()
          .command(request.deepCopy().put("operationId", UUID.randomUUID().toString()));
      assertEquals(2, count(service, "encounters"));
    }
  }

  @Test
  void changedPayloadConflictsAndOriginalReceiptSurvivesPolicyGoalAndRestart() throws Exception {
    Path file = fixture().index(directory);
    var request = input();
    JsonNode result;
    try (var service = new LeximeetService(directory, CLOCK)) {
      setup(service, file);
      result = service.desktop().command(request).path("captureResult");
      var error =
          assertThrows(
              ApiException.class,
              () ->
                  service.desktop().command(request.deepCopy().put("context", "Alpha different.")));
      assertEquals("IDEMPOTENCY_KEY_REUSED", error.code());
      assertEquals(409, error.status());
      assertEquals(1, count(service, "encounters"));
      service.updateSettings(
          json("{\"clipboardCaptureEnabled\":false,\"captureSensitiveRedactionEnabled\":false}"));
      fixture().goal(service, "dictionary");
      assertEquals(result, service.desktop().command(request).path("captureResult"));
    }
    try (var service = new LeximeetService(directory, CLOCK)) {
      fixture().mount(service, file);
      assertEquals(result, service.desktop().command(request).path("captureResult"));
      assertEquals(1, count(service, "encounters"));
      assertFalse(
          service
              .desktop()
              .command(request.deepCopy().put("operationId", UUID.randomUUID().toString()))
              .path("clipboardAccepted")
              .asBoolean());
      assertEquals(2, count(service, "lmcp_capture_events"));
    }
  }

  @Test
  void withdrawnOperationIsStableAndMalformedIdsNeverWrite() throws Exception {
    Path file = fixture().index(directory);
    try (var service = new LeximeetService(directory, CLOCK)) {
      setup(service, file);
      var request = input().put("goal", "dictionary");
      assertFalse(service.desktop().command(request).path("clipboardAccepted").asBoolean());
      fixture().goal(service, "dictionary");
      assertFalse(service.desktop().command(request).path("clipboardAccepted").asBoolean());
      for (var invalid :
          new String[] {"", "abc", "../secrets", UUID.randomUUID().toString().toUpperCase()}) {
        assertThrows(
            ApiException.class,
            () -> service.desktop().command(input().put("operationId", invalid)));
      }
      assertEquals(0, count(service, "encounters"));
      assertEquals(1, count(service, "lmcp_capture_events"));
    }
  }

  @Test
  void failingInsertRollsBackReceiptAndRetrySameIdCanCommitOnce() throws Exception {
    Path file = fixture().index(directory);
    try (var service = new LeximeetService(directory, CLOCK)) {
      setup(service, file);
      var request = input();
      service
          .lmcp()
          .database
          .transaction(
              db -> {
                Sql.execute(
                    db,
                    "CREATE TRIGGER fail_clip BEFORE INSERT ON encounters BEGIN SELECT"
                        + " RAISE(ABORT,'test failure'); END");
                return null;
              });
      assertThrows(Exception.class, () -> service.desktop().command(request));
      assertEquals(0, count(service, "lmcp_capture_events"));
      assertEquals(0, count(service, "encounters"));
      service
          .lmcp()
          .database
          .transaction(
              db -> {
                Sql.execute(db, "DROP TRIGGER fail_clip");
                return null;
              });
      service.desktop().command(request);
      service.desktop().command(request);
      assertEquals(1, count(service, "encounters"));
      assertEquals(1, count(service, "lmcp_capture_events"));
    }
  }

  @Test
  void realHttpCommitWithoutReadingAckRecoversOriginalResultAfterRestart() throws Exception {
    Path file = fixture().index(directory);
    var request = input();
    JsonNode expected;
    try (var service = new LeximeetService(directory, CLOCK);
        var server = new CoreServer(service, TOKEN, false)) {
      setup(service, file);
      server.start();
      byte[] body = request.toString().getBytes(StandardCharsets.UTF_8);
      try (var socket = new Socket("127.0.0.1", server.port())) {
        socket
            .getOutputStream()
            .write(
                ("POST /api/desktop/command HTTP/1.1\r\nHost: 127.0.0.1:"
                        + server.port()
                        + "\r\nAuthorization: Bearer "
                        + TOKEN
                        + "\r\nContent-Type: application/json\r\nContent-Length: "
                        + body.length
                        + "\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().write(body);
        socket.getOutputStream().flush();
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (count(service, "lmcp_capture_events") == 0 && System.nanoTime() < deadline)
          Thread.sleep(10);
        assertEquals(1, count(service, "lmcp_capture_events"));
        // 客户端完全不读取响应；真实 HTTP 服务已提交，而操作发起端仍不知道结果。
      }
      expected =
          Json.MAPPER
              .readTree(
                  service
                      .lmcp()
                      .database
                      .read(db -> Sql.first(db, "SELECT result FROM lmcp_capture_events"))
                      .path("result")
                      .asText())
              .path("captureResult");
    }
    try (var service = new LeximeetService(directory, CLOCK);
        var server = new CoreServer(service, TOKEN, false);
        var client = HttpClient.newHttpClient()) {
      fixture().mount(service, file);
      server.start();
      var response =
          client.send(
              HttpRequest.newBuilder(
                      URI.create("http://127.0.0.1:" + server.port() + "/api/desktop/command"))
                  .timeout(Duration.ofSeconds(5))
                  .header("Authorization", "Bearer " + TOKEN)
                  .header("Content-Type", "application/json")
                  .POST(HttpRequest.BodyPublishers.ofString(request.toString()))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode());
      assertEquals(expected, Json.MAPPER.readTree(response.body()).path("captureResult"));
      assertEquals(1, count(service, "encounters"));
    }
  }
}
