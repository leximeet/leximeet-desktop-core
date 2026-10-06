package app.leximeet.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

// 词卡展示偏好只保存模块标识与顺序，不复制词典、遇见或复习内容。 四档预设由客户端按同一稳定标识解析；自定义配置保留在本机设置中，修订号阻止旧窗口覆盖新选择。
final class CardLayoutSupport {
  static final List<String> MODULES =
      List.of(
          "pronunciation",
          "meaning",
          "collections",
          "evidence",
          "note",
          "encounterList",
          "reviewSummary");
  private static final Set<String> MODULE_IDS = Set.copyOf(MODULES);

  private CardLayoutSupport() {}

  static ObjectNode defaults() {
    ObjectNode value = Json.MAPPER.createObjectNode();
    value.put("level", "balanced");
    value.put("mode", "preset");
    ArrayNode sections = value.putArray("customSections");
    MODULES.forEach(sections::add);
    value.put("customSaved", false);
    value.put("revision", 0);
    return value;
  }

  static ObjectNode update(JsonNode input, JsonNode current) {
    Json.fields(input, "expectedRevision", "level", "mode", "customSections");
    JsonNode expected = input.path("expectedRevision");
    if (!expected.isIntegralNumber() || !expected.canConvertToInt() || expected.asInt() < 0)
      throw ApiException.badRequest("expectedRevision 必须是非负整数");
    if (expected.asInt() != current.path("revision").asInt())
      throw ApiException.conflict("词卡布局已经在另一窗口更新，请刷新后重试；当前草稿尚未保存");
    if (!input.has("level") || !input.has("mode") || !input.has("customSections"))
      throw ApiException.badRequest("词卡布局缺少等级、模式或模块顺序");
    ObjectNode next = Json.MAPPER.createObjectNode();
    next.put(
        "level",
        Json.choice(input, "level", "balanced", "minimal", "balanced", "rich", "complete"));
    next.put("mode", Json.choice(input, "mode", "preset", "preset", "custom"));
    next.set("customSections", input.path("customSections").deepCopy());
    next.put(
        "customSaved",
        next.path("mode").asText().equals("custom") || current.path("customSaved").asBoolean());
    next.put("revision", current.path("revision").asInt() + 1);
    validateStored(next);
    return next;
  }

  static void validateStored(JsonNode value) {
    Json.fields(value, "level", "mode", "customSections", "customSaved", "revision");
    for (String field : new String[] {"level", "mode", "customSections", "customSaved", "revision"})
      if (!value.has(field)) throw ApiException.badRequest("词卡布局缺少 " + field);
    Json.choice(value, "level", "balanced", "minimal", "balanced", "rich", "complete");
    Json.choice(value, "mode", "preset", "preset", "custom");
    if (!value.path("customSaved").isBoolean()) throw ApiException.badRequest("customSaved 必须为布尔值");
    JsonNode revision = value.path("revision");
    if (!revision.isIntegralNumber() || !revision.canConvertToInt() || revision.asInt() < 0)
      throw ApiException.badRequest("词卡布局修订号无效");
    JsonNode sections = value.path("customSections");
    if (!sections.isArray() || sections.isEmpty() || sections.size() > MODULES.size())
      throw ApiException.badRequest("词卡模块顺序无效");
    Set<String> seen = new HashSet<>();
    for (JsonNode section : sections) {
      if (!section.isTextual()
          || !MODULE_IDS.contains(section.asText())
          || !seen.add(section.asText())) throw ApiException.badRequest("词卡模块未知或重复");
    }
    // 词头固定显示；至少保留释义这一项可阅读内容，用户不能配置成空白词卡。
    if (!seen.contains("meaning")) throw ApiException.badRequest("词卡必须保留释义模块");
  }
}
