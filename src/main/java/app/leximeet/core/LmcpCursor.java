package app.leximeet.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Connection;
import java.time.Instant;
import java.util.Base64;

// 游标固定查询、工作区世代、修订和评估时间；资料变化时明确要求刷新，禁止拼接两个快照。
final class LmcpCursor {
  record Page(int offset, Instant asOf, String binding) {}

  static Page read(
      LmcpService service, Connection db, String kind, JsonNode params, String revision)
      throws Exception {
    ObjectNode query = Json.object(params).deepCopy();
    query.remove("cursor");
    String binding =
        LmcpContract.digest(
            Json.MAPPER
                .createObjectNode()
                .put("kind", kind)
                .put("generation", LmcpService.meta(db, "generation"))
                .put("revision", revision)
                .set("query", query));
    if (!params.path("cursor").isTextual()) return new Page(0, service.clock.instant(), binding);
    try {
      JsonNode cursor =
          Json.MAPPER.readTree(Base64.getUrlDecoder().decode(params.path("cursor").asText()));
      Instant at = Instant.parse(cursor.path("at").asText());
      if (!cursor.path("binding").asText().equals(binding)
          || at.isAfter(service.clock.instant())
          || at.isBefore(service.clock.instant().minusSeconds(300))
          || cursor.path("offset").asInt(-1) < 0)
        throw LmcpService.error("CURSOR_EXPIRED", "查询或工作区已更新，请从第一页重新读取");
      return new Page(cursor.path("offset").asInt(), at, binding);
    } catch (ApiException failure) {
      throw failure;
    } catch (Exception failure) {
      throw LmcpService.error("CURSOR_EXPIRED", "分页游标无效");
    }
  }

  static ObjectNode result(
      com.fasterxml.jackson.databind.node.ArrayNode items, Page page, int limit) {
    boolean complete = items.size() <= limit;
    if (!complete) items.remove(items.size() - 1);
    ObjectNode result = Json.MAPPER.createObjectNode().put("complete", complete);
    result.set("items", items);
    result.put(
        "nextCursor",
        complete
            ? null
            : Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                    Json.MAPPER
                        .createObjectNode()
                        .put("offset", page.offset() + limit)
                        .put("at", LmcpService.timestamp(page.asOf()))
                        .put("binding", page.binding())
                        .toString()
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    return result;
  }
}
