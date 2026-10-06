package app.leximeet.core;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// 新资料空间教学恢复与个人资料隔离，不处理预发布旧流程。
class DesktopGuideTest {
  @TempDir Path directory;

  @Test
  void restartRestoresCurrentGuideAndResetKeepsPersonalFacts() throws Exception {
    try (var service = new LeximeetService(directory, DesktopWorkspaceTest.CLOCK)) {
      service
          .desktop()
          .command(
              Json.MAPPER
                  .createObjectNode()
                  .put("action", "capture")
                  .put("word", "alpha")
                  .put("note", "个人笔记")
                  .put("context", "alpha appears here."));
      service.desktop().command(Json.MAPPER.createObjectNode().put("action", "guideStart"));
      service
          .desktop()
          .command(
              Json.MAPPER.createObjectNode().put("action", "guideNext").put("step", "sidebar"));
    }
    try (var service = new LeximeetService(directory, DesktopWorkspaceTest.CLOCK)) {
      assertEquals(1, service.desktop().state().path("guide").path("completed").size());
      service.desktop().command(Json.MAPPER.createObjectNode().put("action", "guideReset"));
      assertEquals(0, service.desktop().state().path("guide").path("completed").size());
      assertEquals(1, service.snapshot().path("encounters").size());
    }
  }
}
