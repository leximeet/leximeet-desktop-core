package app.leximeet.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;

// 仅供 Electron Main 使用的本机网关。Renderer 通过白名单 IPC 访问，不能直接拿令牌。 无跨域响应头；拒绝浏览器 Origin、非回环 Host 和超限请求。
public final class CoreServer implements AutoCloseable {
  public static final int MAX_BODY_BYTES = 2 * 1024 * 1024;

  // 备份使用独立上限；LMCP、普通写入和词表接口继续遵守原有 2 MiB 边界。
  public static final int MAX_BACKUP_BYTES = 64 * 1024 * 1024;

  public static final long MAX_ARCHIVE_BYTES = PortableBackup.MAX_BYTES;
  private final HttpServer server;
  private final LeximeetService service;
  private final RuntimeDiagnostics diagnostics;
  private final boolean ownsService;
  private final byte[] expectedAuthorization;
  private final ThreadPoolExecutor executor;
  private long rateWindow = System.nanoTime();
  private int requestCount;

  public CoreServer(java.nio.file.Path dataDir, String token, Clock clock, boolean seedDemo)
      throws Exception {
    this(ownedService(dataDir, token, clock), token, seedDemo, true);
  }

  CoreServer(LeximeetService service, String token, boolean seedDemo) throws Exception {
    this(service, token, seedDemo, false);
  }

  private static LeximeetService ownedService(java.nio.file.Path dataDir, String token, Clock clock)
      throws Exception {
    if (token == null || !token.matches("[A-Za-z0-9_-]{32,256}"))
      throw new IllegalArgumentException("配对令牌必须是至少 32 字符的随机安全令牌");
    return new LeximeetService(dataDir, clock);
  }

  private CoreServer(LeximeetService service, String token, boolean seedDemo, boolean ownsService)
      throws Exception {
    if (token == null || !token.matches("[A-Za-z0-9_-]{32,256}"))
      throw new IllegalArgumentException("配对令牌必须是至少 32 字符的随机安全令牌");
    this.service = service;
    this.diagnostics = service.diagnostics();
    this.ownsService = ownsService;
    // JDK HttpServer 的请求与响应时间上限，防止慢速本机客户端占满工作线程。
    System.setProperty("sun.net.httpserver.maxReqTime", "300");
    System.setProperty("sun.net.httpserver.maxRspTime", "300");
    System.setProperty("sun.net.httpserver.idleInterval", "10");
    System.setProperty("jdk.httpserver.maxConnections", "32");
    System.setProperty("sun.net.httpserver.maxReqHeaders", "64");
    try {
      if (seedDemo) service.seedDemo();
      expectedAuthorization = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 32);
      executor =
          new ThreadPoolExecutor(
              2,
              4,
              30,
              TimeUnit.SECONDS,
              new ArrayBlockingQueue<>(32),
              new ThreadPoolExecutor.CallerRunsPolicy());
      server.setExecutor(executor);
      server.createContext("/", this::handle);
    } catch (Exception error) {
      if (ownsService) service.close();
      throw error;
    }
  }

  public void start() {
    server.start();
  }

  public int port() {
    return server.getAddress().getPort();
  }

  private void handle(HttpExchange exchange) throws IOException {
    var sample = diagnostics.begin(exchange.getRequestURI().getRawPath());
    int status = 200;
    try {
      secure(exchange);
      if (exchange.getRequestURI().getRawPath().equals("/api/export-archive")) {
        method(exchange.getRequestMethod(), "GET");
        sendArchive(exchange);
        return;
      }
      if (exchange.getRequestURI().getRawPath().equals("/api/restore-archive")) {
        method(exchange.getRequestMethod(), "POST");
        send(exchange, 200, restoreArchive(exchange));
        return;
      }
      JsonNode result = dispatch(exchange);
      send(exchange, 200, result);
    } catch (ApiException error) {
      status = error.status();
      send(
          exchange,
          error.status(),
          Map.of("error", Map.of("code", error.code(), "message", error.getMessage())));
    } catch (JsonProcessingException error) {
      status = 400;
      send(
          exchange,
          400,
          Map.of("error", Map.of("code", "INVALID_JSON", "message", "JSON 格式无效、字段重复或嵌套过深")));
    } catch (Exception error) {
      if (storageFull(error)) {
        status = 507;
        // 只按 SQLite 错误码识别容量不足；不把 SQL、路径或私人内容回显到界面。
        send(
            exchange,
            507,
            Map.of("error", Map.of("code", "STORAGE_FULL", "message", "本机存储空间不足，操作未完成；请腾出空间后重试")));
        return;
      }
      status = 500;
      // 只记录异常类型；个人正文、SQLite SQL、文件路径与令牌不进入 HTTP 错误。
      System.err.println("[core] " + error.getClass().getSimpleName());
      send(
          exchange,
          500,
          Map.of("error", Map.of("code", "INTERNAL_ERROR", "message", "本机核心未完成操作，请查看日志或重试")));
    } finally {
      diagnostics.finish(
          sample, exchange.getRequestMethod(), exchange.getRequestURI().getRawPath(), status);
      exchange.close();
    }
  }

  private static boolean storageFull(Throwable error) {
    for (Throwable cause = error; cause != null; cause = cause.getCause())
      if (cause instanceof SQLiteException sqlite
          && sqlite.getResultCode() == SQLiteErrorCode.SQLITE_FULL) return true;
    return false;
  }

  private void secure(HttpExchange exchange) {
    if (!exchange.getRemoteAddress().getAddress().isLoopbackAddress())
      throw new ApiException(403, "FORBIDDEN", "仅允许本机调用");
    // 主进程原生 HTTP 客户端不发送 Origin。包括 null/file/web origin 在内的一律拒绝。
    if (exchange.getRequestHeaders().containsKey("Origin"))
      throw new ApiException(403, "ORIGIN_REJECTED", "浏览器不能直接连接本机核心");
    String host = exchange.getRequestHeaders().getFirst("Host");
    if (!("127.0.0.1:" + port()).equals(host))
      throw new ApiException(403, "HOST_REJECTED", "本机 Host 校验失败");
    var authorization = exchange.getRequestHeaders().get("Authorization");
    String supplied =
        authorization != null && authorization.size() == 1 ? authorization.get(0) : "";
    if (!MessageDigest.isEqual(expectedAuthorization, supplied.getBytes(StandardCharsets.UTF_8)))
      throw new ApiException(401, "UNAUTHORIZED", "配对令牌缺失或已失效");
    if (!allowRequest()) throw new ApiException(429, "RATE_LIMITED", "请求过于频繁，请稍后再试");
    if (exchange.getRequestURI().getRawQuery() != null) throw ApiException.badRequest("接口不接受查询参数");
    String length = exchange.getRequestHeaders().getFirst("Content-Length");
    if (length != null) {
      try {
        if (Long.parseLong(length) > bodyLimit(exchange)) throw tooLarge(exchange);
      } catch (NumberFormatException error) {
        throw ApiException.badRequest("Content-Length 无效");
      }
    }
  }

  private synchronized boolean allowRequest() {
    long current = System.nanoTime();
    if (current - rateWindow >= TimeUnit.SECONDS.toNanos(1)) {
      rateWindow = current;
      requestCount = 0;
    }
    return ++requestCount <= 200;
  }

  private JsonNode dispatch(HttpExchange exchange) throws Exception {
    String path = exchange.getRequestURI().getRawPath();
    String method = exchange.getRequestMethod();
    if (path.equals("/health")) {
      method(method, "GET");
      return Json.MAPPER.valueToTree(Map.of("ok", true, "protocolVersion", "1"));
    }
    if (path.equals("/api/lmcp/rpc")) {
      method(method, "POST");
      return service.lmcp().rpc(body(exchange));
    }
    if (path.equals("/api/lmcp/manage")) {
      method(method, "POST");
      return service.lmcp().manage(body(exchange));
    }
    if (path.equals("/api/snapshot")) {
      method(method, "GET");
      return service.snapshot();
    }
    if (path.equals("/api/desktop/state")) {
      method(method, "GET");
      return service.desktop().state();
    }
    if (path.equals("/api/desktop/query")) {
      method(method, "POST");
      return service.desktop().query(body(exchange));
    }
    if (path.equals("/api/desktop/command")) {
      method(method, "POST");
      return service.desktop().command(body(exchange));
    }
    if (path.equals("/api/desktop/mount")) {
      method(method, "POST");
      return service.desktop().mount(body(exchange));
    }
    if (path.equals("/api/desktop/guide-native")) {
      method(method, "POST");
      return service.desktop().nativeEvent(body(exchange));
    }
    if (path.equals("/api/diagnostics")) {
      method(method, "GET");
      return diagnostics.snapshot();
    }
    if (path.equals("/api/export")) {
      method(method, "GET");
      return service.exportBackup();
    }
    if (path.equals("/api/import")) {
      method(method, "POST");
      return service.importBackup(body(exchange));
    }
    if (path.equals("/api/settings")) {
      if (method.equals("GET")) return service.settingsView();
      method(method, "PATCH", "POST");
      return service.updateSettings(body(exchange));
    }
    if (path.equals("/api/card-layout")) {
      method(method, "PUT");
      return service.updateCardLayout(body(exchange));
    }
    throw ApiException.notFound("接口不存在");
  }

  private static void method(String actual, String... allowed) {
    if (!Set.of(allowed).contains(actual))
      throw new ApiException(405, "METHOD_NOT_ALLOWED", "此接口不支持该方法");
  }

  private static JsonNode body(HttpExchange exchange) throws IOException {
    String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
    if (contentType == null
        || !contentType
            .toLowerCase(java.util.Locale.ROOT)
            .split(";")[0]
            .strip()
            .equals("application/json"))
      throw new ApiException(415, "UNSUPPORTED_MEDIA_TYPE", "请求必须使用 application/json");
    if (exchange.getRequestHeaders().containsKey("Content-Encoding"))
      throw ApiException.badRequest("不接受压缩请求正文");
    int limit = (int) bodyLimit(exchange);
    byte[] data = exchange.getRequestBody().readNBytes(limit + 1);
    if (data.length > limit) throw tooLarge(exchange);
    return Json.object(
        exchange.getRequestURI().getRawPath().equals("/api/import")
            ? JsonBackup.MAPPER.readTree(data)
            : Json.MAPPER.readTree(data));
  }

  private static long bodyLimit(HttpExchange exchange) {
    if (exchange.getRequestMethod().equals("POST")) {
      if (exchange.getRequestURI().getRawPath().equals("/api/restore-archive"))
        return MAX_ARCHIVE_BYTES;
      if (exchange.getRequestURI().getRawPath().equals("/api/import")) return MAX_BACKUP_BYTES;
    }
    return MAX_BODY_BYTES;
  }

  private static ApiException tooLarge(HttpExchange exchange) {
    long limit = bodyLimit(exchange);
    return new ApiException(
        413,
        "BODY_TOO_LARGE",
        limit == MAX_ARCHIVE_BYTES
            ? "文件备份最多 2 GiB"
            : limit == MAX_BACKUP_BYTES ? "备份正文最多 64 MiB" : "请求正文最多 2 MiB");
  }

  // 私有令牌之外仍检查媒体类型，文件只落入请求独占的临时目录。
  private JsonNode restoreArchive(HttpExchange exchange) throws Exception {
    String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
    if (contentType == null
        || !contentType
            .toLowerCase(java.util.Locale.ROOT)
            .split(";")[0]
            .strip()
            .equals("application/vnd.sqlite3"))
      throw new ApiException(415, "UNSUPPORTED_MEDIA_TYPE", "文件备份必须使用 application/vnd.sqlite3");
    if (exchange.getRequestHeaders().containsKey("Content-Encoding"))
      throw ApiException.badRequest("不接受压缩的文件备份");
    try (PortableBackup.Archive archive = PortableBackup.receive(exchange.getRequestBody())) {
      return service.restorePortableBackup(archive.file());
    }
  }

  private static void send(HttpExchange exchange, int status, Object output) throws IOException {
    byte[] bytes = Json.MAPPER.writeValueAsBytes(output);
    // 保证导出的文件不会因体积超过导入边界而无法恢复。
    if (exchange.getRequestURI().getRawPath().equals("/api/export")
        && bytes.length > MAX_BACKUP_BYTES)
      throw new ApiException(413, "BACKUP_TOO_LARGE", "JSON 备份超过 64 MiB，请使用 SQLite 文件备份");
    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
    exchange.getResponseHeaders().set("Cache-Control", "no-store");
    exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    // JDK 25 的底层响应带缓冲；先刷新再 close，避免拒绝超限正文时
    // close 等待排空未发送的请求输入，使 401 / 413 等响应一直留在缓冲中。
    exchange.getResponseBody().flush();
  }

  // 响应体直接从临时 SQLite 副本传输，既不占用普通 JSON 的 64 MiB 边界，也不暴露路径。
  private void sendArchive(HttpExchange exchange) throws Exception {
    try (PortableBackup.Archive archive = service.exportPortableBackup()) {
      exchange.getResponseHeaders().set("Content-Type", "application/vnd.sqlite3");
      exchange
          .getResponseHeaders()
          .set("Content-Disposition", "attachment; filename=leximeet-backup.sqlite");
      exchange.getResponseHeaders().set("Cache-Control", "no-store");
      exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
      exchange.sendResponseHeaders(200, Files.size(archive.file()));
      Files.copy(archive.file(), exchange.getResponseBody());
      exchange.getResponseBody().flush();
    }
  }

  @Override
  public void close() throws Exception {
    server.stop(0);
    executor.shutdownNow();
    if (ownsService) service.close();
  }
}
