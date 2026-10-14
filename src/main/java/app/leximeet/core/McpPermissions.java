package app.leximeet.core;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Connection;

/** AI 助手连接的授权：默认只读；写入开关保存在设置里，可随时撤销。 */
final class McpPermissions {
  static final String WRITE_SETTING = "assistantWriteEnabled";

  private McpPermissions() {}

  static boolean writeEnabled(Connection db) throws Exception {
    JsonNode settings =
        SettingsSupport.normalize(
            Json.object(
                Json.MAPPER.readTree(
                    Sql.first(db, "SELECT payload FROM settings WHERE id=1")
                        .path("payload")
                        .asText())));
    return settings.path(WRITE_SETTING).asBoolean(false);
  }

  static void requireWrite(Connection db) throws Exception {
    if (!writeEnabled(db))
      throw new ApiException(403, "WRITE_NOT_AUTHORIZED", "加入单词本未授权。请在桌面设置「AI 助手连接」中打开写入能力。");
  }
}
