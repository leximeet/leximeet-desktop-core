package app.leximeet.core;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Comparator;
import java.util.UUID;

// 大容量本机备份的生成阶段。先以 SQLite 的一致快照复制，再从副本中去除授权和设备许可。 原数据库从不因导出而改变；导出失败时仅回收本次创建的临时目录。
final class PortableBackup {
  // 文件备份独立于 JSON 边界；导出和导入共用上限，避免生成无法恢复的文件。
  static final long MAX_BYTES = 2L * 1024 * 1024 * 1024;

  private static final byte[] SQLITE_HEADER =
      "SQLite format 3\0".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

  private PortableBackup() {}

  static Archive create(Database source) throws Exception {
    Path directory = privateDirectory();
    try {
      Path file = directory.resolve("leximeet.sqlite");
      source.vacuumInto(file);
      sanitize(file);
      if (Files.size(file) > MAX_BYTES)
        throw new ApiException(413, "BACKUP_TOO_LARGE", "文件备份超过 2 GiB，不能生成无法恢复的备份");
      return new Archive(directory, file);
    } catch (Exception error) {
      deleteOwnedDirectory(directory);
      throw error;
    }
  }

  // 只接收有界字节流，临时目录由本次请求独占；失败时立即回收。
  static Archive receive(InputStream input) throws Exception {
    return receive(input, MAX_BYTES);
  }

  static Archive receive(InputStream input, long limit) throws Exception {
    Path directory = privateDirectory();
    try {
      Path file = directory.resolve("leximeet.sqlite");
      try (OutputStream output =
          Files.newOutputStream(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
        try {
          Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
          // Windows 继承私有目录 ACL。
        }
        byte[] buffer = new byte[64 * 1024];
        long written = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
          written += count;
          if (written > limit) throw new ApiException(413, "BODY_TOO_LARGE", "文件备份最多 2 GiB");
          output.write(buffer, 0, count);
        }
      }
      return new Archive(directory, file);
    } catch (Exception error) {
      deleteOwnedDirectory(directory);
      throw error;
    }
  }

  // 文件必须是本版 Core 生成的同构 SQLite，而不是任意 SQLite 数据库。 结构、约束与关键领域上限均在修改临时副本及原库之前检查。
  static void validate(Connection current, Path file) throws Exception {
    if (Files.isSymbolicLink(file) || !Files.isRegularFile(file) || Files.size(file) > MAX_BYTES)
      throw ApiException.badRequest("备份文件不存在、不是普通文件或超过 2 GiB");
    try (InputStream input = Files.newInputStream(file)) {
      if (!Arrays.equals(SQLITE_HEADER, input.readNBytes(SQLITE_HEADER.length)))
        throw ApiException.badRequest("文件不是 SQLite 备份");
    }
    try (Connection copy = DriverManager.getConnection("jdbc:sqlite:" + file)) {
      try (Statement sql = copy.createStatement()) {
        sql.execute("PRAGMA trusted_schema=OFF");
        sql.execute("PRAGMA query_only=ON");
        try (var version = sql.executeQuery("PRAGMA user_version")) {
          if (!version.next() || version.getInt(1) != DesktopSchema.VERSION)
            throw ApiException.badRequest("只接受当前 schema " + DesktopSchema.VERSION + " 的词遇文件备份");
        }
        try (var mode = sql.executeQuery("PRAGMA journal_mode")) {
          if (!mode.next() || !"delete".equalsIgnoreCase(mode.getString(1)))
            throw ApiException.badRequest("文件备份必须是独立的单文件 SQLite 快照");
        }
      }
      String schemaSql =
          "SELECT type,name,tbl_name,sql FROM sqlite_master "
              + "WHERE name NOT LIKE 'sqlite_%' ORDER BY type,name";
      if (!Sql.rows(current, schemaSql).equals(Sql.rows(copy, schemaSql)))
        throw ApiException.badRequest("文件结构与当前 Core 不一致");
      try (Statement sql = copy.createStatement()) {
        try (var check = sql.executeQuery("PRAGMA integrity_check")) {
          if (!check.next() || !"ok".equals(check.getString(1)) || check.next())
            throw ApiException.badRequest("备份文件完整性检查失败");
        }
        try (var foreignKeys = sql.executeQuery("PRAGMA foreign_key_check")) {
          if (foreignKeys.next()) throw ApiException.badRequest("备份文件包含无效关系");
        }
      }
      if (Sql.first(
                  copy,
                  "SELECT COUNT(*) AS count FROM words w LEFT JOIN desktop_word_links l ON"
                      + " l.word_id=w.id WHERE w.deleted_at IS NULL AND"
                      + " COALESCE(l.manual_active,1)=1")
              .path("count")
              .asLong()
          > PersonalLibrary.ACTIVE_LIMIT) throw ApiException.badRequest("备份中的活动词条超过我的词库上限");
      if (Sql.first(
                  copy,
                  "SELECT 1 AS invalid FROM words WHERE length(word)<1 OR length(word)>120 "
                      + "OR length(meaning)>3000 OR length(phonetic)>300 OR length(note)>20000 "
                      + "OR status NOT IN ('new','learning','mastered') LIMIT 1")
              != null
          || Sql.first(
                  copy,
                  "SELECT 1 AS invalid FROM encounters WHERE length(context)<1 "
                      + "OR length(context)>20000 OR length(source_title)>300 "
                      + "OR length(source_url)>2000 LIMIT 1")
              != null
          || Sql.first(
                  copy,
                  "SELECT 1 AS invalid FROM words w WHERE w.book_id IS NOT NULL "
                      + "AND NOT EXISTS (SELECT 1 FROM word_books wb WHERE wb.word_id=w.id "
                      + "AND wb.book_id=w.book_id) LIMIT 1")
              != null) throw ApiException.badRequest("备份包含超出当前领域边界的词条或语境");
      if (Sql.first(copy, "SELECT COUNT(*) AS count FROM settings WHERE id=1").path("count").asInt()
          != 1) throw ApiException.badRequest("备份缺少当前设置");
      DesktopReviews.verify(copy);
      try {
        ObjectNode settings =
            Json.object(
                Json.MAPPER.readTree(
                    Sql.first(copy, "SELECT payload FROM settings WHERE id=1")
                        .path("payload")
                        .asText()));
        SettingsSupport.requireBackupCoreFields(settings);
      } catch (ApiException error) {
        throw error;
      } catch (Exception error) {
        throw ApiException.badRequest("备份中的设置或采集规则无效");
      }
    } catch (ApiException error) {
      throw error;
    } catch (Exception error) {
      throw ApiException.badRequest("备份文件无法读取或完整性检查失败");
    }
  }

  private static Path privateDirectory() throws IOException {
    Path directory = Files.createTempDirectory("leximeet-backup-");
    try {
      Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
    } catch (UnsupportedOperationException ignored) {
      // Windows 继承临时目录 ACL。
    }
    return directory;
  }

  static void sanitize(Path file) throws Exception {
    try (var copy = DriverManager.getConnection("jdbc:sqlite:" + file)) {
      try (Statement sql = copy.createStatement()) {
        sql.execute("PRAGMA foreign_keys=ON");
        sql.execute("PRAGMA secure_delete=ON");
        sql.execute("PRAGMA journal_mode=DELETE");
      }
      copy.setAutoCommit(false);
      try {
        // 删除全部 LMCP 控制面与凭据，保留用户资料和已提交学习事件。
        LmcpSchema.clearAuthorization(copy);
        ObjectNode settings =
            Json.object(
                Json.MAPPER.readTree(
                    Sql.first(copy, "SELECT payload FROM settings WHERE id=1")
                        .path("payload")
                        .asText()));
        settings = SettingsSupport.normalize(settings);
        settings.put("translationEnabled", false);
        settings.put("developerMonitoringEnabled", false);
        settings.put("launchAtLogin", false);
        settings.put("clipboardCaptureEnabled", false);
        settings.put("globalShortcutEnabled", false);
        Sql.execute(
            copy,
            "UPDATE settings SET payload=? WHERE id=1",
            Json.MAPPER.writeValueAsString(settings));
        // 副本不沿用来源设备身份；恢复时还会重新生成一次代次。
        for (String key : new String[] {"libraryId", "profileId", "databaseGeneration"})
          Sql.execute(
              copy,
              "UPDATE personal_meta SET value=? WHERE key=?",
              UUID.randomUUID().toString(),
              key);
        copy.commit();
      } catch (Exception error) {
        copy.rollback();
        throw error;
      } finally {
        copy.setAutoCommit(true);
      }
      // 删除可能只标记空闲页；VACUUM 保证授权哈希不残留在可分发文件的空闲页中。
      try (Statement sql = copy.createStatement()) {
        sql.execute("VACUUM");
        try (var check = sql.executeQuery("PRAGMA integrity_check")) {
          if (!check.next() || !"ok".equals(check.getString(1)))
            throw new IOException("备份副本完整性检查失败");
        }
        try (var foreignKeys = sql.executeQuery("PRAGMA foreign_key_check")) {
          if (foreignKeys.next()) throw new IOException("备份副本存在无效引用");
        }
      }
    }
  }

  private static void deleteOwnedDirectory(Path directory) throws IOException {
    if (!Files.exists(directory)) return;
    try (var entries = Files.walk(directory)) {
      for (Path item : entries.sorted(Comparator.reverseOrder()).toList())
        Files.deleteIfExists(item);
    }
  }

  static final class Archive implements AutoCloseable {
    private final Path directory;
    private final Path file;

    private Archive(Path directory, Path file) {
      this.directory = directory;
      this.file = file;
    }

    Path file() {
      return file;
    }

    @Override
    public void close() throws IOException {
      deleteOwnedDirectory(directory);
    }
  }
}
