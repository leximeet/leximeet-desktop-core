package app.leximeet.core;

import static app.leximeet.core.DesktopWorkspaceTest.*;
import static app.leximeet.core.LmcpServiceTest.object;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// 退休字段和命令明确拒绝；失败不能物化公共词、覆盖笔记或写入部分关系。
class DesktopPersonalBoundaryTest {
  @TempDir Path directory;

  @Test
  void rejectsRetiredTagCommandsAndFiltersWithoutChangingCurrentData() throws Exception {
    var fixture = new DesktopWorkspaceTest();
    fixture.directory = directory;
    try (var service = new LeximeetService(directory, CLOCK)) {
      fixture.mount(service, fixture.index(directory));
      var before = service.snapshot();
      for (String action : new String[] {"createTag", "deleteTag"})
        assertEquals(
            400,
            assertThrows(
                    ApiException.class,
                    () -> service.desktop().command(object().put("action", action)))
                .status());
      assertEquals(
          400,
          assertThrows(
                  ApiException.class, () -> service.desktop().query(object().put("scope", "tag")))
              .status());
      assertEquals(
          400,
          assertThrows(
                  ApiException.class,
                  () ->
                      service.desktop().query(object().put("scope", "library").put("tagId", ALPHA)))
              .status());
      assertEquals(before, service.snapshot());
    }
  }

  @Test
  void notebookDeletionKeepsSystemProtectionAndCannotBeRevivedByAnEdit() throws Exception {
    var fixture = new DesktopWorkspaceTest();
    fixture.directory = directory;
    try (var service = new LeximeetService(directory, CLOCK)) {
      fixture.mount(service, fixture.index(directory));
      var protectedDelete =
          object().put("action", "deleteBook").put("bookId", PersonalLibrary.LIBRARY_ID);
      assertEquals(
          "FORBIDDEN",
          assertThrows(ApiException.class, () -> service.desktop().command(protectedDelete))
              .code());
      String book = fixture.createRelation(service, "createBook", "普通词本", "books");
      var edit =
          object()
              .put("action", "saveNote")
              .put("wordId", ALPHA)
              .put("note", "保留笔记")
              .put("expectedRevision", 0);
      edit.putArray("bookIds").add(book);
      service.desktop().command(edit);
      var deletion = object().put("action", "deleteBook").put("bookId", book);
      service.desktop().command(deletion);
      assertEquals(
          "NOT_FOUND",
          assertThrows(ApiException.class, () -> service.desktop().command(deletion)).code());
      var before = service.snapshot();
      edit.put("expectedRevision", fixture.wordDetail(service, ALPHA).path("revision").asInt())
          .put("note", "不能复活旧词本");
      assertEquals(
          404, assertThrows(ApiException.class, () -> service.desktop().command(edit)).status());
      assertEquals(before, service.snapshot());
      assertEquals("保留笔记", fixture.wordDetail(service, ALPHA).path("note").asText());
      assertTrue(fixture.wordDetail(service, ALPHA).path("books").isEmpty());
    }
  }

  @Test
  void personalEditOnlyAcceptsNotesAndBooksAndRejectsTagFieldEvenWhenEmpty() throws Exception {
    var fixture = new DesktopWorkspaceTest();
    fixture.directory = directory;
    try (var service = new LeximeetService(directory, CLOCK)) {
      fixture.mount(service, fixture.index(directory));
      var draft =
          object()
              .put("action", "saveNote")
              .put("wordId", ALPHA)
              .put("note", "失败不得写入")
              .put("expectedRevision", 0);
      for (String field : new String[] {"tagIds", "meaning", "bookId"}) {
        var invalid = draft.deepCopy();
        if (field.equals("tagIds")) invalid.putArray(field);
        else invalid.put(field, "旧编辑内容");
        var before = service.snapshot();
        assertEquals(
            400,
            assertThrows(ApiException.class, () -> service.desktop().command(invalid)).status());
        assertEquals(before, service.snapshot());
      }
      var invalidCapture =
          object()
              .put("action", "capture")
              .put("word", "newword")
              .put("context", "A newword appears.");
      invalidCapture.putArray("tagIds");
      assertEquals(
          400,
          assertThrows(ApiException.class, () -> service.desktop().command(invalidCapture))
              .status());
      assertTrue(service.snapshot().path("words").isEmpty());
      draft.put("note", "正式笔记").putArray("bookIds");
      service.desktop().command(draft);
      assertEquals("正式笔记", fixture.wordDetail(service, ALPHA).path("note").asText());
    }
  }
}
