package app.leximeet.core;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;

// 所有入口共享同一套严格 JSON 限制，避免 HTTP 与备份恢复出现两种校验口径。
final class Json {
  static final ObjectMapper MAPPER =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  static {
    MAPPER
        .getFactory()
        .setStreamReadConstraints(
            StreamReadConstraints.builder()
                .maxNestingDepth(30)
                .maxStringLength(100_000)
                .maxNumberLength(20)
                .build());
  }

  private Json() {}

  static ObjectNode object(JsonNode node) {
    if (node == null || !node.isObject()) throw ApiException.badRequest("请求必须是 JSON 对象");
    return (ObjectNode) node;
  }

  static void fields(JsonNode node, String... allowed) {
    object(node);
    Set<String> whitelist = Set.of(allowed);
    node.fieldNames()
        .forEachRemaining(
            key -> {
              if (!whitelist.contains(key)) throw ApiException.badRequest("不支持的字段：" + key);
            });
  }

  static String text(JsonNode node, String key, int max, boolean required) {
    JsonNode value = node.get(key);
    if (value == null || value.isNull()) {
      if (required) throw ApiException.badRequest(key + " 不能为空");
      return "";
    }
    if (!value.isTextual()) throw ApiException.badRequest(key + " 必须是文本");
    String result = value.textValue().strip();
    if (result.length() > max || (required && result.isEmpty()) || result.indexOf('\0') >= 0)
      throw ApiException.badRequest(key + " 为空、过长或含无效字符");
    return result;
  }

  static String id(JsonNode node, String key, boolean required) {
    String result = text(node, key, 80, required);
    if (!result.isEmpty() && !result.matches("[A-Za-z0-9_-]+"))
      throw ApiException.badRequest(key + " 格式无效");
    return result;
  }

  static String choice(JsonNode node, String key, String fallback, String... options) {
    String value = node.has(key) ? text(node, key, 40, true) : fallback;
    if (!Set.of(options).contains(value)) throw ApiException.badRequest(key + " 取值无效");
    return value;
  }
}
