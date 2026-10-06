package app.leximeet.core;

import static app.leximeet.core.DesktopWorkspaceTest.*;
import static app.leximeet.core.LmcpServiceTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// 仅测试应用业务时间；系统钟、连接鉴权和真实网络期限不受跨日场景影响。
class FileBusinessClockTest {
  @TempDir Path directory;

  void setTime(Path profile, String instant, String zone) throws Exception {
    Files.createDirectories(profile);
    securePosixPermissions(profile, "rwx------");
    Path staged = profile.resolve("test-clock.next");
    Files.writeString(staged, object().put("instant", instant).put("zone", zone).toString());
    securePosixPermissions(staged, "rw-------");
    Files.move(
        staged,
        profile.resolve("test-clock.json"),
        StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING);
  }

  // Windows 沿用 JUnit 专属临时目录的 ACL；POSIX 文件系统显式收紧目录和文件权限。
  void securePosixPermissions(Path path, String permissions) throws Exception {
    if (Files.getFileStore(path).supportsFileAttributeView("posix"))
      Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions));
  }

  @Test
  void advancingOwnedFileChangesOnlyBusinessTimeWithoutRestart() throws Exception {
    Path profile = directory.resolve("profile");
    setTime(profile, "2026-10-01T00:00:00Z", "Asia/Shanghai");
    var clock = new FileBusinessClock(profile, profile.resolve("test-clock.json"));
    var f = new DesktopWorkspaceTest();
    f.directory = profile;
    try (var database = new Database(profile);
        var s =
            new LeximeetService(
                database,
                clock,
                CLOCK,
                new RuntimeDiagnostics(new StartupTiming(System.nanoTime())))) {
      f.mount(s, f.index(profile));
      f.goal(s, "dictionary");
      var paired = pair(s);
      var auth = paired.path("authorization");
      String expiry = paired.path("expiresAt").asText();
      assertEquals(CLOCK.instant().plusSeconds(86400), Instant.parse(expiry));
      assertEquals("2026-10-01", s.desktop().state().path("today").asText());
      setTime(profile, "2026-10-08T00:00:00Z", "Asia/Shanghai");
      assertEquals(Instant.parse("2026-10-08T00:00:00Z"), clock.instant());
      assertEquals("2026-10-08", s.desktop().state().path("today").asText());
      var workspace = success(rpc(s, "getWorkspace", object(), auth));
      assertEquals(
          CLOCK.instant().plusSeconds(30),
          Instant.parse(workspace.path("readLeaseUntil").asText()));
      assertEquals(
          expiry,
          s.lmcp()
              .database
              .read(
                  db ->
                      Sql.first(
                          db,
                          "SELECT expires_at FROM lmcp_sessions WHERE session_id=?",
                          auth.path("sessionId").asText()))
              .path("expires_at")
              .asText());
      assertEquals(
          "connected",
          success(
                  rpc(
                      s,
                      "getConnectionStatus",
                      object()
                          .put("clientInstanceId", CLIENT)
                          .set("pairingCredential", paired.path("pairingCredential")),
                      null))
              .path("connectionState")
              .asText());
    }
  }

  @Test
  void malformedTimeZoneAndExternalPathNeverFallbackToSystemTime() throws Exception {
    Path profile = directory.resolve("profile");
    setTime(profile, "2026-10-01T00:00:00Z", "Asia/Shanghai");
    var file = profile.resolve("test-clock.json");
    var clock = new FileBusinessClock(profile, file);
    Files.writeString(file, "{}");
    assertThrows(IllegalStateException.class, clock::instant);
    setTime(profile, "2026-10-01T00:00:00Z", "UTC");
    assertThrows(IllegalStateException.class, clock::instant);
    assertThrows(
        IllegalArgumentException.class,
        () -> new FileBusinessClock(profile, directory.resolve("external.json")));
  }

  @Test
  void publiclyReadablePosixClockIsRejected() throws Exception {
    assumeTrue(
        Files.getFileStore(directory).supportsFileAttributeView("posix"),
        "文件系统使用 ACL，不支持 POSIX 权限位");
    Path profile = directory.resolve("profile");
    setTime(profile, "2026-10-01T00:00:00Z", "UTC");
    var file = profile.resolve("test-clock.json");
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
    assertThrows(IllegalStateException.class, () -> new FileBusinessClock(profile, file));
  }

  // 三平台都校验符号链接拒绝；不因权限位模型不同而跳过路径保护。
  @Test
  void symbolicClockIsRejected() throws Exception {
    Path profile = directory.resolve("profile");
    setTime(profile, "2026-10-01T00:00:00Z", "UTC");
    var file = profile.resolve("test-clock.json");
    Files.delete(file);
    Files.createSymbolicLink(file, directory.resolve("external.json"));
    assertThrows(IllegalStateException.class, () -> new FileBusinessClock(profile, file));
  }

  @Test
  void productionArgumentsRejectMutableClockBeforeAnyDatabaseCreation() {
    Path profile = directory.resolve("profile"), file = profile.resolve("test-clock.json");
    String[] args = {
      "--data-dir",
      profile.toString(),
      "--token",
      "a".repeat(32),
      "--test-clock-file",
      file.toString()
    };
    assertThrows(IllegalArgumentException.class, () -> CoreOptions.parse(args));
    assertFalse(Files.exists(profile));
    var valid =
        CoreOptions.parse(
            new String[] {
              "--data-dir",
              profile.toString(),
              "--token",
              "a".repeat(32),
              "--profile",
              "test",
              "--test-clock-file",
              file.toString()
            });
    assertEquals(file, valid.testClockFile());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CoreConfiguration()
                .clock(new CoreOptions(profile, "a".repeat(32), "production", false, 0, 0, file)));
  }
}
