package app.leximeet.core;

import java.util.Set;

// 真实业务方法注册表；与冻结契约精确相等，再由实调测试逐一验证业务和响应。
final class LmcpRoutes {
  static final Set<String> METHODS =
      Set.of(
          "hello",
          "requestConnection",
          "getConnectionStatus",
          "pair",
          "resumeSession",
          "renewSession",
          "revokePairing",
          "disconnect",
          "getWorkspace",
          "matchWords",
          "getWord",
          "getPublicEntry",
          "recordEncounter",
          "getChanges",
          "getOperation",
          "openInDesktop",
          "listNotebooks",
          "listEncounters",
          "registerHost");

  private LmcpRoutes() {}
}
