package app.leximeet.core;

import static app.leximeet.core.LmcpServiceTest.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// 校验正式 note-only 合同的真实读写与旧字段拒绝，不能用两份旧冻结副本相互验收。
class LmcpNoteOnlyContractTest {
  @TempDir Path directory;

  @Test
  void newCaptureAndSummaryOnlyContainNotesAndRetiredAnnotationIsRejected() throws Exception {
    try (var service = new LeximeetService(directory, DesktopWorkspaceTest.CLOCK)) {
      var authorization = pair(service).path("authorization");
      var ref = custom(uuid());
      var input = capture(ref, uuid(), uuid());
      var before = service.snapshot();
      for (String retiredValue : new String[] {"旧释义", ""}) {
        var invalid = input.deepCopy();
        invalid.withObject("data").withObject("annotation").put("meaningSupplement", retiredValue);
        error("INVALID_ARGUMENT", rpc(service, "recordEncounter", invalid, authorization));
        assertEquals(before, service.snapshot());
      }
      var stored = success(rpc(service, "recordEncounter", input, authorization));
      assertEquals(
          object().put("note", "测试语境"), stored.path("entity").path("data").path("annotation"));
      var card = success(rpc(service, "getWord", object().set("word", ref), authorization));
      assertEquals(object().put("note", "测试语境"), card.path("personal"));
      assertEquals(1, service.snapshot().path("encounters").size());
    }
  }

  @Test
  void wordFactsRejectPersonalMeaningAndTagsEvenWhenEmpty() throws Exception {
    try (var service = new LeximeetService(directory, DesktopWorkspaceTest.CLOCK)) {
      var authorization = pair(service).path("authorization");
      var ref = custom(uuid());
      success(rpc(service, "recordEncounter", capture(ref, uuid(), uuid()), authorization));
      var data =
          service
              .lmcp()
              .database
              .read(
                  db ->
                      Json.object(
                          Json.MAPPER.readTree(
                              Sql.first(
                                      db,
                                      "SELECT payload FROM desktop_entities WHERE entity_type='word'")
                                  .path("payload")
                                  .asText())));
      assertFalse(data.has("personalMeaning"));
      assertFalse(data.has("tagIds"));
      var record = DesktopDataModel.record("word", DesktopDataModel.key(ref), 1, data, null);
      service.lmcp().contract.entity(record);
      for (String field : new String[] {"personalMeaning", "tagIds"}) {
        ObjectNode invalid = record.deepCopy();
        if (field.equals("tagIds")) invalid.withObject("data").putArray(field);
        else invalid.withObject("data").put(field, "");
        assertEquals(
            400,
            assertThrows(ApiException.class, () -> service.lmcp().contract.entity(invalid))
                .status());
      }
    }
  }

  @Test
  void desktopCollectionRejectsMeaningInsteadOfStoringAHiddenOverride() throws Exception {
    try (var service = new LeximeetService(directory, DesktopWorkspaceTest.CLOCK)) {
      for (String action : new String[] {"collect", "capture"}) {
        var input =
            object()
                .put("action", action)
                .put("word", "privateword")
                .put("meaning", "不应存储")
                .put("context", "A privateword appears.");
        var before = service.snapshot();
        assertEquals(
            400, assertThrows(ApiException.class, () -> service.desktop().command(input)).status());
        assertEquals(before, service.snapshot());
      }
    }
  }
}
