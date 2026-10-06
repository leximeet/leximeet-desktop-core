package app.leximeet.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.UUID;

// 本地领域用例。界面只提交意图；词条、语境、计划和反馈的关联由此处统一校验。 Clock 可替换，因此跨日、提前练习和重启测试无需修改系统时间。
public final class LeximeetService implements AutoCloseable {
  private final Database database;
  private final Clock clock;
  private final RuntimeDiagnostics diagnostics;
  private final LmcpService lmcp;
  private final boolean ownsDatabase;
  private final DesktopWorkspace desktop;
  private static final String WORD_COLUMNS =
      "id, word, meaning, phonetic, note, book_id AS bookId, status, revision, created_at AS"
          + " createdAt, updated_at AS updatedAt, deleted_at AS deletedAt";
  private static final String ENCOUNTER_COLUMNS =
      "id, word_id AS wordId, context, source_title AS sourceTitle, source_url AS sourceUrl,"
          + " created_at AS createdAt, is_demo AS isDemo";

  public LeximeetService(java.nio.file.Path directory, Clock clock) throws Exception {
    this(
        new Database(directory),
        clock,
        new RuntimeDiagnostics(new StartupTiming(System.nanoTime())),
        true);
  }

  LeximeetService(Database database, Clock clock, RuntimeDiagnostics diagnostics) throws Exception {
    this(database, clock, diagnostics, false);
  }

  LeximeetService(
      Database database, Clock clock, Clock connectionClock, RuntimeDiagnostics diagnostics)
      throws Exception {
    this(database, clock, connectionClock, diagnostics, false);
  }

  private LeximeetService(
      Database database, Clock clock, RuntimeDiagnostics diagnostics, boolean ownsDatabase)
      throws Exception {
    this(database, clock, clock, diagnostics, ownsDatabase);
  }

  private LeximeetService(
      Database database,
      Clock clock,
      Clock connectionClock,
      RuntimeDiagnostics diagnostics,
      boolean ownsDatabase)
      throws Exception {
    this.database = database;
    this.clock = clock;
    this.diagnostics = diagnostics;
    this.ownsDatabase = ownsDatabase;
    this.desktop = new DesktopWorkspace(database, clock);
    this.lmcp = new LmcpService(database, connectionClock, clock, desktop);
    try {
      database.transaction(
          db -> {
            PersonalLibrary.seed(db, now());
            return null;
          });
      diagnostics.setEnabled(
          database.read(db -> settings(db).path("developerMonitoringEnabled").asBoolean()));
    } catch (Exception error) {
      if (ownsDatabase) database.close();
      throw error;
    }
  }

  RuntimeDiagnostics diagnostics() {
    return diagnostics;
  }

  LmcpService lmcp() {
    return lmcp;
  }

  DesktopWorkspace desktop() {
    return desktop;
  }

  public ObjectNode snapshot() throws Exception {
    return database.read(this::snapshot);
  }

  public String today() {
    return LocalDate.now(clock).toString();
  }

  private String now() {
    return clock.instant().toString();
  }

  private static String newId() {
    return UUID.randomUUID().toString();
  }

  private ObjectNode snapshot(Connection db) throws Exception {
    ObjectNode output = Json.MAPPER.createObjectNode();
    output.put("protocolVersion", "1");
    output.put("today", today());
    ArrayNode words =
        rows(db, "SELECT " + WORD_COLUMNS + " FROM words ORDER BY created_at DESC, id");
    words.forEach(word -> ((ObjectNode) word).put("trashed", !word.path("deletedAt").isNull()));
    output.set("words", words);
    // 桌面端需按权威角色区分不可删除的系统词本，不能猜测名称或随机 ID。
    output.set(
        "books",
        rows(
            db,
            "SELECT b.id,b.name,b.color,b.created_at AS createdAt,r.role FROM books b LEFT JOIN"
                + " book_roles r ON r.book_id=b.id ORDER BY b.created_at,b.id"));
    output.set(
        "encounters",
        rows(
            db,
            "SELECT "
                + ENCOUNTER_COLUMNS
                + " FROM encounters WHERE id NOT IN (SELECT encounter_id FROM encounter_details"
                + " WHERE undone_at IS NOT NULL) ORDER BY created_at DESC, id"));
    output.set("settings", settings(db));
    PersonalBackup.snapshotExtras(db, output);
    return output;
  }

  public synchronized ObjectNode updateSettings(JsonNode input) throws Exception {
    SettingsSupport.validate(input);
    ObjectNode result =
        database.transaction(
            db -> {
              ObjectNode settings = settings(db);
              var beforePolicy = CapturePolicy.preferences(db).json();
              input.properties().forEach(field -> settings.set(field.getKey(), field.getValue()));
              execute(
                  db,
                  "UPDATE settings SET payload=? WHERE id=1",
                  Json.MAPPER.writeValueAsString(settings));
              if (!beforePolicy.equals(CapturePolicy.preferences(db).json()))
                LmcpService.bump(db, "revision");
              return snapshot(db);
            });
    // 只在事务提交后改变监控开关；设置与导入串行，避免并发开关回写乱序。
    diagnostics.setEnabled(result.path("settings").path("developerMonitoringEnabled").asBoolean());
    return result;
  }

  // 展示偏好独立提交，避免系统级设置的补偿写入覆盖另一窗口的词卡草稿。
  public synchronized ObjectNode updateCardLayout(JsonNode input) throws Exception {
    return database.transaction(
        db -> {
          ObjectNode settings = settings(db);
          settings.set("cardLayout", CardLayoutSupport.update(input, settings.path("cardLayout")));
          execute(
              db,
              "UPDATE settings SET payload=? WHERE id=1",
              Json.MAPPER.writeValueAsString(settings));
          return snapshot(db);
        });
  }

  public ObjectNode settingsView() throws Exception {
    return database.read(
        db -> {
          ObjectNode output = Json.MAPPER.createObjectNode();
          output.set("settings", settings(db));
          return output;
        });
  }

  // 仅隔离 test 资料；既有词条保持原状，不读取正式用户库。
  public void seedCapacity(int count) throws Exception {
    seedCapacity(count, 0);
  }

  // 长笔记夹具只由已验证的 test 启动参数传入，正式资料没有此写入入口。
  public void seedCapacity(int count, int noteLength) throws Exception {
    database.transaction(
        db -> {
          if (first(db, "SELECT id FROM words LIMIT 1") != null) return null;
          IndexCapacitySupport.seed(db, count, noteLength);
          return null;
        });
  }

  private ObjectNode settings(Connection db) throws Exception {
    ObjectNode stored =
        (ObjectNode)
            Json.MAPPER.readTree(
                first(db, "SELECT payload FROM settings WHERE id=1").path("payload").asText());
    return SettingsSupport.normalize(stored);
  }

  // JSON 与文件备份都携带同一完整 SQLite 快照，避免另一套行模型漏掉学习事实。
  public ObjectNode exportBackup() throws Exception {
    try (PortableBackup.Archive archive = exportPortableBackup()) {
      return JsonBackup.encode(archive.file(), now());
    }
  }

  // 大容量文件备份由 Core 生成并持有；调用方发送完成后必须关闭临时文件。
  PortableBackup.Archive exportPortableBackup() throws Exception {
    return PortableBackup.create(database);
  }

  // 恢复是用户明确确认后的完整替换；去授权及结构检查在写入本机资料前完成。
  public synchronized ObjectNode restorePortableBackup(Path file) throws Exception {
    ObjectNode result = database.restoreFrom(file);
    diagnostics.setEnabled(false);
    return result;
  }

  // 只接受当前格式，不转换旧开发备份；共用文件恢复的完整性校验和单事务替换。
  public synchronized ObjectNode importBackup(JsonNode input) throws Exception {
    try (PortableBackup.Archive archive = JsonBackup.decode(input)) {
      restorePortableBackup(archive.file());
      return snapshot();
    }
  }

  // 仅由明确的 --seed-demo 启用；既有数据空间保持原状。
  public void seedDemo() throws Exception {
    database.transaction(
        db -> {
          if (first(db, "SELECT id FROM words LIMIT 1") != null) return null;
          String book = newId();
          execute(
              db,
              "INSERT INTO books(id,name,color,created_at) VALUES(?,?,?,?)",
              book,
              "日常阅读 · 示例",
              "#6C8F7D",
              now());
          String[][] examples = {
            {
              "serendipity",
              "n. 不期而遇的美好；意外发现珍宝的幸运",
              "/ˌserənˈdɪpəti/",
              "A small moment of serendipity can change the direction of a day."
            },
            {
              "resilience",
              "n. 韧性；从困难中恢复的能力",
              "/rɪˈzɪliəns/",
              "Resilience grows when we take one small step after another."
            },
            {
              "wander",
              "v. 漫步；随意行走",
              "/ˈwɒndə/",
              "We wander through the quiet streets and notice the morning light."
            },
            {
              "subtle",
              "adj. 微妙的；不易察觉的",
              "/ˈsʌtl/",
              "The author makes a subtle distinction between knowing and understanding."
            },
            {
              "embrace",
              "v. 拥抱；欣然接受",
              "/ɪmˈbreɪs/",
              "Embrace the unfamiliar and let curiosity lead the way."
            },
            {
              "deliberate",
              "adj. 深思熟虑的；从容的",
              "/dɪˈlɪbərət/",
              "A deliberate pause gives us time to choose our next words."
            }
          };
          for (String[] values : examples) {
            // 只有固定、原创的演示数据；不复用已退休的外部词条 CRUD 与旧编辑模型。
            String id = newId();
            execute(
                db,
                "INSERT INTO words(id,word,normalized,meaning,phonetic,book_id,created_at,updated_at)"
                    + " VALUES(?,?,?,?,?,?,?,?)",
                id,
                values[0],
                PublicLexicon.normalize(values[0]),
                values[1],
                values[2],
                book,
                now(),
                now());
            execute(db, "INSERT INTO desktop_word_links VALUES(?,NULL,1)", id);
            execute(
                db,
                "INSERT INTO encounters(id,word_id,context,source_title,source_url,created_at,is_demo)"
                    + " VALUES(?,?,?,?,?,?,1)",
                newId(),
                id,
                values[3],
                "词遇原创示例 · 非真实采集",
                "",
                now());
          }
          return null;
        });
  }

  private static void execute(Connection db, String sql, Object... arguments) throws Exception {
    try (PreparedStatement statement = db.prepareStatement(sql)) {
      bind(statement, arguments);
      statement.executeUpdate();
    }
  }

  private static void bind(PreparedStatement statement, Object[] arguments) throws Exception {
    for (int index = 0; index < arguments.length; index++)
      statement.setObject(index + 1, arguments[index]);
  }

  private static ObjectNode first(Connection db, String sql, Object... arguments) throws Exception {
    ArrayNode rows = rows(db, sql, arguments);
    return rows.isEmpty() ? null : (ObjectNode) rows.get(0);
  }

  private static ArrayNode rows(Connection db, String sql, Object... arguments) throws Exception {
    ArrayNode rows = Json.MAPPER.createArrayNode();
    try (PreparedStatement statement = db.prepareStatement(sql)) {
      bind(statement, arguments);
      try (ResultSet result = statement.executeQuery()) {
        while (result.next()) {
          var values = new LinkedHashMap<String, Object>();
          for (int index = 1; index <= result.getMetaData().getColumnCount(); index++) {
            String name = result.getMetaData().getColumnLabel(index);
            Object value = result.getObject(index);
            if (Set.of("isDemo").contains(name)) value = result.getBoolean(index);
            values.put(name, value);
          }
          rows.add(Json.MAPPER.valueToTree(values));
        }
      }
    }
    return rows;
  }

  @Override
  public void close() throws Exception {
    if (ownsDatabase) database.close();
  }
}
