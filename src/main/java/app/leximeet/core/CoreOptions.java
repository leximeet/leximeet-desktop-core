package app.leximeet.core;

import java.nio.file.Path;
import java.util.Set;

// 启动参数先于容器与数据库验证，避免错误运行时或无效 profile 产生任何个人数据。
record CoreOptions(
    Path dataDir,
    String token,
    String profile,
    boolean seedDemo,
    int seedCapacity,
    int seedNoteLength,
    Path testClockFile) {
  CoreOptions(
      Path dataDir,
      String token,
      String profile,
      boolean seedDemo,
      int seedCapacity,
      int seedNoteLength) {
    this(dataDir, token, profile, seedDemo, seedCapacity, seedNoteLength, null);
  }

  CoreOptions(Path dataDir, String token, String profile, boolean seedDemo) {
    this(dataDir, token, profile, seedDemo, 0, 0);
  }

  static CoreOptions parse(String[] arguments) {
    Path dataDir = null;
    String token = null;
    String profile = "production";
    boolean seedDemo = false;
    int seedCapacity = 0;
    int seedNoteLength = 0;
    Path testClockFile = null;
    Set<String> seen = new java.util.HashSet<>();
    for (int index = 0; index < arguments.length; index++) {
      String argument = arguments[index];
      if (!seen.add(argument)) throw new IllegalArgumentException("启动参数不能重复：" + argument);
      if (argument.equals("--seed-demo")) {
        seedDemo = true;
        continue;
      }
      if (argument.equals("--seed-capacity")) {
        if (index + 1 >= arguments.length)
          throw new IllegalArgumentException(
              "有效参数：--data-dir <绝对目录> --token <随机令牌> [--profile test --seed-capacity <1-50000>]");
        try {
          seedCapacity = Integer.parseInt(arguments[++index]);
        } catch (NumberFormatException error) {
          throw new IllegalArgumentException("容量夹具词数必须是整数");
        }
        continue;
      }
      if (argument.equals("--seed-note-length")) {
        if (index + 1 >= arguments.length) throw new IllegalArgumentException("长笔记夹具必须指定长度");
        try {
          seedNoteLength = Integer.parseInt(arguments[++index]);
        } catch (NumberFormatException error) {
          throw new IllegalArgumentException("长笔记夹具长度必须是整数");
        }
        continue;
      }
      if (!Set.of("--data-dir", "--token", "--profile", "--test-clock-file").contains(argument)
          || index + 1 >= arguments.length)
        throw new IllegalArgumentException(
            "有效参数：--data-dir <绝对目录> --token <随机令牌> [--profile demo --seed-demo] [--profile test"
                + " --seed-capacity <1-50000>]");
      String value = arguments[++index];
      switch (argument) {
        case "--data-dir" -> dataDir = Path.of(value);
        case "--token" -> token = value;
        case "--profile" -> profile = value;
        case "--test-clock-file" -> testClockFile = Path.of(value);
        default -> throw new IllegalArgumentException("未知参数");
      }
    }
    if (dataDir == null || !dataDir.isAbsolute())
      throw new IllegalArgumentException("必须显式提供 --data-dir 绝对目录");
    if (token == null || !token.matches("[A-Za-z0-9_-]{32,256}"))
      throw new IllegalArgumentException("配对令牌必须是至少 32 字符的随机安全令牌");
    if (!Set.of("production", "development", "test", "demo").contains(profile))
      throw new IllegalArgumentException("profile 无效");
    if (seedDemo && !profile.equals("demo"))
      throw new IllegalArgumentException("示例导入只能用于 --profile demo");
    if (seedCapacity != 0 && (seedCapacity < 1 || seedCapacity > IndexCapacitySupport.MAX_WORDS))
      throw new IllegalArgumentException("容量夹具词数必须为 1–" + IndexCapacitySupport.MAX_WORDS);
    if (seedCapacity > 0 && !profile.equals("test"))
      throw new IllegalArgumentException("容量夹具只能用于 --profile test");
    if (seedNoteLength != 0
        && (seedNoteLength < 1
            || seedNoteLength > 8_000
            || seedCapacity < 1
            || seedCapacity > 10_000
            || !profile.equals("test")))
      throw new IllegalArgumentException("长笔记夹具只允许 test 资料的 1–10000 词与 1–8000 字");
    if (seedDemo && seedCapacity > 0) throw new IllegalArgumentException("示例导入与容量夹具不能同时启用");
    if (testClockFile != null
        && (!profile.equals("test")
            || !testClockFile.isAbsolute()
            || !testClockFile
                .normalize()
                .equals(dataDir.toAbsolutePath().normalize().resolve("test-clock.json"))))
      throw new IllegalArgumentException(
          "--test-clock-file 仅允许 test profile 当前资料目录的 test-clock.json");
    return new CoreOptions(
        dataDir.toAbsolutePath().normalize(),
        token,
        profile,
        seedDemo,
        seedCapacity,
        seedNoteLength,
        testClockFile);
  }

  static void requireJava21(int feature) {
    if (feature != 21)
      throw new StartupProblem(
          "JAVA_RUNTIME_MISMATCH",
          "词遇本地核心要求 JDK 21，当前 Java 主版本为 " + feature + "；请使用随包运行时或设置 JAVA_HOME 为 JDK 21");
  }
}
