package app.leximeet.core;

import static app.leximeet.core.LmcpServiceTest.object;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// 正式首次结构与未发布同号试验结构分开；拒绝时不迁移、清空或改写资料文件。
class InitialReleaseDataShapeTest {
  @TempDir Path directory;

  @Test
  void oldSameNumberMeaningOverrideStructureIsRejectedWithOriginalDataUntouched() throws Exception {
    try (var service = new LeximeetService(directory, DesktopWorkspaceTest.CLOCK)) {
      CoreTestData.collect(service, object().put("word", "savedword").put("note", "原资料必须保留"));
    }
    Path file = directory.resolve("leximeet.sqlite");
    try (var db = DriverManager.getConnection("jdbc:sqlite:" + file)) {
      Sql.execute(db, "PRAGMA writable_schema=ON");
      assertTrue(
          Sql.first(db, "SELECT sql FROM sqlite_master WHERE name='words'")
              .path("sql")
              .asText()
              .contains("meaning TEXT NOT NULL DEFAULT ''"));
      Sql.execute(
          db,
          "UPDATE sqlite_master SET sql=replace(sql,?,?) WHERE name='words'",
          "meaning TEXT NOT NULL DEFAULT ''",
          "meaning TEXT NOT NULL");
      assertFalse(
          Sql.first(db, "SELECT sql FROM sqlite_master WHERE name='words'")
              .path("sql")
              .asText()
              .contains("meaning TEXT NOT NULL DEFAULT ''"));
      Sql.execute(db, "PRAGMA writable_schema=OFF");
    }
    byte[] original = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
    var rejected =
        assertThrows(
            StartupProblem.class, () -> new LeximeetService(directory, DesktopWorkspaceTest.CLOCK));
    assertEquals("DATA_STRUCTURE_MISMATCH", rejected.code());
    assertArrayEquals(
        original, MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    try (var db = DriverManager.getConnection("jdbc:sqlite:" + file)) {
      assertEquals(100, Sql.first(db, "PRAGMA user_version").path("user_version").asInt());
      assertEquals(
          "原资料必须保留",
          Sql.first(db, "SELECT note FROM words WHERE word='savedword'").path("note").asText());
    }
  }
}
