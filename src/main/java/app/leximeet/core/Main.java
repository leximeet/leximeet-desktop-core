package app.leximeet.core;

import java.util.Map;
import java.util.concurrent.CountDownLatch;

// Java 子进程入口：stdout 只输出一次协议握手，诊断信息写 stderr。
public final class Main {
  private Main() {}

  public static void main(String[] arguments) {
    long mainStartedNanos = System.nanoTime();
    boolean optionsParsed = false;
    try {
      CoreOptions.requireJava21(Runtime.version().feature());
      CoreOptions options = CoreOptions.parse(arguments);
      optionsParsed = true;
      StartupTiming timing = new StartupTiming(mainStartedNanos);
      var context = CoreApplication.start(options, timing);
      CoreServer server = context.getBean(CoreServer.class);
      System.out.println(
          Json.MAPPER.writeValueAsString(
              Map.of(
                  "port",
                  server.port(),
                  "protocolVersion",
                  "1",
                  "dataDir",
                  options.dataDir().toString(),
                  "profile",
                  options.profile(),
                  "startup",
                  timing.snapshot())));
      System.out.flush();
      new CountDownLatch(1).await();
    } catch (Throwable error) {
      System.err.println(StartupDiagnostic.describe(error, optionsParsed));
      System.exit(1);
    }
  }
}
