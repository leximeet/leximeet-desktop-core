package app.leximeet.core;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;

// 小容量 JSON 只包装完整 SQLite；业务数据与大容量文件备份共用一套恢复校验。
final class JsonBackup {
  // 为 JSON 字段留出固定余量；Base64 每三个原始字节占四个文本字节。
  static final int MAX_SQLITE_BYTES = (CoreServer.MAX_BACKUP_BYTES - 1024) / 4 * 3;
  static final ObjectMapper MAPPER = Json.MAPPER.copy();

  static {
    MAPPER
        .getFactory()
        .setStreamReadConstraints(
            StreamReadConstraints.builder()
                .maxNestingDepth(5)
                .maxStringLength(CoreServer.MAX_BACKUP_BYTES)
                .maxNumberLength(20)
                .build());
  }

  private JsonBackup() {}

  static ObjectNode encode(Path file, String exportedAt) throws Exception {
    if (Files.size(file) > MAX_SQLITE_BYTES)
      throw new ApiException(413, "BACKUP_TOO_LARGE", "JSON 备份超过 64 MiB，请使用 SQLite 文件备份");
    ObjectNode result = Json.MAPPER.createObjectNode();
    result.put("format", "leximeet-backup");
    result.put("version", 100);
    result.put("schemaVersion", DesktopSchema.VERSION);
    result.put("encoding", "sqlite-base64");
    result.put("exportedAt", exportedAt);
    result.put("data", Base64.getEncoder().encodeToString(Files.readAllBytes(file)));
    return result;
  }

  static PortableBackup.Archive decode(JsonNode input) throws Exception {
    Json.fields(input, "format", "version", "schemaVersion", "encoding", "exportedAt", "data");
    if (!input.path("format").asText().equals("leximeet-backup")
        || !input.path("version").isInt()
        || input.path("version").asInt() != 100
        || !input.path("schemaVersion").isInt()
        || input.path("schemaVersion").asInt() != DesktopSchema.VERSION
        || !input.path("encoding").asText().equals("sqlite-base64"))
      throw ApiException.badRequest(
          "只接受 version=100、schemaVersion=100 的 sqlite-base64 备份，不转换旧开发格式");
    try {
      Instant.parse(Json.text(input, "exportedAt", 50, true));
    } catch (Exception error) {
      throw ApiException.badRequest("备份导出时间无效");
    }
    if (!input.path("data").isTextual()) throw ApiException.badRequest("备份 data 必须是 Base64 文本");
    String encoded = input.path("data").textValue();
    if (encoded.length() > MAX_SQLITE_BYTES / 3 * 4)
      throw new ApiException(413, "BACKUP_TOO_LARGE", "JSON 备份超过 64 MiB，请使用 SQLite 文件备份");
    byte[] decoded;
    try {
      decoded = Base64.getDecoder().decode(encoded);
      if (decoded.length < 16 || !Base64.getEncoder().encodeToString(decoded).equals(encoded))
        throw new IllegalArgumentException();
    } catch (IllegalArgumentException error) {
      throw ApiException.badRequest("备份不是有效的标准 Base64 数据");
    }
    return PortableBackup.receive(new ByteArrayInputStream(decoded), MAX_SQLITE_BYTES);
  }
}
