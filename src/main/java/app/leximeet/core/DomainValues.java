package app.leximeet.core;

import com.fasterxml.jackson.databind.JsonNode;

// 本机领域校验，不依赖任何网络协议或配对服务。
final class DomainValues {
  static String uuid(JsonNode input, String key) {
    JsonNode raw = input.get(key);
    if (raw == null
        || !raw.isTextual()
        || !raw.textValue()
            .matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
      throw error("INVALID_ARGUMENT", key + " 必须为 UUID");
    return raw.textValue();
  }

  static ApiException error(String code, String message) {
    return new ApiException(400, code, message);
  }

  private DomainValues() {}
}
