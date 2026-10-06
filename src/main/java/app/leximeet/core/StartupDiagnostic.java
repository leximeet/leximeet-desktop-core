package app.leximeet.core;

import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.util.Collections;
import java.util.IdentityHashMap;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;

// 解开 Spring 装配异常，但只输出固定分类和最底层类型；任意异常 message 都不直接回显。
final class StartupDiagnostic {
  private StartupDiagnostic() {}

  static String describe(Throwable error, boolean optionsParsed) {
    var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
    Throwable root = error;
    StartupProblem known = null;
    String code = optionsParsed ? "INITIALIZATION_FAILED" : "INVALID_STARTUP_OPTIONS";
    String message = optionsParsed ? "本机核心初始化失败，请检查应用与资料是否属于同一版本" : "启动参数无效，请使用应用提供的启动方式";
    for (Throwable cause = error;
        cause != null && seen.size() < 32 && seen.add(cause);
        cause = cause.getCause()) {
      root = cause;
      if (cause instanceof StartupProblem problem) known = problem;
      if (cause instanceof AccessDeniedException) {
        code = "DATA_ACCESS_DENIED";
        message = "无法访问本机资料目录，请检查目录权限";
      } else if (cause instanceof FileAlreadyExistsException) {
        code = "DATA_DIRECTORY_INVALID";
        message = "本机资料位置不是可用目录，请选择独立资料目录";
      } else if (cause instanceof SQLiteException sqlite) {
        SQLiteErrorCode result = sqlite.getResultCode();
        if (result == SQLiteErrorCode.SQLITE_NOTADB || result == SQLiteErrorCode.SQLITE_CORRUPT) {
          code = "DATA_CORRUPTED";
          message = "本机资料文件不是有效数据库或已损坏；原文件已保留，请从已验证的备份恢复";
        } else if (result == SQLiteErrorCode.SQLITE_FULL) {
          code = "STORAGE_FULL";
          message = "本机存储空间不足，请腾出空间后重试";
        } else if (result == SQLiteErrorCode.SQLITE_CANTOPEN
            || result == SQLiteErrorCode.SQLITE_PERM) {
          code = "DATA_ACCESS_DENIED";
          message = "无法打开本机资料文件，请检查目录权限";
        } else if (result == SQLiteErrorCode.SQLITE_BUSY
            || result == SQLiteErrorCode.SQLITE_LOCKED) {
          code = "DATA_IN_USE";
          message = "本机资料正被其他进程使用，请关闭同一资料空间的另一应用后重试";
        }
      }
    }
    if (known != null) {
      code = known.code();
      message = known.getMessage();
    }
    String type = root.getClass().getSimpleName();
    if (!type.matches("[A-Za-z_$][A-Za-z0-9_$]{0,79}")) type = "UnknownFailure";
    return "Core 启动失败：[" + code + "] " + message + "（原因类型：" + type + "）";
  }
}
