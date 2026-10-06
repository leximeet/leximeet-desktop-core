package app.leximeet.core;

import static app.leximeet.core.LmcpServiceTest.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// 公共身份与资源版本分开；只读查询、词本游标和采集均调用实际 Core。
class LmcpReadingTest {
  @TempDir Path directory;

  DesktopWorkspaceTest fixture() {
    var f = new DesktopWorkspaceTest();
    f.directory = directory;
    return f;
  }

  ObjectNode publicRef() {
    return DesktopDataModel.wordRef(DesktopWorkspaceTest.ALPHA, "", "alpha", "0.0.3");
  }

  void completeDictionary(Path index) throws Exception {
    try (var db = DriverManager.getConnection("jdbc:sqlite:" + index)) {
      for (JsonNode row : Sql.rows(db, "SELECT id,payload FROM entries")) {
        var entry =
            Json.object(Json.MAPPER.readTree(row.path("payload").asText()))
                .put("base_entry_schema", "leximeet.entry.v1");
        var learning =
            entry.putObject("learning").put("schema_version", "leximeet.learning-card.v1");
        for (String field :
            new String[] {"collections", "mnemonics", "illustrations", "source_signals"})
          learning.putArray(field);
        var lexical = learning.putObject("lexical");
        for (String field : new String[] {"synonyms", "antonyms", "related_words", "phrases"})
          lexical.putArray(field);
        var practice = learning.putObject("practice");
        practice.putArray("questions");
        practice.putArray("attested_examples");
        learning
            .putObject("audio")
            .put("offline_index_entry_id", row.path("id").asText())
            .putArray("alternate_candidates");
        Sql.execute(
            db,
            "UPDATE entries SET payload=? WHERE id=?",
            entry.toString(),
            row.path("id").asText());
      }
    }
  }

  @Test
  void publicResourceAndNotebookOptionalApiReturnValidatedDtos() throws Exception {
    var f = fixture();
    Path index = f.index(directory);
    completeDictionary(index);
    try (var s = new LeximeetService(directory, DesktopWorkspaceTest.CLOCK)) {
      f.mount(s, index);
      f.goal(s, "dictionary");
      var auth = pair(s).path("authorization");
      var entry =
          success(
              rpc(
                  s,
                  "getPublicEntry",
                  object().put("entryId", DesktopWorkspaceTest.ALPHA).put("release", "0.0.3"),
                  auth));
      assertEquals("alpha", entry.path("entry").path("headword").asText());
      var card = success(rpc(s, "getWord", object().set("word", publicRef()), auth));
      assertTrue(card.path("inTarget").asBoolean());
      assertFalse(card.path("collected").asBoolean());
      assertTrue(card.path("personal").isNull());
      var match = object().put("kind", "tokens");
      match.putArray("tokens").add("alpha").add("beta");
      assertEquals(2, success(rpc(s, "matchWords", match, auth)).path("results").size());
      s.desktop().command(object().put("action", "createBook").put("name", "可选词本"));
      assertTrue(
          success(rpc(s, "listNotebooks", object(), auth))
              .path("items")
              .toString()
              .contains("可选词本"));
      assertEquals(0, s.snapshot().path("words").size());
    }
  }

  @Test
  void missingReleasePreservesSourceRefAndPrivateData() throws Exception {
    var f = fixture();
    Path index = f.index(directory);
    completeDictionary(index);
    try (var s = new LeximeetService(directory, DesktopWorkspaceTest.CLOCK)) {
      f.mount(s, index);
      var auth = pair(s).path("authorization");
      success(rpc(s, "recordEncounter", capture(publicRef(), uuid(), uuid()), auth));
      Path replacement = index.resolveSibling("new-release.sqlite");
      Files.copy(index, replacement);
      try (var resource = DriverManager.getConnection("jdbc:sqlite:" + replacement)) {
        Sql.execute(resource, "UPDATE metadata SET payload=json_set(payload,'$.version','0.0.4')");
      }
      s.desktop()
          .mount(
              object()
                  .put("file", replacement.toString())
                  .put("edition", "core-text")
                  .put("version", "0.0.4")
                  .put("manifestSha", DesktopWorkspaceTest.SHA)
                  .put("entryCount", 2));
      var card = success(rpc(s, "getWord", object().set("word", publicRef()), auth));
      assertEquals(publicRef(), card.path("word"));
      assertEquals("missing", card.path("resourceStatus").asText());
      error(
          "RESOURCE_UNAVAILABLE",
          rpc(
              s,
              "getPublicEntry",
              object().put("entryId", DesktopWorkspaceTest.ALPHA).put("release", "0.0.3"),
              auth));
      assertEquals(
          "available",
          success(
                  rpc(
                      s,
                      "getWord",
                      object().set("word", publicRef().put("release", "0.0.4")),
                      auth))
              .path("resourceStatus")
              .asText());
      var source =
          s.lmcp()
              .database
              .read(
                  db ->
                      Sql.first(
                          db,
                          "SELECT payload FROM desktop_word_identity WHERE word_id=?",
                          DesktopWorkspaceTest.ALPHA));
      assertEquals(
          "0.0.3", Json.MAPPER.readTree(source.path("payload").asText()).path("release").asText());
      assertEquals(1, s.snapshot().path("encounters").size());
    }
  }

  @Test
  void customSameSpellingRemainsDifferentFromPublicAndOtherCustom() throws Exception {
    var f = fixture();
    Path index = f.index(directory);
    try (var s = new LeximeetService(directory, DesktopWorkspaceTest.CLOCK)) {
      var auth = pair(s).path("authorization");
      ObjectNode a = custom(uuid()), b = custom(uuid());
      success(rpc(s, "recordEncounter", capture(a, uuid(), uuid()), auth));
      success(rpc(s, "recordEncounter", capture(b, uuid(), uuid()), auth));
      f.mount(s, index);
      var p = object().put("kind", "tokens");
      p.putArray("tokens").add("alpha");
      assertEquals(
          3, success(rpc(s, "matchWords", p, auth)).path("results").get(0).path("matches").size());
      assertEquals(2, s.snapshot().path("words").size());
      assertEquals(a, success(rpc(s, "getWord", object().set("word", a), auth)).path("word"));
    }
  }

  @Test
  void blankAnnotationsDoNotReplaceExistingPersonalNotes() throws Exception {
    try (var s = new LeximeetService(directory, DesktopWorkspaceTest.CLOCK)) {
      JsonNode auth = pair(s).path("authorization"), ref = custom(uuid());
      success(rpc(s, "recordEncounter", capture(ref, uuid(), uuid()), auth));
      var second = capture(ref, uuid(), uuid());
      // 验证空注释不覆盖个人笔记，第二次使用不同语境；相同语境由独立政策测试验证。
      second
          .withObject("data")
          .put("originalSentence", "alpha appears again.")
          .put("savedExcerpt", "alpha appears again.");
      second.withObject("data").withObject("annotation").put("note", "");
      success(rpc(s, "recordEncounter", second, auth));
      assertEquals(
          "测试语境",
          success(rpc(s, "getWord", object().set("word", ref), auth))
              .path("personal")
              .path("note")
              .asText());
      assertEquals(2, s.snapshot().path("encounters").size());
    }
  }

  @Test
  void exactCustomSpellingPrecedesPublicQueryKey() throws Exception {
    var f = fixture();
    Path index = f.index(directory);
    try (var s = new LeximeetService(directory, DesktopWorkspaceTest.CLOCK)) {
      f.mount(s, index);
      var auth = pair(s).path("authorization");
      var ref = custom(uuid()).put("headword", "Alpha");
      var input = capture(ref, uuid(), uuid());
      input
          .withObject("data")
          .put("surface", "Alpha")
          .put("originalSentence", "Alpha")
          .put("savedExcerpt", "Alpha");
      success(rpc(s, "recordEncounter", input, auth));
      var query = object().put("kind", "tokens");
      query.putArray("tokens").add("Alpha").add("ALPHA");
      var results = success(rpc(s, "matchWords", query, auth)).path("results");
      assertEquals(1, results.get(0).path("matches").size());
      assertEquals(ref, results.get(0).path("matches").get(0).path("word"));
      assertEquals(1, results.get(1).path("matches").size());
      assertEquals(
          "dictionary", results.get(1).path("matches").get(0).path("word").path("kind").asText());
    }
  }

  @Test
  void captureCannotBypassPersonalCapacityAndLeavesRejectedReceipt() throws Exception {
    try (var s = new LeximeetService(directory, DesktopWorkspaceTest.CLOCK)) {
      s.seedCapacity(PersonalLibrary.ACTIVE_LIMIT);
      var auth = pair(s).path("authorization");
      var input = capture(custom(uuid()), uuid(), uuid());
      error("CONFLICT", rpc(s, "recordEncounter", input, auth));
      assertEquals(
          0,
          s.lmcp()
              .database
              .read(db -> Sql.first(db, "SELECT COUNT(*) AS n FROM lmcp_capture_events"))
              .path("n")
              .asInt());
      assertEquals(
          "rejected",
          success(
                  rpc(
                      s,
                      "getOperation",
                      object().set("mutationId", input.path("mutationId")),
                      auth))
              .path("status")
              .asText());
      assertEquals(PersonalLibrary.ACTIVE_LIMIT, s.snapshot().path("words").size());
    }
  }

  @Test
  void sameVersionWithObsoleteStructureIsRejectedWithoutDeletion() throws Exception {
    Path file = directory.resolve("leximeet.sqlite");
    try (var db = DriverManager.getConnection("jdbc:sqlite:" + file)) {
      Sql.execute(db, "CREATE TABLE obsolete(payload TEXT)");
      Sql.execute(db, "INSERT INTO obsolete VALUES('kept')");
      Sql.execute(db, "PRAGMA user_version=100");
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> new LeximeetService(directory, DesktopWorkspaceTest.CLOCK));
    try (var db = DriverManager.getConnection("jdbc:sqlite:" + file)) {
      assertEquals("kept", Sql.first(db, "SELECT payload FROM obsolete").path("payload").asText());
    }
  }
}
