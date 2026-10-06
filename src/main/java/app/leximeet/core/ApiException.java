package app.leximeet.core;

// 可安全返回给界面的领域错误；内部异常不会把 SQL、磁盘路径或令牌发送给页面。
public final class ApiException extends RuntimeException {
  private final int status;
  private final String code;

  public ApiException(int status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
  }

  public int status() {
    return status;
  }

  public String code() {
    return code;
  }

  public static ApiException badRequest(String message) {
    return new ApiException(400, "INVALID_INPUT", message);
  }

  public static ApiException notFound(String message) {
    return new ApiException(404, "NOT_FOUND", message);
  }

  public static ApiException conflict(String message) {
    return new ApiException(409, "CONFLICT", message);
  }
}
