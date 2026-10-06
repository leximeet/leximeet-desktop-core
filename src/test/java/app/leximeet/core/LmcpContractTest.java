package app.leximeet.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

// Java 实现消费协议仓同一组正反结构例，不以方法名称存在替代响应校验。
class LmcpContractTest {
  @Test
  void allFrozenStructuralVectorsMatch() throws Exception {
    LmcpContract contract = new LmcpContract();
    try (var input = getClass().getResourceAsStream("/lmcp/fixtures/contracts.json")) {
      var vectors = Json.MAPPER.readTree(input);
      assertTrue(vectors.size() >= 60);
      for (var vector : vectors) {
        String name = vector.path("name").asText();
        if (vector.path("valid").asBoolean())
          assertDoesNotThrow(
              () -> contract.definition(vector.path("schema").asText(), vector.path("value")),
              name);
        else
          assertThrows(
              ApiException.class,
              () -> contract.definition(vector.path("schema").asText(), vector.path("value")),
              name);
      }
    }
  }

  @Test
  void canonicalNumbersFollowEcmascriptAndUtf16Sorting() throws Exception {
    assertEquals(
        "[0,1e-7,0.000001,1e+21,1e+23,5e-324]",
        LmcpContract.canonical(Json.MAPPER.readTree("[-0.0,1e-7,1e-6,1e21,1e23,5e-324]")));
    assertEquals(
        "{\"a\":1,\"b\":2}", LmcpContract.canonical(Json.MAPPER.readTree("{\"b\":2,\"a\":1}")));
  }
}
