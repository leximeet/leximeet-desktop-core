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
    ObjectNode message = Json.MAPPER.createObjectNode().put("jsonrpc", "2.0").put("id", 1);
    message.put("method", method);
    if (params != null) message.set("params", params);
    return Json.MAPPER.createObjectNode().put("clientId", "cursor").set("message", message);
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
      assertEquals(McpCatalog.PROTOCOL_2025, init.path("protocolVersion").asText());
      assertEquals("leximeet", init.path("serverInfo").path("name").asText());
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
  void rejectsMalformedRpcAndDoesNotEchoSecrets() throws Exception {
    try (var service = service()) {
      JsonNode bad =
          service
              .mcpRpc(
                  Json.MAPPER
                      .createObjectNode()
                      .put("clientId", "cursor")
                      .set("message", Json.MAPPER.createObjectNode().put("method", "tools/list")))
              .path("message");
      assertEquals("INVALID_REQUEST", bad.path("error").path("data").path("code").asText());
      assertFalse(bad.toString().contains("Bearer"));
      assertFalse(service.snapshot().toString().contains("record_review"));
    }
  }
}
