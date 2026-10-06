package app.leximeet.core;

import static app.leximeet.core.DesktopWorkspaceTest.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// JSON 是完整文件快照的编码，不另维护会遗漏正式学习与练习断点的行格式。
class JsonBackupTest {
  @TempDir Path directory;

  @Test
  void roundTripReplacesTargetAndPreservesCurrentLearningNotesPlanAndFrozenDraft()
      throws Exception {
    var fixture = new DesktopWorkspaceTest();
    fixture.directory = directory;
    Path index = fixture.index(directory.resolve("source"));
    ObjectNode backup;
    String session;
    try (var source = new LeximeetService(directory.resolve("source"), CLOCK)) {
      fixture.mount(source, index);
      fixture.goal(source, "dictionary");
      source
          .desktop()
          .command(
              json(
                  "{\"action\":\"capture\",\"word\":\"alpha\",\"context\":\"alpha appears here.\",\"note\":\"我的笔记\"}"));
      var journey = new DesktopSevenDayTest();
      journey.answer(source, "copy", true);
      var practice =
          source
              .desktop()
              .query(json("{\"kind\":\"practice\",\"scope\":\"library\",\"mode\":\"recall\"}"));
      session = practice.path("sessionId").asText();
      source
          .desktop()
          .command(
              json(
                  "{\"action\":\"practiceSave\",\"scope\":\"library\",\"mode\":\"recall\",\"cursor\":1,\"wordId\":\""
                      + ALPHA
                      + "\",\"draft\":{\"input\":\"al\"}}"));
      backup = source.exportBackup();
      assertEquals(100, backup.path("version").asInt());
      assertEquals("sqlite-base64", backup.path("encoding").asText());
      assertTrue(backup.path("data").isTextual());
      // 仅此备份解析器放宽大字符串，普通业务 JSON 仍保持原有上限。
      backup = Json.object(JsonBackup.MAPPER.readTree(backup.toString()));
    }
    try (var target = new LeximeetService(directory.resolve("target"), CLOCK)) {
      CoreTestData.collect(
          target, Json.MAPPER.createObjectNode().put("word", "target-only").put("note", "应被替换"));
      target.importBackup(backup);
      Path targetIndex = directory.resolve("target/dictionaries/test.sqlite");
      java.nio.file.Files.createDirectories(targetIndex.getParent());
      java.nio.file.Files.copy(index, targetIndex);
      fixture.mount(target, targetIndex);
      assertEquals("dictionary", target.desktop().state().path("profile").path("goal").asText());
      var detail =
          target.desktop().query(json("{\"kind\":\"detail\",\"wordId\":\"" + ALPHA + "\"}"));
      assertEquals("我的笔记", detail.path("note").asText());
      assertEquals(11, detail.path("familiarity").path("score").asInt());
      assertEquals(1, target.snapshot().path("encounters").size());
      assertFalse(target.snapshot().toString().contains("target-only"));
      var restored =
          target
              .desktop()
              .query(
                  json(
                      "{\"kind\":\"practice\",\"scope\":\"library\",\"mode\":\"recall\",\"wordId\":\""
                          + ALPHA
                          + "\"}"));
      assertEquals(session, restored.path("sessionId").asText());
      assertEquals(1, restored.path("cursor").asInt());
      assertEquals("al", restored.path("draft").path("input").asText());
    }
    try (var restarted = new LeximeetService(directory.resolve("target"), CLOCK)) {
      assertEquals(1, restarted.snapshot().path("encounters").size());
    }
  }

  @Test
  void unknownVersionBadEncodingAndCorruptPayloadNeverChangeLocalData() throws Exception {
    try (var service = new LeximeetService(directory.resolve("validation"), Clock.systemUTC())) {
      CoreTestData.collect(
          service, Json.MAPPER.createObjectNode().put("word", "keep").put("note", "保留"));
      ObjectNode backup = service.exportBackup();
      var before = service.snapshot();
      ObjectNode[] invalid = {
        backup.deepCopy().put("version", 6),
        backup.deepCopy().put("schemaVersion", 101),
        backup.deepCopy().put("encoding", "json-rows"),
        backup.deepCopy().put("data", "not-base64"),
        backup
            .deepCopy()
            .put(
                "data",
                java.util.Base64.getEncoder()
                    .encodeToString(
                        "private damaged SQLite bytes"
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8))),
        backup.deepCopy().put("exportedAt", "not-a-time")
      };
      for (ObjectNode value : invalid) {
        assertThrows(ApiException.class, () -> service.importBackup(value));
        assertEquals(before, service.snapshot());
      }
      assertThrows(
          Exception.class,
          () -> Json.MAPPER.readTree("{\"value\":\"" + "x".repeat(100_001) + "\"}"));
    }
  }

  @Test
  void oversizedSqliteIsRejectedBeforeReadingOrEncoding() throws Exception {
    Path file = directory.resolve("sparse-too-large.sqlite");
    try (var channel =
        FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
      channel.position(JsonBackup.MAX_SQLITE_BYTES);
      channel.write(ByteBuffer.wrap(new byte[] {0}));
    }
    assertEquals(
        413,
        assertThrows(ApiException.class, () -> JsonBackup.encode(file, CLOCK.instant().toString()))
            .status());
  }
}
