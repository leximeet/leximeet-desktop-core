package app.leximeet.core;

import static app.leximeet.core.DesktopWorkspaceTest.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// 提醒偏好不改变学习规划和历史；旧间隔升级为设备随机策略的 30 分钟基准。
class DesktopReminderPreferencesTest {
  @TempDir Path directory;

  private ObjectNode preferences(JsonNode state) {
    ObjectNode input =
        Json.MAPPER
            .createObjectNode()
            .put("action", "saveReminderPreferences")
            .put("expectedRevision", state.path("profile").path("revision").asInt());
    input.putArray("reminderModes").add("meaning-choice").add("recall");
    return input;
  }

  @Test
  void preferencesAreAtomicAndDoNotEnableOrReplaceAPlan() throws Exception {
    var fixture = new DesktopWorkspaceTest();
    Path index = fixture.index(directory);
    try (var service = new LeximeetService(directory, CLOCK)) {
      fixture.mount(service, index);
      JsonNode before = service.desktop().state();
      assertEquals(30, before.path("profile").path("reminderInterval").asInt());
      assertFalse(before.path("profile").path("planEnabled").asBoolean());
      for (String modes : new String[] {"[]", "[\"copy\",\"copy\"]", "[\"unknown\"]"}) {
        var invalid = preferences(before);
        invalid.set("reminderModes", Json.MAPPER.readTree(modes));
        assertThrows(ApiException.class, () -> service.desktop().command(invalid));
        assertEquals(before, service.desktop().state());
      }
      JsonNode saved = service.desktop().command(preferences(before));
      var expected = ((ObjectNode) before.path("profile")).deepCopy();
      expected.put("revision", expected.path("revision").asInt() + 1);
      expected.set("reminderModes", preferences(before).path("reminderModes"));
      assertEquals(expected, saved.path("profile"));
      assertEquals(before.path("insights"), saved.path("insights"));
      assertThrows(ApiException.class, () -> service.desktop().command(preferences(before)));
      assertEquals(saved, service.desktop().state());
    }
    try (var reopened = new LeximeetService(directory, CLOCK)) {
      fixture.mount(reopened, index);
      assertEquals(
          Json.MAPPER.readTree("[\"meaning-choice\",\"recall\"]"),
          reopened.desktop().state().path("profile").path("reminderModes"));
      assertFalse(reopened.desktop().state().path("profile").path("planEnabled").asBoolean());
    }
  }
}
