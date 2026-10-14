package app.leximeet.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 官方 MCP JSON-RPC 表面：initialize、tools/list、tools/call。 业务路由与授权在本类串行执行，不把 Core 令牌交给助手客户端。
 */
final class McpService {
  static final String JSONRPC = "2.0";
  private final Database database;
  private final DesktopWorkspace desktop;
  private final McpRouter router;
  private final Map<String, RateWindow> rates = new ConcurrentHashMap<>();

  McpService(Database database, DesktopWorkspace desktop) {
    this.database = database;
    this.desktop = desktop;
    this.router = new McpRouter(desktop);
  }

  /** 网关投递：{ clientId, message }。返回 { message }，JSON-RPC 错误也放在 message 内。 */
  synchronized ObjectNode handle(JsonNode envelope) {
    ObjectNode request = Json.object(envelope);
    Json.fields(request, "clientId", "message");
    String clientId = Json.text(request, "clientId", 40, false);
    if (clientId.isEmpty()) clientId = "unknown";
    if (!clientId.matches("[A-Za-z0-9_-]{1,40}")) throw invalidArgument("clientId 格式无效");
    JsonNode message = request.get("message");
    if (message == null || !message.isObject()) throw invalidArgument("message 必须是 JSON-RPC 对象");
    ObjectNode rpc = Json.object(message);
    if (!JSONRPC.equals(rpc.path("jsonrpc").asText()))
      return wrap(error(rpc, -32600, "INVALID_REQUEST", "jsonrpc 必须是 2.0"));
    if (!rpc.has("id") || rpc.get("id").isNull()) {
      if ("notifications/initialized".equals(rpc.path("method").asText())
          || "notifications/cancelled".equals(rpc.path("method").asText()))
        return Json.MAPPER.createObjectNode().putNull("message");
      return wrap(error(rpc, -32600, "INVALID_REQUEST", "通知以外的请求必须带 id"));
    }
    if (!allow(clientId)) return wrap(error(rpc, -32000, "RATE_LIMITED", "请求过于频繁，请稍后再试"));
    return wrap(dispatch(clientId, rpc));
  }

  synchronized ObjectNode manage(JsonNode input) throws Exception {
    Json.fields(input, "action");
    Json.choice(input, "action", "state", "state");
    ObjectNode out =
        database.read(
            db ->
                Json.MAPPER
                    .createObjectNode()
                    .put("writeEnabled", McpPermissions.writeEnabled(db))
                    .put("server", McpCatalog.SERVER_NAME)
                    .put("version", McpCatalog.SERVER_VERSION));
    out.set("tools", McpCatalog.toolsList().path("tools"));
    return out;
  }

  private ObjectNode dispatch(String clientId, ObjectNode rpc) {
    String method = rpc.path("method").asText("");
    JsonNode params = rpc.path("params");
    try {
      return switch (method) {
        case "initialize" -> ok(rpc, initialize(params));
        case "ping" -> ok(rpc, Json.MAPPER.createObjectNode());
        case "tools/list" -> ok(rpc, McpCatalog.toolsList());
        case "tools/call" -> ok(rpc, call(clientId, params));
        default -> error(rpc, -32601, "METHOD_NOT_FOUND", "不支持的 MCP 方法：" + method);
      };
    } catch (ApiException error) {
      if ("WRITE_NOT_AUTHORIZED".equals(error.code()) || "READ_ONLY".equals(error.code()))
        return ok(rpc, toolError(error.code(), error.getMessage()));
      if (error.status() < 500) return ok(rpc, toolError(error.code(), error.getMessage()));
      return error(rpc, -32000, error.code(), error.getMessage());
    } catch (Exception error) {
      System.err.println("[mcp] " + error.getClass().getSimpleName());
      return error(rpc, -32603, "INTERNAL_ERROR", "本机核心未完成操作");
    }
  }

  private ObjectNode initialize(JsonNode params) {
    String requested = params.path("protocolVersion").asText("");
    return McpCatalog.initializeResult(McpCatalog.negotiateProtocol(requested));
  }

  private ObjectNode call(String clientId, JsonNode params) throws Exception {
    if (params == null || !params.isObject()) throw invalidArgument("tools/call 需要 params 对象");
    String name = params.path("name").asText("");
    JsonNode arguments = params.path("arguments");
    if (arguments.isMissingNode() || arguments.isNull()) arguments = Json.MAPPER.createObjectNode();
    if (!arguments.isObject()) throw invalidArgument("arguments 必须是对象");
    ObjectNode result =
        switch (name) {
          case "leximeet_help" -> McpCatalog.help();
          case "leximeet_describe" ->
              McpCatalog.describe(Json.text(Json.object(arguments), "group", 40, true));
          case "leximeet_query" -> query(arguments);
          case "leximeet_write" -> write(arguments);
          default -> throw invalidArgument("未知工具：" + name);
        };
    return toolOk(bound(result));
  }

  private ObjectNode query(JsonNode arguments) throws Exception {
    Json.fields(arguments, "action", "args", "detail");
    String action = Json.text(arguments, "action", 40, true);
    JsonNode args = McpCatalog.parseArgs(arguments.get("args"));
    boolean detail = arguments.path("detail").asBoolean(false);
    if (arguments.has("detail") && !arguments.get("detail").isBoolean())
      throw invalidArgument("detail 必须为布尔值");
    return database.read(db -> router.query(db, action, args, detail));
  }

  private ObjectNode write(JsonNode arguments) throws Exception {
    Json.fields(arguments, "action", "args");
    String action = Json.text(arguments, "action", 40, true);
    if (!McpCatalog.WRITE_ACTIONS.contains(action))
      throw new ApiException(403, "READ_ONLY", "不允许的写入动作");
    JsonNode args = McpCatalog.parseArgs(arguments.get("args"));
    database.read(
        db -> {
          McpPermissions.requireWrite(db);
          return null;
        });
    return router.write(action, args);
  }

  private static ObjectNode bound(ObjectNode result) throws Exception {
    String text = Json.MAPPER.writeValueAsString(result);
    if (text.length() <= McpCatalog.MAX_RESULT_CHARS) return result;
    ObjectNode clipped =
        Json.MAPPER
            .createObjectNode()
            .put("truncated", true)
            .put("message", "结果超过大小上限，请缩小 limit 或关闭 detail");
    clipped.set("preview", Json.MAPPER.readTree(text.substring(0, Math.min(800, text.length()))));
    return clipped;
  }

  private static ObjectNode toolOk(ObjectNode result) throws Exception {
    ObjectNode content = Json.MAPPER.createObjectNode().put("type", "text");
    content.put("text", Json.MAPPER.writeValueAsString(result));
    ObjectNode out = Json.MAPPER.createObjectNode().put("isError", false);
    out.putArray("content").add(content);
    out.set("structuredContent", result);
    return out;
  }

  static ObjectNode toolError(String code, String message) {
    ObjectNode payload = Json.MAPPER.createObjectNode().put("code", code).put("message", message);
    ObjectNode content = Json.MAPPER.createObjectNode().put("type", "text");
    try {
      content.put("text", Json.MAPPER.writeValueAsString(payload));
    } catch (Exception ignored) {
      content.put("text", message);
    }
    ObjectNode out = Json.MAPPER.createObjectNode().put("isError", true);
    out.putArray("content").add(content);
    out.set("structuredContent", payload);
    return out;
  }

  private static ObjectNode wrap(ObjectNode message) {
    ObjectNode out = Json.MAPPER.createObjectNode();
    if (message == null) out.putNull("message");
    else out.set("message", message);
    return out;
  }

  private static ObjectNode ok(ObjectNode rpc, ObjectNode result) {
    ObjectNode out = Json.MAPPER.createObjectNode().put("jsonrpc", JSONRPC);
    out.set("id", rpc.get("id"));
    out.set("result", result);
    return out;
  }

  private static ObjectNode error(ObjectNode rpc, int code, String name, String message) {
    ObjectNode error =
        Json.MAPPER.createObjectNode().put("code", code).put("message", name + ": " + message);
    error.set("data", Json.MAPPER.createObjectNode().put("code", name));
    ObjectNode out = Json.MAPPER.createObjectNode().put("jsonrpc", JSONRPC);
    if (rpc != null && rpc.has("id")) out.set("id", rpc.get("id"));
    else out.putNull("id");
    out.set("error", error);
    return out;
  }

  static ApiException invalidArgument(String message) {
    return new ApiException(400, "INVALID_ARGUMENT", message);
  }

  private boolean allow(String clientId) {
    long now = System.nanoTime();
    RateWindow window = rates.computeIfAbsent(clientId, key -> new RateWindow());
    if (now - window.started >= 60_000_000_000L) {
      window.started = now;
      window.count = 0;
    }
    return ++window.count <= 60;
  }

  private static final class RateWindow {
    long started = System.nanoTime();
    int count;
  }
}
