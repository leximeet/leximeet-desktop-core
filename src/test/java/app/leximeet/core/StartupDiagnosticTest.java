package app.leximeet.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;

// 包装异常只暴露可操作分类；不把底层 SQL、任意路径和个人正文带入 UI 或日志。
class StartupDiagnosticTest {
  @Test
  void springWrapperPreservesKnownClassificationAndOmitsArbitraryMessages() {
    var root = new StartupProblem("UNSUPPORTED_DATA_SCHEMA", "只接受当前资料版本；原文件已保留");
    var wrapper =
        new BeanCreationException(
            "private-bean", "secret-token /private/home note SELECT password", root);
    String result = StartupDiagnostic.describe(wrapper, true);
    assertTrue(result.contains("[UNSUPPORTED_DATA_SCHEMA]"));
    assertTrue(result.contains("原因类型：StartupProblem"));
    for (String privateText :
        new String[] {"private-bean", "secret-token", "/private/home", "SELECT", "password"})
      assertFalse(result.contains(privateText));
  }

  @Test
  void unclassifiedLeafUsesItsTypeAndSafeGenericText() {
    String result =
        StartupDiagnostic.describe(
            new BeanCreationException("private-name", new IllegalStateException("private-note")),
            true);
    assertTrue(result.contains("[INITIALIZATION_FAILED]"));
    assertTrue(result.contains("原因类型：IllegalStateException"));
    assertFalse(result.contains("private"));
    assertTrue(
        StartupDiagnostic.describe(new IllegalArgumentException("secret-token"), false)
            .contains("[INVALID_STARTUP_OPTIONS]"));
  }

  @Test
  void sqliteCorruptionAndStorageFullHaveSeparateSafeAdvice() {
    String corrupt =
        StartupDiagnostic.describe(
            new SQLiteException("private database bytes", SQLiteErrorCode.SQLITE_NOTADB), true);
    assertTrue(corrupt.contains("[DATA_CORRUPTED]"));
    assertTrue(corrupt.contains("原文件已保留"));
    String full =
        StartupDiagnostic.describe(
            new SQLiteException("private directory", SQLiteErrorCode.SQLITE_FULL), true);
    assertTrue(full.contains("[STORAGE_FULL]"));
    assertFalse(full.contains("private"));
  }

  @Test
  void cyclicCauseChainTerminates() {
    var first = new IllegalStateException("private first");
    var second = new IllegalArgumentException("private second", first);
    first.initCause(second);
    String result = StartupDiagnostic.describe(first, true);
    assertTrue(result.contains("[INITIALIZATION_FAILED]"));
    assertFalse(result.contains("private"));
  }
}
