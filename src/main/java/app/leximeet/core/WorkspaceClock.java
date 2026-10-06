package app.leximeet.core;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

// 事件时刻来自注入时钟，学习日时区来自工作区；修改计划后无需重启 Core 即可生效。
final class WorkspaceClock extends Clock {
  private final Database database;
  private final Clock source;

  WorkspaceClock(Database database, Clock source) {
    this.database = database;
    this.source = source;
  }

  @Override
  public ZoneId getZone() {
    try {
      return database.read(
          db ->
              ZoneId.of(
                  Sql.first(db, "SELECT study_zone FROM desktop_profile WHERE id=1")
                      .path("study_zone")
                      .asText()));
    } catch (Exception error) {
      throw new IllegalStateException("无法读取工作区学习时区", error);
    }
  }

  @Override
  public Clock withZone(ZoneId zone) {
    return source.withZone(zone);
  }

  @Override
  public Instant instant() {
    return source.instant();
  }
}
