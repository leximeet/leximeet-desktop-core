package app.leximeet.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.Locale;

// 只读公共词典适配器。只接受 Main 放在本资料空间的索引，不接受 Renderer 的文件路径。
final class PublicLexicon {
  private final Database database;
  private boolean mounted;
  private ObjectNode activeMetadata;

  PublicLexicon(Database database) {
    this.database = database;
  }

  ObjectNode mount(JsonNode input) throws Exception {
    Json.fields(input, "file", "edition", "version", "manifestSha", "entryCount");
    Path directory = database.dataDir().resolve("dictionaries").toAbsolutePath().normalize();
    Path file = Path.of(Json.text(input, "file", 4096, true)).toAbsolutePath().normalize();
    if (!file.startsWith(directory)
        || Files.isSymbolicLink(file)
        || !Files.isRegularFile(file)
        || !file.toRealPath().startsWith(directory.toRealPath()))
      throw ApiException.badRequest("词典索引必须在受控词典目录中");
    String edition = Json.choice(input, "edition", "core-text", "core-text", "full-text");
    String sha = Json.text(input, "manifestSha", 64, true);
    if (!sha.matches("[a-f0-9]{64}")) throw ApiException.badRequest("词典清单校验和无效");
    return database.read(
        db -> {
          if (mounted) {
            Sql.execute(db, "DETACH DATABASE lexicon");
            mounted = false;
          }
          try {
            Sql.execute(db, "ATTACH DATABASE ? AS lexicon", file.toUri() + "?mode=ro");
            var info = Sql.first(db, "SELECT payload FROM lexicon.metadata WHERE id=1");
            ObjectNode metadata = Json.object(Json.MAPPER.readTree(info.path("payload").asText()));
            if (!metadata.path("manifestSha").asText().equals(sha)
                || !metadata.path("edition").asText().equals(edition)
                || !metadata.path("version").asText().equals(input.path("version").asText())
                || metadata.path("entryCount").asLong() != input.path("entryCount").asLong()
                || Sql.first(db, "SELECT COUNT(*) AS n FROM lexicon.entries").path("n").asLong()
                    != metadata.path("entryCount").asLong())
              throw ApiException.badRequest("词典索引与发布清单不一致");
            mounted = true;
            activeMetadata = metadata;
            database.transaction(
                personal -> {
                  Sql.execute(
                      personal,
                      "INSERT INTO desktop_dictionary VALUES(1,?) ON CONFLICT(id) DO UPDATE SET"
                          + " payload=excluded.payload",
                      metadata.toString());
                  // 公共资源到来后只关联身份，不改写旧手动 ID、释义或笔记。
                  Sql.execute(
                      personal,
                      "INSERT OR IGNORE INTO desktop_word_links(word_id,manual_active) SELECT"
                          + " id,CASE WHEN deleted_at IS NULL THEN 1 ELSE 0 END FROM words");
                  Sql.execute(
                      personal,
                      "UPDATE desktop_word_links SET entry_id=(SELECT e.id FROM lexicon.entries e"
                          + " JOIN words w ON w.id=desktop_word_links.word_id WHERE"
                          + " e.normalized=w.normalized ORDER BY CASE WHEN e.headword=w.word THEN 0"
                          + " ELSE 1 END,e.position LIMIT 1) WHERE entry_id IS NULL AND NOT"
                          + " EXISTS(SELECT 1 FROM desktop_word_identity i WHERE"
                          + " i.word_id=desktop_word_links.word_id AND"
                          + " json_extract(i.payload,'$.kind')='custom')");
                  return null;
                });
            return metadata;
          } catch (Exception error) {
            try {
              Sql.execute(db, "DETACH DATABASE lexicon");
            } catch (Exception ignored) {
            }
            mounted = false;
            activeMetadata = null;
            throw error;
          }
        });
  }

  boolean mounted() {
    return mounted;
  }

  void require() {
    if (!mounted) throw ApiException.conflict("公共词典正在准备，请稍后重试");
  }

  ObjectNode metadata(Connection db) throws Exception {
    if (mounted && activeMetadata != null) return activeMetadata.deepCopy().put("ready", true);
    ObjectNode row = Sql.first(db, "SELECT payload FROM desktop_dictionary WHERE id=1");
    return row == null
        ? Json.MAPPER.createObjectNode().put("ready", false)
        : Json.object(Json.MAPPER.readTree(row.path("payload").asText())).put("ready", mounted);
  }

  ArrayNode catalogs(Connection db) throws Exception {
    if (!mounted) return Json.MAPPER.createArrayNode();
    ArrayNode rows = Sql.rows(db, "SELECT payload FROM lexicon.catalogs ORDER BY category,title");
    ArrayNode result = Json.MAPPER.createArrayNode();
    for (JsonNode row : rows) result.add(Json.MAPPER.readTree(row.path("payload").asText()));
    return result;
  }

  ObjectNode entry(Connection db, String idOrWord) throws Exception {
    if (!mounted) return null;
    try (var query =
        db.prepareStatement(
            "SELECT payload FROM lexicon.entries WHERE id=? OR normalized=? ORDER BY CASE WHEN id=?"
                + " THEN 0 WHEN headword=? THEN 1 ELSE 2 END,position LIMIT 1")) {
      query.setString(1, idOrWord);
      query.setString(2, normalize(idOrWord));
      query.setString(3, idOrWord);
      query.setString(4, idOrWord);
      try (var row = query.executeQuery()) {
        if (!row.next()) return null;
        Object payload = row.getObject(1);
        if (payload instanceof String text) return Json.object(Json.MAPPER.readTree(text));
        try (var input =
            new java.util.zip.InflaterInputStream(
                new java.io.ByteArrayInputStream(row.getBytes(1)),
                new java.util.zip.Inflater(true))) {
          byte[] bytes = input.readNBytes(1_000_001);
          if (bytes.length > 1_000_000) throw ApiException.badRequest("词典词条正文过大");
          return Json.object(Json.MAPPER.readTree(bytes));
        }
      }
    }
  }

  static String normalize(String text) {
    return java.text.Normalizer.normalize(text.strip(), java.text.Normalizer.Form.NFKC)
        .toLowerCase(Locale.ROOT);
  }
}
