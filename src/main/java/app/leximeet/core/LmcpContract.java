package app.leximeet.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

// 消费打包的正式契约快照。只解析本地 $ref，绝不按输入 URL 下载 Schema。
final class LmcpContract {
  private final Map<String, JsonNode> documents = new LinkedHashMap<>();
  final JsonNode metadata;
  final JsonNode mergeProfile;
  final JsonNode algorithmProfile;
  final Map<String, JsonNode> methods = new LinkedHashMap<>();
  private final JsonNode api;

  LmcpContract() throws Exception {
    metadata = load("contract.json");
    verifySnapshot();
    mergeProfile = loadDomain("learning-sync-v1.json");
    algorithmProfile = loadDomain("fsrs6-v1.json");
    api = load("schemas/desktop-api.v1.schema.json");
    for (String file :
        new String[] {
          "desktop-api.v1.schema.json",
          "reading-domain.v1.schema.json",
          "dictionary-entry.v2.schema.json",
          "browser-host.v1.schema.json"
        }) {
      JsonNode schema = load("schemas/" + file);
      documents.put(schema.path("$id").asText(), schema);
    }
    JsonNode domain = loadDomain("domain.v1.schema.json");
    documents.put(domain.path("$id").asText(), domain);
    for (JsonNode method : load("methods.json"))
      methods.put(method.path("method").asText(), method);
    if (!methods.keySet().equals(LmcpRoutes.METHODS))
      throw new IllegalStateException("LMCP 契约与业务注册表不一致");
  }

  // 安装物必须包含同一份完整规范，不能只展示一个未校验的版本号或摘要。
  private void verifySnapshot() throws Exception {
    JsonNode manifest = load("contract-manifest.json");
    ObjectNode configuration = Json.object(metadata).deepCopy();
    configuration.remove("contractDigest");
    if (!digest(configuration).equals(manifest.path("configurationSha256").asText()))
      throw new IllegalStateException("LMCP 契约配置摘要不匹配");
    if (!digest(manifest).equals(metadata.path("contractDigest").asText()))
      throw new IllegalStateException("LMCP 契约清单摘要不匹配");
    for (JsonNode file : manifest.path("files")) {
      String path = file.path("path").asText();
      if (path.startsWith("/") || path.contains(".."))
        throw new IllegalStateException("LMCP 契约资源路径无效");
      try (var input = getClass().getResourceAsStream("/lmcp/" + path)) {
        if (input == null) throw new IllegalStateException("LMCP 契约资源缺失");
        String hash =
            java.util.HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(input.readAllBytes()));
        if (!hash.equals(file.path("sha256").asText()))
          throw new IllegalStateException("LMCP 契约资源摘要不匹配：" + path);
      }
    }
  }

  private JsonNode loadDomain(String path) throws Exception {
    try (var in = getClass().getResourceAsStream("/domain/" + path)) {
      if (in == null) throw new IllegalStateException("缺少桌面内部领域结构");
      return Json.MAPPER.readTree(in);
    }
  }

  private JsonNode load(String path) throws Exception {
    try (var in = getClass().getResourceAsStream("/lmcp/" + path)) {
      if (in == null) throw new IllegalStateException("缺少 LMCP 契约资源：" + path);
      return Json.MAPPER.readTree(in);
    }
  }

  void request(JsonNode envelope) {
    validate(api.path("$defs").path("Request"), envelope, api, "request");
  }

  void result(String method, JsonNode output) {
    JsonNode definition = methods.get(method);
    if (definition == null) throw invalid("method");
    validate(resolve(definition.path("result").asText(), api), output, api, "result");
  }

  void definition(String name, JsonNode value) {
    for (JsonNode document : documents.values())
      if (document.path("$defs").has(name)) {
        validate(document.path("$defs").path(name), value, document, name);
        return;
      }
    throw new IllegalStateException("缺少契约定义：" + name);
  }

  JsonNode domain(String name) {
    return documents
        .get("https://leximeet.github.io/contracts/domain.v1.schema.json")
        .path("$defs")
        .path(name);
  }

  void entity(JsonNode record) {
    validate(
        domain("EntityRecord"),
        record,
        documents.get("https://leximeet.github.io/contracts/domain.v1.schema.json"),
        "entity");
  }

  private JsonNode resolve(String ref, JsonNode current) {
    int separator = ref.indexOf('#');
    String base = separator < 0 ? ref : ref.substring(0, separator);
    JsonNode document = base.isEmpty() ? current : documents.get(base);
    if (document == null) throw new IllegalStateException("未知本地契约引用");
    return separator < 0 ? document : document.at(ref.substring(separator + 1));
  }

  private void validate(JsonNode schema, JsonNode value, JsonNode document, String path) {
    if (schema.isBoolean()) {
      if (!schema.asBoolean()) throw invalid(path);
      return;
    }
    if (schema.has("if")) {
      boolean condition = true;
      try {
        validate(schema.path("if"), value, document, path);
      } catch (ApiException failure) {
        condition = false;
      }
      if (condition && schema.has("then")) validate(schema.path("then"), value, document, path);
      if (!condition && schema.has("else")) validate(schema.path("else"), value, document, path);
    }
    if (schema.isMissingNode()) throw new IllegalStateException("缺少契约定义：" + path);
    if (schema.has("$ref")) {
      String ref = schema.path("$ref").asText();
      int pos = ref.indexOf('#');
      JsonNode target = pos <= 0 ? document : documents.get(ref.substring(0, pos));
      validate(resolve(ref, document), value, target, path);
      return;
    }
    for (String union : new String[] {"oneOf", "anyOf", "allOf"})
      if (schema.has(union)) {
        int matched = 0;
        for (JsonNode option : schema.path(union))
          try {
            validate(option, value, document, path);
            matched++;
          } catch (ApiException ignored) {
          }
        if (union.equals("allOf")
            ? matched != schema.path(union).size()
            : union.equals("oneOf") ? matched != 1 : matched == 0) throw invalid(path);
      }
    if (schema.has("const") && !schema.path("const").equals(value)) throw invalid(path);
    if (schema.has("enum")) {
      boolean match = false;
      for (JsonNode allowed : schema.path("enum")) if (allowed.equals(value)) match = true;
      if (!match) throw invalid(path);
    }
    if (schema.path("type").isArray()) {
      boolean matched = false;
      for (JsonNode option : schema.path("type")) {
        ObjectNode candidate = Json.object(schema).deepCopy();
        candidate.set("type", option);
        try {
          validate(candidate, value, document, path);
          matched = true;
          break;
        } catch (ApiException ignored) {
        }
      }
      if (!matched) throw invalid(path);
      return;
    }
    String type = schema.path("type").asText();
    boolean matches =
        switch (type) {
          case "object" -> value.isObject();
          case "array" -> value.isArray();
          case "string" -> value.isTextual();
          case "integer" -> value.isIntegralNumber();
          case "number" -> value.isNumber();
          case "boolean" -> value.isBoolean();
          case "null" -> value.isNull();
          default -> true;
        };
    if (!matches) throw invalid(path);
    if (value.isObject()) {
      for (JsonNode required : schema.path("required"))
        if (!value.has(required.asText())) throw invalid(path + "." + required.asText());
      var keys = value.fieldNames();
      while (keys.hasNext()) {
        String key = keys.next();
        JsonNode property = schema.path("properties").get(key);
        if (property != null) validate(property, value.get(key), document, path + "." + key);
        else if (schema.has("additionalProperties")
            && !schema.path("additionalProperties").asBoolean(true))
          throw invalid(path + "." + key);
      }
    }
    if (value.isArray()) {
      if (schema.has("minItems") && value.size() < schema.path("minItems").asInt()
          || schema.has("maxItems") && value.size() > schema.path("maxItems").asInt())
        throw invalid(path);
      if (schema.path("uniqueItems").asBoolean()) {
        var set = new java.util.HashSet<JsonNode>();
        for (JsonNode item : value) if (!set.add(item)) throw invalid(path);
      }
      if (schema.has("items"))
        for (int i = 0; i < value.size(); i++)
          validate(schema.path("items"), value.get(i), document, path + "[" + i + "]");
    }
    if (value.isNumber()) {
      if (schema.has("minimum")
              && value.decimalValue().compareTo(schema.path("minimum").decimalValue()) < 0
          || schema.has("maximum")
              && value.decimalValue().compareTo(schema.path("maximum").decimalValue()) > 0)
        throw invalid(path);
    }
    if (value.isTextual()) {
      canonical(value); // RFC 8785 禁止孤立 Unicode 代理字符。
      String text = value.asText();
      int size = text.codePointCount(0, text.length());
      if (text.indexOf('\0') >= 0
          || schema.has("minLength") && size < schema.path("minLength").asInt()
          || schema.has("maxLength") && size > schema.path("maxLength").asInt())
        throw invalid(path);
      if (schema.has("pattern")
          && !Pattern.compile(schema.path("pattern").asText()).matcher(text).find())
        throw invalid(path);
      try {
        switch (schema.path("format").asText()) {
          case "date-time" -> Instant.parse(text);
          case "date" -> LocalDate.parse(text);
          case "uuid" -> java.util.UUID.fromString(text);
          case "uri" -> {
            if (!URI.create(text).isAbsolute()) throw invalid(path);
          }
          default -> {}
        }
      } catch (RuntimeException error) {
        throw invalid(path);
      }
    }
  }

  private ApiException invalid(String field) {
    return new ApiException(400, "INVALID_ARGUMENT", "请求不符合契约：" + field);
  }

  // RFC 8785 对象按 UTF-16 键排序；数据包只用安全整数，原始浮点禁止悄然截断。
  static String canonical(JsonNode node) {
    if (node.isTextual()) {
      String value = node.asText();
      for (int i = 0; i < value.length(); i++) {
        char c = value.charAt(i);
        if (Character.isHighSurrogate(c)) {
          if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(++i)))
            throw ApiException.badRequest("文本包含无效 Unicode 代理字符");
        } else if (Character.isLowSurrogate(c))
          throw ApiException.badRequest("文本包含无效 Unicode 代理字符");
      }
    }
    if (node.isObject()) {
      TreeMap<String, JsonNode> sorted = new TreeMap<>();
      node.fields().forEachRemaining(e -> sorted.put(e.getKey(), e.getValue()));
      StringBuilder out = new StringBuilder("{");
      boolean first = true;
      for (var item : sorted.entrySet()) {
        if (!first) out.append(',');
        first = false;
        out.append(canonical(Json.MAPPER.valueToTree(item.getKey())))
            .append(':')
            .append(canonical(item.getValue()));
      }
      return out.append('}').toString();
    }
    if (node.isArray()) {
      StringBuilder out = new StringBuilder("[");
      for (int i = 0; i < node.size(); i++) {
        if (i > 0) out.append(',');
        out.append(canonical(node.get(i)));
      }
      return out.append(']').toString();
    }
    if (node.isFloatingPointNumber()) {
      double number = node.asDouble();
      if (!Double.isFinite(number)) throw ApiException.badRequest("不接受非有限数值");
      if (number == 0) return "0";
      BigDecimal exact = new BigDecimal(number), shortest = null;
      for (int digits = 1; digits <= 17; digits++) {
        BigDecimal rounded =
            exact
                .round(new java.math.MathContext(digits, java.math.RoundingMode.HALF_EVEN))
                .stripTrailingZeros();
        if (Double.doubleToLongBits(rounded.doubleValue()) == Double.doubleToLongBits(number)) {
          shortest = rounded;
          break;
        }
      }
      if (shortest == null) throw new IllegalStateException("数值无法规范化");
      double magnitude = Math.abs(number);
      if (magnitude >= 1e-6 && magnitude < 1e21) return shortest.toPlainString();
      int exponent = shortest.precision() - shortest.scale() - 1;
      String coefficient = shortest.movePointLeft(exponent).stripTrailingZeros().toPlainString();
      return coefficient + "e" + (exponent >= 0 ? "+" : "") + exponent;
    }
    return node.toString();
  }

  static String digest(JsonNode value) {
    return hash(canonical(value));
  }

  static String hash(String value) {
    try {
      return java.util.HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception error) {
      throw new IllegalStateException(error);
    }
  }
}
