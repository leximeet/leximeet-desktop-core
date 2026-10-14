package app.leximeet.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AI 助手连接的路由式工具目录。工具定义与参数说明集中在此，供协议层与契约快照共用。
 * 协议形状对齐官方 MCP（JSON-RPC 2.0、tools/list、annotations.readOnlyHint）。
 */
final class McpCatalog {
  static final String PROTOCOL_2024 = "2024-11-05";
  static final String PROTOCOL_2025_03 = "2025-03-26";
  static final String PROTOCOL_2025_06 = "2025-06-18";
  static final String PROTOCOL_2025 = PROTOCOL_2025_06;
  static final String SERVER_NAME = "leximeet";
  static final String SERVER_TITLE = "词遇";
  static final String SERVER_VERSION = "1.0.1";
  static final String INSTRUCTIONS =
      "先调用 leximeet_help 查看能力分组，再用 leximeet_describe 读取该组参数。"
          + "只读查询一律走 leximeet_query；唯一写入是 leximeet_write 的 add_to_vocab，默认关闭，需用户在桌面设置中授权。";
  static final int DEFAULT_LIMIT = 20;
  static final int MAX_LIMIT = 50;
  static final int MAX_RESULT_CHARS = 12000;
  static final Set<String> GROUPS = Set.of("dictionary", "notebooks", "reviews", "stats");
  static final Set<String> QUERY_ACTIONS =
      Set.of(
          "lookup_word",
          "search_words",
          "get_examples",
          "get_word_status",
          "list_due_reviews",
          "get_learning_stats",
          "list_notebooks",
          "list_encounters");
  static final Set<String> WRITE_ACTIONS = Set.of("add_to_vocab");
  static final String WRITE_TOOL = "leximeet_write";

  private McpCatalog() {}

  static boolean knownProtocol(String protocol) {
    return PROTOCOL_2025_06.equals(protocol)
        || PROTOCOL_2025_03.equals(protocol)
        || PROTOCOL_2024.equals(protocol);
  }

  static String negotiateProtocol(String requested) {
    if (knownProtocol(requested)) return requested;
    return PROTOCOL_2025_06;
  }

  static boolean supportsTitle(String protocol) {
    return PROTOCOL_2025_06.equals(protocol);
  }

  static boolean supportsAnnotations(String protocol) {
    return PROTOCOL_2025_06.equals(protocol) || PROTOCOL_2025_03.equals(protocol);
  }

  static boolean supportsStructuredContent(String protocol) {
    return PROTOCOL_2025_06.equals(protocol);
  }

  static ObjectNode initializeResult(String protocol) {
    ObjectNode info = Json.MAPPER.createObjectNode().put("name", SERVER_NAME);
    // title 是 2025-06-18 才有的 Implementation 字段。
    if (supportsTitle(protocol)) info.put("title", SERVER_TITLE);
    info.put("version", SERVER_VERSION);
    ObjectNode capabilities = Json.MAPPER.createObjectNode();
    capabilities.set("tools", Json.MAPPER.createObjectNode().put("listChanged", false));
    ObjectNode result =
        Json.MAPPER
            .createObjectNode()
            .put("protocolVersion", protocol)
            .put("instructions", INSTRUCTIONS);
    result.set("capabilities", capabilities);
    result.set("serverInfo", info);
    return result;
  }

  static ObjectNode help() {
    ObjectNode out = Json.MAPPER.createObjectNode();
    ArrayNode groups = out.putArray("groups");
    group(groups, "dictionary", "词典", "查词、搜词、例句与当前学习状态");
    group(groups, "notebooks", "单词本", "列出单词本与遇见记录");
    group(groups, "reviews", "复习查询", "今日到期复习，只读，不能提交判题");
    group(groups, "stats", "统计", "学习数量与今日额度");
    out.put("write", "唯一写入 add_to_vocab，默认关闭");
    return out;
  }

  private static void group(ArrayNode groups, String id, String title, String summary) {
    groups.addObject().put("id", id).put("title", title).put("summary", summary);
  }

  static ObjectNode describe(String group) {
    if (!GROUPS.contains(group)) throw McpService.invalidArgument("未知能力分组：" + group);
    ObjectNode out = Json.MAPPER.createObjectNode().put("group", group);
    ArrayNode actions = out.putArray("actions");
    for (ActionSpec spec : ACTION_SPECS.values())
      if (spec.group.equals(group)) actions.add(spec.describe());
    return out;
  }

  static ActionSpec requireQuery(String action) {
    ActionSpec spec = ACTION_SPECS.get(action);
    if (spec == null || spec.write) throw McpService.invalidArgument("未知查询动作：" + action);
    return spec;
  }

  static ActionSpec requireWrite(String action) {
    ActionSpec spec = ACTION_SPECS.get(action);
    if (spec == null || !spec.write) throw McpService.invalidArgument("未知写入动作：" + action);
    return spec;
  }

  static ObjectNode toolsList() {
    return toolsList(PROTOCOL_2025_06);
  }

  static ObjectNode toolsList(String protocol) {
    ObjectNode out = Json.MAPPER.createObjectNode();
    ArrayNode tools = out.putArray("tools");
    tools.add(
        tool(protocol, "leximeet_help", "列出词典、单词本、复习查询与统计四组能力。", objectSchema(Map.of()), true));
    tools.add(
        tool(
            protocol,
            "leximeet_describe",
            "按分组返回该组各个 action 的参数说明。group 取 dictionary / notebooks / reviews / stats。",
            objectSchema(
                Map.of(
                    "group",
                    property("string", "能力分组：dictionary、notebooks、reviews 或 stats", true))),
            true));
    tools.add(
        tool(
            protocol,
            "leximeet_query",
            "只读统一入口。action 为 lookup_word、search_words、get_examples、get_word_status、"
                + "list_due_reviews、get_learning_stats、list_notebooks、list_encounters。",
            objectSchema(
                Map.of(
                    "action",
                    property("string", "只读动作名", true),
                    "args",
                    objectProperty("动作参数，字段见 leximeet_describe"),
                    "detail",
                    property("boolean", "为 true 时返回完整词卡，默认精简", false))),
            true));
    tools.add(
        tool(
            protocol,
            WRITE_TOOL,
            "唯一写入入口，目前只有 add_to_vocab。默认关闭，需用户在桌面「AI 助手连接」中授权。",
            objectSchema(
                Map.of(
                    "action",
                    property("string", "目前仅 add_to_vocab", true),
                    "args",
                    objectProperty("写入参数，见 leximeet_describe 的 notebooks 组"))),
            false));
    return out;
  }

  private static ObjectNode tool(
      String protocol, String name, String description, ObjectNode inputSchema, boolean readOnly) {
    ObjectNode tool = Json.MAPPER.createObjectNode().put("name", name);
    if (supportsTitle(protocol)) tool.put("title", name);
    tool.put("description", description);
    tool.set("inputSchema", inputSchema);
    if (supportsAnnotations(protocol)) {
      ObjectNode annotations =
          Json.MAPPER
              .createObjectNode()
              .put("readOnlyHint", readOnly)
              .put("destructiveHint", false)
              .put("openWorldHint", false)
              .put("idempotentHint", readOnly);
      tool.set("annotations", annotations);
    }
    return tool;
  }

  private static ObjectNode objectSchema(Map<String, ObjectNode> properties) {
    ObjectNode schema = Json.MAPPER.createObjectNode().put("type", "object");
    ObjectNode props = schema.putObject("properties");
    ArrayNode required = schema.putArray("required");
    properties.forEach(
        (key, value) -> {
          props.set(key, value);
          if (value.path("requiredHint").asBoolean()) required.add(key);
          value.remove("requiredHint");
        });
    schema.put("additionalProperties", false);
    return schema;
  }

  private static ObjectNode property(String type, String description, boolean required) {
    ObjectNode node =
        Json.MAPPER.createObjectNode().put("type", type).put("description", description);
    if (required) node.put("requiredHint", true);
    return node;
  }

  private static ObjectNode objectProperty(String description) {
    return Json.MAPPER
        .createObjectNode()
        .put("type", "object")
        .put("description", description)
        .put("additionalProperties", true);
  }

  record ActionSpec(
      String action, String group, boolean write, String summary, List<String> params) {
    ObjectNode describe() {
      ObjectNode node =
          Json.MAPPER.createObjectNode().put("action", action).put("summary", summary);
      ArrayNode list = node.putArray("params");
      params.forEach(list::add);
      return node;
    }
  }

  static final Map<String, ActionSpec> ACTION_SPECS = new LinkedHashMap<>();

  static {
    ACTION_SPECS.put(
        "lookup_word",
        new ActionSpec(
            "lookup_word",
            "dictionary",
            false,
            "按词头或词条身份查询，默认精简字段",
            List.of("q 或 word：词头", "entryId：可选公共词条", "detail：可选，完整词卡")));
    ACTION_SPECS.put(
        "search_words",
        new ActionSpec(
            "search_words",
            "dictionary",
            false,
            "按词头前缀或包含检索，分页",
            List.of("q：检索词", "limit：默认 20，最大 50", "cursor：上一页 nextCursor")));
    ACTION_SPECS.put(
        "get_examples",
        new ActionSpec(
            "get_examples",
            "dictionary",
            false,
            "读取公共词典例句",
            List.of("q 或 word 或 entryId", "limit：默认 5，最大 20")));
    ACTION_SPECS.put(
        "get_word_status",
        new ActionSpec(
            "get_word_status",
            "dictionary",
            false,
            "是否已收藏、学习阶段与积分",
            List.of("q 或 word 或 entryId 或 wordId")));
    ACTION_SPECS.put(
        "list_due_reviews",
        new ActionSpec(
            "list_due_reviews",
            "reviews",
            false,
            "今日到期复习，不含提交入口",
            List.of("limit：默认 20，最大 50", "cursor：上一页 nextCursor")));
    ACTION_SPECS.put(
        "get_learning_stats",
        new ActionSpec("get_learning_stats", "stats", false, "今日额度与累计学习数量", List.of("无额外参数")));
    ACTION_SPECS.put(
        "list_notebooks",
        new ActionSpec(
            "list_notebooks",
            "notebooks",
            false,
            "单词本列表",
            List.of("limit：默认 20，最大 50", "cursor：上一页 nextCursor")));
    ACTION_SPECS.put(
        "list_encounters",
        new ActionSpec(
            "list_encounters",
            "notebooks",
            false,
            "遇见记录，可按词或日期过滤",
            List.of("q 或 wordId：可选", "from/to：可选 YYYY-MM-DD，非法日期拒绝", "limit、cursor")));
    ACTION_SPECS.put(
        "add_to_vocab",
        new ActionSpec(
            "add_to_vocab",
            "notebooks",
            true,
            "加入单词本；需设置授权。可用 mutationId 幂等",
            List.of(
                "word：词头",
                "notebookId：可选",
                "note：可选笔记",
                "context：可选语境",
                "mutationId：按 clientId 隔离；同键不同请求拒绝")));
  }

  static ObjectNode snapshot() {
    ObjectNode out = Json.MAPPER.createObjectNode().put("protocol", PROTOCOL_2025_06);
    out.set("initialize", initializeResult(PROTOCOL_2025_06));
    out.set("tools", toolsList().path("tools"));
    out.set("help", help());
    ArrayNode actions = out.putArray("actions");
    ACTION_SPECS.values().forEach(spec -> actions.add(spec.describe()));
    return out;
  }

  static JsonNode parseArgs(JsonNode raw) {
    if (raw == null || raw.isMissingNode() || raw.isNull()) return Json.MAPPER.createObjectNode();
    if (!raw.isObject()) throw McpService.invalidArgument("args 必须是对象");
    return raw;
  }
}
