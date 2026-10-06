package app.leximeet.core;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

// 跨端共享向量固定公开输入、xxx、UTF-16 位置、句子边界和截长结果。
class CapturePolicyTest {
  @Test
  void sharedVectorsHaveExactSentenceAndRangeResults() throws Exception {
    JsonNode vectors;
    try (var stream = getClass().getResourceAsStream("/capture-policy-v1.json")) {
      vectors = Json.MAPPER.readTree(stream);
    }
    for (JsonNode vector : vectors.path("segments")) {
      var p = vector.path("policy");
      var policy =
          new CapturePolicy.Preferences(
              p.path("duplicateWindowDays").asInt(),
              p.path("sensitiveRedactionEnabled").asBoolean(),
              p.path("contextMaxLength").asInt());
      if (vector.has("error")) {
        var failure =
            assertThrows(
                ApiException.class,
                () ->
                    CapturePolicy.segment(
                        vector.path("input").asText(),
                        vector.path("surface").asText(),
                        vector.path("ranges"),
                        policy));
        assertEquals(vector.path("error").asText(), failure.code(), vector.path("id").asText());
      } else {
        var safe =
            CapturePolicy.segment(
                vector.path("input").asText(),
                vector.path("surface").asText(),
                vector.path("ranges"),
                policy);
        assertEquals(
            vector.path("expected").path("text").asText(), safe.text(), vector.path("id").asText());
        assertEquals(
            vector.path("expected").path("ranges"), safe.ranges(), vector.path("id").asText());
        assertTrue(safe.text().length() <= policy.contextMaxLength());
      }
    }
    for (var vector : vectors.path("contextKeys"))
      assertEquals(
          vector.path("expected").asText(),
          CapturePolicy.contextKey(vector.path("input").asText()));
  }

  @Test
  void multipleOccurrencesRetainOnlyRangesInsideFirstSentence() throws Exception {
    var ranges =
        Json.MAPPER.readTree(
            "[{\"start\":0,\"end\":5},{\"start\":12,\"end\":17},{\"start\":20,\"end\":25}]");
    var safe =
        CapturePolicy.segment(
            "alpha meets alpha.  alpha waits.",
            "alpha",
            ranges,
            new CapturePolicy.Preferences(7, true, 500));
    assertEquals("alpha meets alpha.", safe.text());
    assertEquals(2, safe.ranges().size());
  }
}
