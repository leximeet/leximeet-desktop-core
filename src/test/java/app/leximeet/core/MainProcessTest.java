package app.leximeet.core;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// 验证真实 Java 子进程握手与重启协议，确保测试内存对象没有掩盖启动问题。
class MainProcessTest {
  @TempDir Path directory;
  private static final String FIRST_TOKEN = "first_0123456789abcdefghijklmnopqrstuvwxyz";
  private static final String NEXT_TOKEN = "next_0123456789abcdefghijklmnopqrstuvwxyz";

  private Process start(String... args) throws Exception {
    var command =
        new ArrayList<>(
            List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                System.getProperty("java.class.path"),
                Main.class.getName()));
    command.addAll(List.of(args));
    return new ProcessBuilder(command)
        .redirectError(directory.resolve("process-error.log").toFile())
        .start();
  }

  private JsonNode handshake(Process process) throws Exception {
    try (var reader = Executors.newSingleThreadExecutor()) {
      String line =
          reader
              .submit(
                  () ->
                      new BufferedReader(
                              new InputStreamReader(
                                  process.getInputStream(), StandardCharsets.UTF_8))
                          .readLine())
              .get(10, TimeUnit.SECONDS);
      assertNotNull(line, "子进程必须先报告启动握手");
      assertFalse(line.contains(FIRST_TOKEN));
      assertFalse(line.contains(NEXT_TOKEN));
      return Json.MAPPER.readTree(line);
    }
  }

  private static void stop(Process process) throws Exception {
    process.destroy();
    if (!process.waitFor(5, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      process.waitFor(5, TimeUnit.SECONDS);
    }
  }

  @Test
  void childProcessReportsPortRestartsWithPersistenceAndRotatedToken() throws Exception {
    String dataDir = directory.resolve("isolated-profile").toString();
    Process first = start("--data-dir", dataDir, "--token", FIRST_TOKEN, "--profile", "test");
    try (var client = HttpClient.newHttpClient()) {
      try {
        JsonNode ready = handshake(first);
        assertEquals("1", ready.path("protocolVersion").asText());
        assertEquals("test", ready.path("profile").asText());
        assertEquals("Spring Boot 3.5.16", ready.path("startup").path("framework").asText());
        assertTrue(ready.path("startup").path("javaVersion").asText().startsWith("21."));
        assertTrue(ready.path("startup").path("frameworkMs").asDouble() > 0);
        assertTrue(
            ready.path("startup").path("readyMs").asDouble()
                >= ready.path("startup").path("frameworkMs").asDouble());
        var request =
            HttpRequest.newBuilder(
                    URI.create(
                        "http://127.0.0.1:" + ready.path("port").asInt() + "/api/desktop/command"))
                .timeout(Duration.ofSeconds(3))
                .header("Authorization", "Bearer " + FIRST_TOKEN)
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        "{\"action\":\"collect\",\"word\":\"persist\",\"note\":\"持久化笔记\"}"))
                .build();
        assertEquals(200, client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
      } finally {
        stop(first);
      }
      Process restarted = start("--data-dir", dataDir, "--token", NEXT_TOKEN, "--profile", "test");
      try {
        int port = handshake(restarted).path("port").asInt();
        URI uri = URI.create("http://127.0.0.1:" + port + "/api/snapshot");
        assertEquals(
            401,
            client
                .send(
                    HttpRequest.newBuilder(uri)
                        .timeout(Duration.ofSeconds(3))
                        .header("Authorization", "Bearer " + FIRST_TOKEN)
                        .build(),
                    HttpResponse.BodyHandlers.ofString())
                .statusCode());
        var response =
            client.send(
                HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(3))
                    .header("Authorization", "Bearer " + NEXT_TOKEN)
                    .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals(
            "persist",
            Json.MAPPER.readTree(response.body()).path("words").get(0).path("word").asText());
      } finally {
        stop(restarted);
      }
    }
  }

  @Test
  void demoSeedRequiresExplicitDemoProfile() throws Exception {
    Process invalid =
        start(
            "--data-dir",
            directory.resolve("must-not-seed").toString(),
            "--token",
            FIRST_TOKEN,
            "--seed-demo");
    try {
      assertTrue(invalid.waitFor(5, TimeUnit.SECONDS));
      assertEquals(1, invalid.exitValue());
      assertFalse(java.nio.file.Files.exists(directory.resolve("must-not-seed/leximeet.sqlite")));
    } finally {
      stop(invalid);
    }
  }

  @Test
  void unsupportedSchemaAndSameVersionOldStructureAreRejectedWithoutReplacingFiles()
      throws Exception {
    for (int version : new int[] {13, 100, 0}) {
      Path profile = directory.resolve("unsupported-" + version);
      java.nio.file.Files.createDirectories(profile);
      Path file = profile.resolve("leximeet.sqlite");
      try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + file)) {
        Sql.execute(connection, "CREATE TABLE private_old_shape(marker TEXT)");
        Sql.execute(connection, "INSERT INTO private_old_shape VALUES('private-marker-content')");
        Sql.execute(connection, "PRAGMA user_version=" + version);
      }
      byte[] original = java.nio.file.Files.readAllBytes(file);
      assertStartupFailure(
          profile, version == 13 ? "UNSUPPORTED_DATA_SCHEMA" : "DATA_STRUCTURE_MISMATCH");
      assertArrayEquals(original, java.nio.file.Files.readAllBytes(file));
    }
  }

  @Test
  void corruptedFileProducesSafeLeafReasonAndIsNotDeleted() throws Exception {
    Path profile = directory.resolve("corrupt");
    java.nio.file.Files.createDirectories(profile);
    Path file = profile.resolve("leximeet.sqlite");
    byte[] privateBytes =
        "private-marker-content token SQL not a database".getBytes(StandardCharsets.UTF_8);
    java.nio.file.Files.write(file, privateBytes);
    assertStartupFailure(profile, "DATA_CORRUPTED");
    assertArrayEquals(privateBytes, java.nio.file.Files.readAllBytes(file));
  }

  private void assertStartupFailure(Path profile, String code) throws Exception {
    Process child =
        start("--data-dir", profile.toString(), "--token", FIRST_TOKEN, "--profile", "test");
    try {
      assertTrue(child.waitFor(10, TimeUnit.SECONDS));
      assertEquals(1, child.exitValue());
      assertEquals("", new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
      String diagnostic = java.nio.file.Files.readString(directory.resolve("process-error.log"));
      assertTrue(diagnostic.contains("[" + code + "]"), diagnostic);
      assertFalse(diagnostic.contains("BeanCreationException"));
      for (String secret :
          new String[] {
            FIRST_TOKEN, "private-marker-content", profile.toString(), "SELECT", "INSERT"
          }) assertFalse(diagnostic.contains(secret));
      assertEquals(1, diagnostic.lines().count());
    } finally {
      stop(child);
    }
  }
}
