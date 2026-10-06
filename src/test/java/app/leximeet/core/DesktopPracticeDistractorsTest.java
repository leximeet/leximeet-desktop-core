package app.leximeet.core;

import static app.leximeet.core.DesktopWorkspaceTest.*;
import static app.leximeet.core.LmcpServiceTest.object;
import static app.leximeet.core.LmcpServiceTest.uuid;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.HashSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// 使用真实公共词典和 SQLite 冻结题验证候选范围、跨题变化及重启后的相同判题依据。
class DesktopPracticeDistractorsTest {
  @TempDir Path directory;

  @Test
  void prefersFrozenScopeAndPersistsEachQuestionAcrossRestart() throws Exception {
    var f = new DesktopWorkspaceTest();
    f.directory = directory;
    Path index = f.index(directory);
    String[][] entries = {
      {"orchard", "果园"}, {"river", "河流"}, {"mountain", "山峰"}, {"valley", "山谷"},
      {"forest", "森林"}, {"bridge", "桥梁"}, {"island", "岛屿"}, {"harbor", "港口"}
    };
    // 正确释义来自只读公共资源，不能通过已经删除的个人释义输入伪造练习资料。
    try (var db = java.sql.DriverManager.getConnection("jdbc:sqlite:" + index)) {
      int position = 2;
      for (var entry : entries) {
        String id = uuid();
        var payload =
            object()
                .put("entry_id", id)
                .put("headword", entry[0])
                .put("lookup_key", entry[0])
                .put("schema_version", "leximeet.entry.v2");
        payload.putArray("senses").addObject().put("short_gloss", entry[1]);
        Sql.execute(
            db,
            "INSERT INTO entries VALUES(?,?,?,?,?,?)",
            id,
            entry[0],
            entry[0],
            entry[1],
            payload.toString(),
            position++);
      }
      Sql.execute(db, "UPDATE metadata SET payload=json_set(payload,'$.entryCount',10)");
    }
    String word = "orchard", seed = uuid();
    JsonNode frozen;
    try (var s = new LeximeetService(directory, CLOCK)) {
      mountFullScope(s, index);
      for (var entry : entries) CoreTestData.collect(s, object().put("word", entry[0]));
      for (String current : new String[] {"orchard", "river", "mountain", "valley"}) {
        var q =
            s.desktop()
                .query(
                    object()
                        .put("kind", "practiceQuestion")
                        .put("scope", "library")
                        .put("wordId", current)
                        .put("mode", "meaning-choice")
                        .put("attemptId", current.equals(word) ? seed : uuid()));
        assertFalse(q.path("unavailable").asBoolean());
        assertEquals(4, q.path("options").size());
        var texts = new HashSet<String>();
        for (JsonNode choice : q.path("options")) {
          String text = choice.path("text").asText();
          assertTrue(text.matches("果园|河流|山峰|山谷|森林|桥梁|岛屿|港口"), "不该固定使用词典开头的干扰项");
          assertTrue(texts.add(text));
        }
        assertTrue(texts.contains(q.path("meaning").asText()));
      }
      var sets = new HashSet<String>();
      String session =
          s.desktop()
              .query(object().put("kind", "practice").put("scope", "library"))
              .path("sessionId")
              .asText();
      // 固定种子验证采样差异，不依赖随机 UUID 恰好抽到不同集合来使测试通过。
      for (int i = 0; i < 8; i++) {
        String currentSeed = "sample-" + i;
        var sampled =
            s.lmcp()
                .database
                .read(db -> s.desktop().practice().choices(db, word, currentSeed, session));
        var texts = new java.util.TreeSet<String>();
        for (JsonNode option : sampled.path("options")) texts.add(option.path("text").asText());
        sets.add(texts.toString());
      }
      assertTrue(sets.size() > 1, "跨题应重新取样，而不是仅重排同三个干扰释义");
      frozen =
          s.desktop()
              .query(
                  object()
                      .put("kind", "practiceQuestion")
                      .put("scope", "library")
                      .put("wordId", word)
                      .put("mode", "meaning-choice")
                      .put("attemptId", seed));
    }
    try (var s = new LeximeetService(directory, CLOCK)) {
      mountFullScope(s, index);
      var resumed =
          s.desktop()
              .query(
                  object()
                      .put("kind", "practiceQuestion")
                      .put("scope", "library")
                      .put("wordId", word)
                      .put("mode", "meaning-choice")
                      .put("attemptId", seed));
      assertEquals(frozen, resumed);
      String correct = "";
      for (JsonNode option : resumed.path("options"))
        if (option.path("text").asText().equals("果园")) correct = option.path("id").asText();
      var result =
          s.desktop()
              .command(
                  object()
                      .put("action", "practiceRecord")
                      .put("scope", "library")
                      .put("wordId", word)
                      .put("mode", "meaning-choice")
                      .put("attemptId", seed)
                      .put("submissionId", uuid())
                      .put("choiceId", correct));
      assertTrue(result.path("practiceFeedback").path("correct").asBoolean());
      assertEquals(1, result.path("practiceFeedback").path("delta").asInt());
    }
  }

  private void mountFullScope(LeximeetService service, Path index) throws Exception {
    service
        .desktop()
        .mount(
            object()
                .put("file", index.toString())
                .put("edition", "core-text")
                .put("version", "0.0.3")
                .put("manifestSha", SHA)
                .put("entryCount", 10));
  }

  @Test
  void publicSamplingIsDeterministicAndDoesNotMaterializeDistractors() throws Exception {
    var f = new DesktopWorkspaceTest();
    f.directory = directory;
    try (var s = new LeximeetService(directory, CLOCK)) {
      f.mount(s, f.index(directory));
      var first =
          s.lmcp().database.read(db -> s.desktop().practice().choices(db, ALPHA, "stable-seed"));
      var second =
          s.lmcp().database.read(db -> s.desktop().practice().choices(db, ALPHA, "stable-seed"));
      assertEquals(first, second);
      assertEquals(2, first.path("options").size());
      assertEquals(0, s.snapshot().path("words").size());
    }
  }
}
