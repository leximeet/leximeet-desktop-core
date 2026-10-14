package app.leximeet.core;

import static app.leximeet.core.DesktopWorkspaceTest.CLOCK;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// 路由、权限、参数校验与只读档拒绝写入；真实 SQLite，不用替身。
class McpServiceTest {
  @TempDir Path directory;

  private LeximeetService service() throws Exception {
    return new LeximeetService(directory.resolve("mcp"), CLOCK);
  }

  private static ObjectNode rpc(String method, JsonNode params) {
    return rpc("cursor", method, params, true);
  }

  private static ObjectNode rpc(String clientId, String method, JsonNode params, boolean withId) {
    ObjectNode message = Json.MAPPER.createObjectNode().put("jsonrpc", "2.0").put("method", method);
    if (withId) message.put("id", 1);
    if (params != null) message.set("params", params);
    return Json.MAPPER.createObjectNode().put("clientId", clientId).set("message", message);
  }

  private static ObjectNode notify(String method) {
    return rpc("cursor", method, Json.MAPPER.createObjectNode(), false);
  }

  private static ObjectNode call(String name, ObjectNode arguments) {
    ObjectNode params = Json.MAPPER.createObjectNode().put("name", name);
    params.set("arguments", arguments);
    return rpc("tools/call", params);
  }

  private static JsonNode result(LeximeetService service, ObjectNode envelope) {
    JsonNode message = service.mcpRpc(envelope).path("message");
    assertFalse(message.has("error"), () -> message.toString());
    return message.path("result");
  }

  private static JsonNode structured(LeximeetService service, ObjectNode envelope) {
    JsonNode body = result(service, envelope);
    assertFalse(body.path("isError").asBoolean(), () -> body.toString());
    return body.path("structuredContent");
  }

  @Test
  void catalogListsFourToolsAndWriteIsNotReadOnly() {
    JsonNode tools = McpCatalog.toolsList().path("tools");
    assertEquals(4, tools.size());
    assertEquals("leximeet_help", tools.get(0).path("name").asText());
    assertEquals("leximeet_describe", tools.get(1).path("name").asText());
    assertEquals("leximeet_query", tools.get(2).path("name").asText());
    assertEquals("leximeet_write", tools.get(3).path("name").asText());
    assertTrue(tools.get(2).path("annotations").path("readOnlyHint").asBoolean());
    assertFalse(tools.get(3).path("annotations").path("readOnlyHint").asBoolean());
    assertFalse(McpCatalog.snapshot().toString().contains("record_review"));
  }

  @Test
  void initializeAndToolsListMatchSnapshotShape() throws Exception {
    try (var service = service()) {
      ObjectNode initParams =
          Json.MAPPER.createObjectNode().put("protocolVersion", McpCatalog.PROTOCOL_2025);
      initParams.set("capabilities", Json.MAPPER.createObjectNode());
      JsonNode init = result(service, rpc("initialize", initParams));
      assertEquals(McpCatalog.PROTOCOL_2025_06, init.path("protocolVersion").asText());
      assertEquals("leximeet", init.path("serverInfo").path("name").asText());
      assertEquals("词遇", init.path("serverInfo").path("title").asText());
      assertEquals("1.0.1", init.path("serverInfo").path("version").asText());
      JsonNode listed = result(service, rpc("tools/list", Json.MAPPER.createObjectNode()));
      assertEquals(McpCatalog.toolsList().path("tools"), listed.path("tools"));
    }
  }

  @Test
  void helpAndDescribeRouteByGroup() throws Exception {
    try (var service = service()) {
      JsonNode help = structured(service, call("leximeet_help", Json.MAPPER.createObjectNode()));
      assertEquals(4, help.path("groups").size());
      JsonNode dict =
          structured(
              service,
              call("leximeet_describe", Json.MAPPER.createObjectNode().put("group", "dictionary")));
      assertTrue(dict.toString().contains("lookup_word"));
      ApiException unknown =
          assertThrows(ApiException.class, () -> McpCatalog.describe("windsurf"));
      assertEquals("INVALID_ARGUMENT", unknown.code());
    }
  }

  @Test
  void queryLooksUpCollectedWordAndPaginatesNotebooks() throws Exception {
    try (var service = service()) {
      CoreTestData.collect(
          service, Json.MAPPER.createObjectNode().put("word", "alpha").put("note", "笔记"));
      JsonNode card =
          structured(
              service,
              call(
                  "leximeet_query",
                  Json.MAPPER
                      .createObjectNode()
                      .put("action", "lookup_word")
                      .set("args", Json.MAPPER.createObjectNode().put("q", "alpha"))));
      assertEquals("alpha", card.path("word").asText());
      assertTrue(card.path("collected").asBoolean());
      assertFalse(card.has("entry"));
      JsonNode search =
          structured(
              service,
              call(
                  "leximeet_query",
                  Json.MAPPER
                      .createObjectNode()
                      .put("action", "search_words")
                      .set("args", Json.MAPPER.createObjectNode().put("q", "alp"))));
      assertEquals(1, search.path("words").size());
      JsonNode status =
          structured(
              service,
              call(
                  "leximeet_query",
                  Json.MAPPER
                      .createObjectNode()
                      .put("action", "get_word_status")
                      .set("args", Json.MAPPER.createObjectNode().put("word", "alpha"))));
      assertTrue(status.path("collected").asBoolean());
      JsonNode books =
          structured(
              service,
              call(
                  "leximeet_query",
                  Json.MAPPER
                      .createObjectNode()
                      .put("action", "list_notebooks")
                      .set("args", Json.MAPPER.createObjectNode().put("limit", 2))));
      assertTrue(books.path("notebooks").isArray());
      JsonNode stats =
          structured(
              service,
              call(
                  "leximeet_query",
                  Json.MAPPER.createObjectNode().put("action", "get_learning_stats")));
      assertTrue(stats.path("manualWords").asLong() >= 1);
      JsonNode examples =
          structured(
              service,
              call(
                  "leximeet_query",
                  Json.MAPPER
                      .createObjectNode()
                      .put("action", "get_examples")
                      .set("args", Json.MAPPER.createObjectNode().put("word", "alpha"))));
      assertEquals("alpha", examples.path("word").asText());
      assertTrue(examples.path("examples").isArray());
      JsonNode encounters =
          structured(
              service,
              call(
                  "leximeet_query",
                  Json.MAPPER
                      .createObjectNode()
                      .put("action", "list_encounters")
                      .set("args", Json.MAPPER.createObjectNode().put("q", "alpha"))));
      assertTrue(encounters.path("total").asLong() >= 0);
      JsonNode due =
          structured(
              service,
              call(
                  "leximeet_query",
                  Json.MAPPER
                      .createObjectNode()
                      .put("action", "list_due_reviews")
                      .set("args", Json.MAPPER.createObjectNode().put("limit", 5))));
      assertTrue(due.path("reviews").isArray());
    }
  }

  @Test
  void writeDefaultsOffAndCanBeGrantedThenRevoked() throws Exception {
    try (var service = service()) {
      assertFalse(
          service
              .mcpManage(Json.MAPPER.createObjectNode().put("action", "state"))
              .path("writeEnabled")
              .asBoolean());
      JsonNode denied =
          result(
              service,
              call(
                  "leximeet_write",
                  Json.MAPPER
                      .createObjectNode()
                      .put("action", "add_to_vocab")
                      .set("args", Json.MAPPER.createObjectNode().put("word", "gamma"))));
      assertTrue(denied.path("isError").asBoolean());
      assertEquals("WRITE_NOT_AUTHORIZED", denied.path("structuredContent").path("code").asText());
      service.mcpManage(
          Json.MAPPER.createObjectNode().put("action", "setWriteEnabled").put("enabled", true));
      JsonNode added =
          structured(
              service,
              call(
                  "leximeet_write",
                  Json.MAPPER
                      .createObjectNode()
                      .put("action", "add_to_vocab")
                      .set(
                          "args",
                          Json.MAPPER
                              .createObjectNode()
                              .put("word", "gamma")
                              .put("mutationId", "mut-gamma-1"))));
      assertEquals("gamma", added.path("word").asText());
      JsonNode replay =
          structured(
              service,
              call(
                  "leximeet_write",
                  Json.MAPPER
                      .createObjectNode()
                      .put("action", "add_to_vocab")
                      .set(
                          "args",
                          Json.MAPPER
                              .createObjectNode()
                              .put("word", "gamma")
                              .put("mutationId", "mut-gamma-1"))));
      assertEquals(added, replay);
      service.mcpManage(
          Json.MAPPER.createObjectNode().put("action", "setWriteEnabled").put("enabled", false));
      JsonNode again =
          result(
              service,
              call(
                  "leximeet_write",
                  Json.MAPPER
                      .createObjectNode()
                      .put("action", "add_to_vocab")
                      .set("args", Json.MAPPER.createObjectNode().put("word", "delta"))));
      assertEquals("WRITE_NOT_AUTHORIZED", again.path("structuredContent").path("code").asText());
    }
  }

  @Test
  void unknownWriteAndReviewActionsAreRejected() throws Exception {
    try (var service = service()) {
      service.mcpManage(
          Json.MAPPER.createObjectNode().put("action", "setWriteEnabled").put("enabled", true));
      JsonNode review =
          result(
              service,
              call(
                  "leximeet_write",
                  Json.MAPPER
                      .createObjectNode()
                      .put("action", "record_review")
                      .set("args", Json.MAPPER.createObjectNode().put("word", "alpha"))));
      assertTrue(review.path("isError").asBoolean());
      assertEquals("READ_ONLY", review.path("structuredContent").path("code").asText());
      JsonNode missing =
          result(
              service,
              call(
                  "leximeet_query",
                  Json.MAPPER
                      .createObjectNode()
                      .put("action", "lookup_word")
                      .set("args", Json.MAPPER.createObjectNode())));
      assertEquals("INVALID_ARGUMENT", missing.path("structuredContent").path("code").asText());
    }
  }

  @Test
  void notificationsWithoutIdAreSilentlyIgnored() throws Exception {
    try (var service = service()) {
      for (String method :
          new String[] {
            "notifications/roots/list_changed",
            "notifications/progress",
            "notifications/initialized",
            "tools/list"
          }) {
        JsonNode body = service.mcpRpc(notify(method));
        assertTrue(body.path("message").isNull(), () -> method + " " + body);
        assertFalse(body.toString().contains("\"id\":null"), () -> method + " " + body);
      }
      assertFalse(service.snapshot().toString().contains("record_review"));
    }
  }

  @Test
  void negotiatesProtocolVersionsAndOmitsNewerFields() throws Exception {
    assertEquals(McpCatalog.PROTOCOL_2025_06, McpCatalog.negotiateProtocol(""));
    assertEquals(McpCatalog.PROTOCOL_2025_06, McpCatalog.negotiateProtocol("2025-06-18"));
    assertEquals(McpCatalog.PROTOCOL_2025_03, McpCatalog.negotiateProtocol("2025-03-26"));
    assertEquals(McpCatalog.PROTOCOL_2024, McpCatalog.negotiateProtocol("2024-11-05"));
    assertEquals(McpCatalog.PROTOCOL_2025_06, McpCatalog.negotiateProtocol("1999-01-01"));
    JsonNode latest = McpCatalog.initializeResult(McpCatalog.PROTOCOL_2025_06);
    assertTrue(latest.path("serverInfo").has("title"));
    JsonNode march = McpCatalog.initializeResult(McpCatalog.PROTOCOL_2025_03);
    assertFalse(march.path("serverInfo").has("title"));
    JsonNode first = McpCatalog.initializeResult(McpCatalog.PROTOCOL_2024);
    assertFalse(first.path("serverInfo").has("title"));
    JsonNode latestTool = McpCatalog.toolsList(McpCatalog.PROTOCOL_2025_06).path("tools").get(0);
    assertTrue(latestTool.has("title"));
    assertTrue(latestTool.has("annotations"));
    JsonNode marchTool = McpCatalog.toolsList(McpCatalog.PROTOCOL_2025_03).path("tools").get(0);
    assertFalse(marchTool.has("title"));
    assertTrue(marchTool.has("annotations"));
    JsonNode oldTool = McpCatalog.toolsList(McpCatalog.PROTOCOL_2024).path("tools").get(0);
    assertFalse(oldTool.has("title"));
    assertFalse(oldTool.has("annotations"));
    try (var service = service()) {
      ObjectNode oldParams =
          Json.MAPPER.createObjectNode().put("protocolVersion", McpCatalog.PROTOCOL_2024);
      oldParams.set("capabilities", Json.MAPPER.createObjectNode());
      JsonNode init = result(service, rpc("old", "initialize", oldParams, true));
      assertEquals(McpCatalog.PROTOCOL_2024, init.path("protocolVersion").asText());
      assertFalse(init.path("serverInfo").has("title"));
      JsonNode listed =
          result(service, rpc("old", "tools/list", Json.MAPPER.createObjectNode(), true));
      assertFalse(listed.path("tools").get(0).has("title"));
      assertFalse(listed.path("tools").get(0).has("annotations"));
      JsonNode help =
          result(
              service,
              rpc(
                  "old",
                  "tools/call",
                  call("leximeet_help", Json.MAPPER.createObjectNode())
                      .path("message")
                      .path("params"),
                  true));
      assertFalse(help.has("structuredContent"));
      assertTrue(help.path("content").get(0).path("text").asText().contains("dictionary"));
    }
  }

  @Test
  void unknownToolReturnsJsonRpcInvalidParams() throws Exception {
    try (var service = service()) {
      JsonNode message =
          service.mcpRpc(call("leximeet_nope", Json.MAPPER.createObjectNode())).path("message");
      assertEquals(-32602, message.path("error").path("code").asInt(), () -> message.toString());
      assertEquals("INVALID_PARAMS", message.path("error").path("data").path("code").asText());
      assertFalse(message.has("result"));
    }
  }

  @Test
  void rateLimitCountsOnlyToolsCall() throws Exception {
    try (var service = service()) {
      ObjectNode initParams =
          Json.MAPPER.createObjectNode().put("protocolVersion", McpCatalog.PROTOCOL_2025_06);
      initParams.set("capabilities", Json.MAPPER.createObjectNode());
      for (int i = 0; i < 80; i++) {
        JsonNode listed =
            service.mcpRpc(rpc("tools/list", Json.MAPPER.createObjectNode())).path("message");
        assertFalse(listed.has("error"), () -> listed.toString());
      }
      JsonNode help = result(service, rpc("initialize", initParams));
      assertEquals(McpCatalog.PROTOCOL_2025_06, help.path("protocolVersion").asText());
      JsonNode last = null;
      for (int i = 0; i < 61; i++) {
        last =
            service.mcpRpc(call("leximeet_help", Json.MAPPER.createObjectNode())).path("message");
      }
      assertEquals("RATE_LIMITED", last.path("error").path("data").path("code").asText());
    }
  }

  @Test
  void searchPaginatesMergedSourcesAndExamplesReadObjectText() throws Exception {
    var fixture = new DesktopWorkspaceTest();
    try (var service = service()) {
      fixture.mount(service, fixture.index(directory.resolve("mcp")));
      CoreTestData.collect(service, Json.MAPPER.createObjectNode().put("word", "alpine"));
      CoreTestData.collect(service, Json.MAPPER.createObjectNode().put("word", "alphabet"));
      JsonNode first =
          structured(
              service,
              call(
                  "leximeet_query",
                  Json.MAPPER
                      .createObjectNode()
                      .put("action", "search_words")
                      .set(
                          "args", Json.MAPPER.createObjectNode().put("q", "alp").put("limit", 1))));
      assertEquals(1, first.path("words").size());
      assertEquals("alpha", first.path("words").get(0).path("word").asText());
      assertTrue(first.has("nextCursor"));
      JsonNode second =
          structured(
              service,
              call(
                  "leximeet_query",
                  Json.MAPPER
                      .createObjectNode()
                      .put("action", "search_words")
                      .set(
                          "args",
                          Json.MAPPER
                              .createObjectNode()
                              .put("q", "alp")
                              .put("limit", 1)
                              .put("cursor", first.path("nextCursor").asText()))));
      assertEquals(1, second.path("words").size());
      assertEquals("alphabet", second.path("words").get(0).path("word").asText());
      JsonNode examples =
          structured(
              service,
              call(
                  "leximeet_query",
                  Json.MAPPER
                      .createObjectNode()
                      .put("action", "get_examples")
                      .set("args", Json.MAPPER.createObjectNode().put("word", "alpha"))));
      assertTrue(examples.toString().contains("alpha appears here"), () -> examples.toString());
    }
  }

  @Test
  void encountersHonorWordIdAndOversizePreviewStaysText() throws Exception {
    try (var service = service()) {
      service
          .desktop()
          .command(
              Json.MAPPER
                  .createObjectNode()
                  .put("action", "capture")
                  .put("word", "alpha")
                  .put("context", "alpha appears in this first sentence.")
                  .put("sourceTitle", "测试")
                  .put("sourceUrl", ""));
      service
          .desktop()
          .command(
              Json.MAPPER
                  .createObjectNode()
                  .put("action", "capture")
                  .put("word", "gamma")
                  .put("context", "gamma appears in this second sentence.")
                  .put("sourceTitle", "测试")
                  .put("sourceUrl", ""));
      String wordId =
          structured(
                  service,
                  call(
                      "leximeet_query",
                      Json.MAPPER
                          .createObjectNode()
                          .put("action", "lookup_word")
                          .set("args", Json.MAPPER.createObjectNode().put("word", "alpha"))))
              .path("wordId")
              .asText();
      JsonNode filtered =
          structured(
              service,
              call(
                  "leximeet_query",
                  Json.MAPPER
                      .createObjectNode()
                      .put("action", "list_encounters")
                      .set("args", Json.MAPPER.createObjectNode().put("wordId", wordId))));
      assertEquals(1, filtered.path("total").asLong());
      assertEquals(1, filtered.path("encounters").size());
      assertEquals("alpha", filtered.path("encounters").get(0).path("word").asText());
      CoreTestData.collect(
          service,
          Json.MAPPER.createObjectNode().put("word", "omega").put("note", "n".repeat(15000)));
      JsonNode clipped =
          structured(
              service,
              call(
                  "leximeet_query",
                  Json.MAPPER
                      .createObjectNode()
                      .put("action", "lookup_word")
                      .put("detail", true)
                      .set("args", Json.MAPPER.createObjectNode().put("word", "omega"))));
      assertTrue(clipped.path("truncated").asBoolean(), () -> clipped.toString());
      assertTrue(clipped.path("preview").isTextual(), () -> clipped.toString());
    }
  }

  @Test
  void oversizedListShrinksLimitAndKeepsNextCursor() throws Exception {
    try (var service = service()) {
      for (int i = 0; i < 50; i++) {
        String word = "item" + String.format("%02d", i);
        service
            .desktop()
            .command(
                Json.MAPPER
                    .createObjectNode()
                    .put("action", "capture")
                    .put("word", word)
                    .put("context", word + " appears in this long sentence " + "pad".repeat(80))
                    .put("sourceTitle", "测试列表超长")
                    .put("sourceUrl", ""));
      }
      JsonNode page =
          structured(
              service,
              call(
                  "leximeet_query",
                  Json.MAPPER
                      .createObjectNode()
                      .put("action", "list_encounters")
                      .set("args", Json.MAPPER.createObjectNode().put("limit", 50))));
      assertFalse(page.path("truncated").asBoolean(false), () -> page.toString());
      assertTrue(page.path("encounters").isArray());
      assertTrue(page.path("encounters").size() >= 1);
      assertTrue(page.path("encounters").size() < 50, () -> page.toString());
      assertTrue(page.has("nextCursor"), () -> page.toString());
      assertTrue(Json.MAPPER.writeValueAsString(page).length() <= McpCatalog.MAX_RESULT_CHARS);
      JsonNode next =
          structured(
              service,
              call(
                  "leximeet_query",
                  Json.MAPPER
                      .createObjectNode()
                      .put("action", "list_encounters")
                      .set(
                          "args",
                          Json.MAPPER
                              .createObjectNode()
                              .put("limit", 50)
                              .put("cursor", page.path("nextCursor").asText()))));
      assertTrue(next.path("encounters").isArray());
      if (next.path("encounters").size() > 0) {
        assertNotEquals(
            page.path("encounters").get(0).path("id").asText(),
            next.path("encounters").get(0).path("id").asText());
      }
    }
  }
}
