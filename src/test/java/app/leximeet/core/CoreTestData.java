package app.leximeet.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

// 夹具也走正式收藏命令，不为测试保留已退休的业务入口。
final class CoreTestData {
  private CoreTestData() {}

  static ObjectNode collect(LeximeetService service, JsonNode input) throws Exception {
    service.desktop().command(Json.object(input).deepCopy().put("action", "collect"));
    return service.snapshot();
  }
}
