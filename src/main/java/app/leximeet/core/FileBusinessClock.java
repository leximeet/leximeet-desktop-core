package app.leximeet.core;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

// 隔离 test 资料的可变业务时钟。只改变学习时间，邀请、会话、租约与网络期限仍用真实时间。
final class FileBusinessClock extends Clock {
  private final Path file;
  private final ZoneId zone;

  FileBusinessClock(Path dataDir, Path file) {
    this.file = file.toAbsolutePath().normalize();
    if (!this.file.equals(dataDir.toAbsolutePath().normalize().resolve("test-clock.json")))
      throw new IllegalArgumentException("测试时钟必须是当前资料目录内的 test-clock.json");
    zone = read().zone();
  }

  private record Value(Instant instant, ZoneId zone) {}

  private Value read() {
    try {
      if (Files.isSymbolicLink(file.getParent())
          || Files.isSymbolicLink(file)
          || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
        throw new IllegalArgumentException("测试时钟必须是普通文件，禁止符号链接");
      for (Path path : new Path[] {file.getParent(), file}) {
        try {
          for (PosixFilePermission permission :
              Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS))
            if (permission.name().startsWith("GROUP_") || permission.name().startsWith("OTHERS_"))
              throw new IllegalArgumentException("测试时钟目录和文件不能允许其他用户访问");
        } catch (UnsupportedOperationException ignored) {
          // Windows 由专属目录 ACL 限制。
        }
      }
      if (Files.size(file) > 1024) throw new IllegalArgumentException("测试时钟文件超过长度限制");
      ByteBuffer bytes = ByteBuffer.allocate(1025);
      try (FileChannel channel =
          FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
        while (channel.read(bytes) > 0)
          if (!bytes.hasRemaining()) throw new IllegalArgumentException("测试时钟文件超过长度限制");
      }
      bytes.flip();
      byte[] value = new byte[bytes.remaining()];
      bytes.get(value);
      var data = Json.MAPPER.readTree(value);
      Json.fields(data, "instant", "zone");
      return new Value(
          Instant.parse(Json.text(data, "instant", 80, true)),
          ZoneId.of(Json.text(data, "zone", 80, true)));
    } catch (Exception failure) {
      throw new IllegalStateException("隔离测试时钟无效，未回退到系统时间", failure);
    }
  }

  @Override
  public ZoneId getZone() {
    return zone;
  }

  @Override
  public Clock withZone(ZoneId requested) {
    return new Clock() {
      @Override
      public ZoneId getZone() {
        return requested;
      }

      @Override
      public Clock withZone(ZoneId other) {
        return FileBusinessClock.this.withZone(other);
      }

      @Override
      public Instant instant() {
        return FileBusinessClock.this.instant();
      }
    };
  }

  @Override
  public Instant instant() {
    Value value = read();
    if (!value.zone().equals(zone)) throw new IllegalStateException("测试期间不能修改工作区时区");
    return value.instant();
  }
}
