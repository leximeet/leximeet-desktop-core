package app.leximeet.core;

// 启动边界的可公开错误；正文由程序常量组成，不接受异常原文、SQL、路径或个人内容。
final class StartupProblem extends IllegalArgumentException {
  private final String code;

  StartupProblem(String code, String message) {
    super(message);
    this.code = code;
  }

  String code() {
    return code;
  }
}
