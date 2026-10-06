package app.leximeet.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

// 连接控制与业务资料分离：发现不授权，邀请需插件确认，明确断开可跨重启查证。
final class LmcpConnections {
  private static final String INVITATION_PREFIX = "invitation:", STATE_PREFIX = "connection:";
  private static final int MAX_CLIENTS = 100;
  private final LmcpService service;
  // 发现只在进程中保留；重启不能把以前在线的客户端当作当前可操作端口。
  private final LinkedHashMap<String, ObjectNode> discovered = new LinkedHashMap<>();

  LmcpConnections(LmcpService service) {
    this.service = service;
  }

  void observe(String origin, String connection, JsonNode params) {
    prune();
    String key = key(origin, params.path("clientInstanceId").asText());
    if (!discovered.containsKey(key) && discovered.size() >= MAX_CLIENTS)
      throw LmcpService.error("DISCOVERY_LIMIT", "发现的客户端过多");
    discovered.put(
        key,
        Json.MAPPER
            .createObjectNode()
            .put("clientInstanceId", params.path("clientInstanceId").asText())
            .put("displayName", params.path("displayName").asText())
            .put("origin", origin)
            .put("connectionId", connection)
            .put("lastSeenAt", service.now()));
  }

  void closed(String connection) {
    discovered.values().removeIf(row -> row.path("connectionId").asText().equals(connection));
  }

  private void prune() {
    Instant cutoff = service.clock.instant().minusSeconds(60);
    discovered
        .values()
        .removeIf(row -> !Instant.parse(row.path("lastSeenAt").asText()).isAfter(cutoff));
  }

  private static String key(String origin, String client) {
    return origin + "/" + client;
  }

  ObjectNode requireClient(String origin, String client, String connection) {
    prune();
    var row = discovered.get(key(origin, client));
    if (row == null || !row.path("connectionId").asText().equals(connection))
      throw LmcpService.error("STALE_CONNECTION", "请在当前端口重新发现客户端");
    row.put("lastSeenAt", service.now());
    return row;
  }

  ObjectNode desktopTarget(String client, String origin) {
    prune();
    ObjectNode found = null;
    for (var row : discovered.values()) {
      if (!row.path("clientInstanceId").asText().equals(client)
          || (origin != null && !row.path("origin").asText().equals(origin))) continue;
      if (found != null) throw LmcpService.error("AMBIGUOUS_CLIENT", "请选择明确的客户端来源");
      found = row;
    }
    if (found == null) throw LmcpService.error("CLIENT_NOT_DISCOVERED", "客户端尚未发现或已经离线");
    return found;
  }

  ObjectNode request(Connection db, String origin, String client, String connection, String by)
      throws Exception {
    var target = requireClient(origin, client, connection);
    service.ensureInvitationSecret(db);
    var pairing =
        Sql.first(db, "SELECT * FROM lmcp_pairings WHERE origin=? AND client_id=?", origin, client);
    if (pairing != null && state(db, pairing).equals("connected"))
      throw LmcpService.error("CONNECTION_ACTIVE", "客户端已连接，请先明确断开");
    var old = invitation(db, origin, client);
    if (old != null && invitationState(old).equals("pending")) return old;
    long count =
        Sql.first(db, "SELECT COUNT(*) AS total FROM lmcp_meta WHERE key LIKE 'invitation:%'")
            .path("total")
            .asLong();
    if (old == null && count >= MAX_CLIENTS)
      throw LmcpService.error("DISCOVERY_LIMIT", "连接邀请数量达到上限");
    var row =
        Json.MAPPER
            .createObjectNode()
            .put("invitationId", LmcpService.uuid())
            .put("clientInstanceId", client)
            .put("displayName", target.path("displayName").asText())
            .put("origin", origin)
            .put("requestedBy", by)
            .put("state", "pending")
            .put("expiresAt", LmcpService.timestamp(service.clock.instant().plusSeconds(120)));
    row.put("tokenHash", LmcpContract.hash(invitationToken(db, row)));
    saveInvitation(db, row);
    return row;
  }

  ObjectNode invitation(Connection db, String origin, String client) throws Exception {
    var row =
        Sql.first(
            db, "SELECT value FROM lmcp_meta WHERE key=?", INVITATION_PREFIX + key(origin, client));
    return row == null ? null : (ObjectNode) Json.MAPPER.readTree(row.path("value").asText());
  }

  ObjectNode byId(Connection db, String id) throws Exception {
    var row =
        Sql.first(
            db,
            "SELECT value FROM lmcp_meta WHERE key LIKE 'invitation:%' AND"
                + " json_extract(value,'$.invitationId')=?",
            id);
    return row == null ? null : (ObjectNode) Json.MAPPER.readTree(row.path("value").asText());
  }

  private void saveInvitation(Connection db, ObjectNode row) throws Exception {
    Sql.execute(
        db,
        "INSERT INTO lmcp_meta VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value",
        INVITATION_PREFIX + key(row.path("origin").asText(), row.path("clientInstanceId").asText()),
        row.toString());
  }

  String invitationState(JsonNode row) {
    if (row == null) return "none";
    if (row.path("state").asText().equals("pending")
        && !Instant.parse(row.path("expiresAt").asText()).isAfter(service.clock.instant()))
      return "expired";
    return row.path("state").asText();
  }

  // 全局签名密钥受本机用户目录保护；每张票据只存哈希，不能从公开邀请 ID 推出票据。
  private String invitationToken(Connection db, JsonNode row) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(
        new SecretKeySpec(
            Base64.getUrlDecoder().decode(LmcpService.meta(db, "invitationSecret")), "HmacSHA256"));
    String message =
        "lmcp.invitation/1:"
            + LmcpService.meta(db, "desktopInstanceId")
            + ":"
            + row.path("invitationId").asText()
            + ":"
            + row.path("origin").asText()
            + ":"
            + row.path("clientInstanceId").asText();
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
  }

  ObjectNode validatePair(Connection db, JsonNode params, String origin, String connection)
      throws Exception {
    String client = params.path("clientInstanceId").asText();
    requireClient(origin, client, connection);
    var row = invitation(db, origin, client);
    if (row == null
        || !row.path("invitationId").asText().equals(params.path("invitationId").asText())
        || !LmcpService.secureEquals(
            row.path("tokenHash").asText(),
            LmcpContract.hash(params.path("invitationToken").asText())))
      throw LmcpService.error("INVITATION_INVALID", "邀请不属于当前客户端或票据无效");
    switch (invitationState(row)) {
      case "expired" -> throw LmcpService.error("INVITATION_EXPIRED", "邀请已过期且未接受");
      case "cancelled" -> throw LmcpService.error("INVITATION_CANCELLED", "邀请已取消且未接受");
      default -> {
        return row;
      }
    }
  }

  void accepted(Connection db, ObjectNode row, ObjectNode pairing) throws Exception {
    row.put("state", "accepted")
        .put("pairingId", pairing.path("pairing_id").asText())
        .put("authorizationEpoch", pairing.path("epoch").asText());
    saveInvitation(db, row);
    setState(db, pairing.path("pairing_id").asText(), "connected");
  }

  void cancel(Connection db, ObjectNode row) throws Exception {
    if (row == null) throw LmcpService.error("INVITATION_INVALID", "未找到邀请");
    if (invitationState(row).equals("accepted"))
      throw LmcpService.error("INVITATION_ALREADY_ACCEPTED", "邀请已经接受，请使用断开连接");
    if (invitationState(row).equals("pending")) {
      row.put("state", "cancelled");
      saveInvitation(db, row);
    }
  }

  void setState(Connection db, String pairing, String state) throws Exception {
    Sql.execute(
        db,
        "INSERT INTO lmcp_meta VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value",
        STATE_PREFIX + pairing,
        state);
  }

  String state(Connection db, JsonNode pairing) throws Exception {
    if (pairing.path("revoked").asBoolean()) return "revoked";
    var marker =
        Sql.first(
            db,
            "SELECT value FROM lmcp_meta WHERE key=?",
            STATE_PREFIX + pairing.path("pairing_id").asText());
    return marker == null ? "connected" : marker.path("value").asText();
  }

  ObjectNode status(Connection db, JsonNode params, String origin, String connection)
      throws Exception {
    String client = params.path("clientInstanceId").asText();
    requireClient(origin, client, connection);
    var out =
        Json.MAPPER
            .createObjectNode()
            .put("desktopInstanceId", LmcpService.meta(db, "desktopInstanceId"))
            .put("displayName", "词遇 Desktop")
            .put("connectionState", "unpaired");
    if (params.has("pairingCredential")) {
      var credential = params.path("pairingCredential");
      var pairing =
          Sql.first(
              db,
              "SELECT * FROM lmcp_pairings WHERE pairing_id=?",
              credential.path("pairingId").asText());
      if (pairing == null
          || !pairing.path("origin").asText().equals(origin)
          || !pairing.path("client_id").asText().equals(client)
          || !credential.path("clientInstanceId").asText().equals(client)
          || !LmcpService.secureEquals(
              pairing.path("token_hash").asText(),
              LmcpContract.hash(credential.path("pairingToken").asText())))
        throw LmcpService.error("PAIRING_REVOKED", "配对控制凭据无效");
      for (String field : new String[] {"desktopInstanceId", "workspaceId", "generation"})
        if (!LmcpService.meta(db, field).equals(credential.path(field).asText()))
          throw LmcpService.error("GENERATION_MISMATCH", "配对控制归属已变化");
      if (!pairing.path("revoked").asBoolean()
          && !pairing.path("epoch").asText().equals(credential.path("authorizationEpoch").asText()))
        throw LmcpService.error("PAIRING_REVOKED", "配对授权代次已变化");
      out.put("connectionState", state(db, pairing));
    }
    var row = invitation(db, origin, client);
    if (params.has("invitationId")
        && (row == null
            || !row.path("invitationId").asText().equals(params.path("invitationId").asText())))
      row = null;
    String inviteState = invitationState(row);
    out.put("invitationState", inviteState).putNull("invitation");
    if (inviteState.equals("pending"))
      out.set("invitation", summary(row).put("invitationToken", invitationToken(db, row)));
    return out;
  }

  private ObjectNode summary(JsonNode row) {
    return Json.MAPPER
        .createObjectNode()
        .put("invitationId", row.path("invitationId").asText())
        .put("requestedBy", row.path("requestedBy").asText())
        .put("expiresAt", row.path("expiresAt").asText());
  }

  void appendManageState(Connection db, ObjectNode out) throws Exception {
    var clients =
        Sql.rows(
            db,
            "SELECT pairing_id AS pairingId,client_id AS clientInstanceId,display_name AS"
                + " displayName,origin,epoch AS authorizationEpoch,revoked,created_at AS createdAt"
                + " FROM lmcp_pairings ORDER BY created_at");
    for (JsonNode client : clients) {
      var pairing =
          Sql.first(
              db,
              "SELECT * FROM lmcp_pairings WHERE pairing_id=?",
              client.path("pairingId").asText());
      ((ObjectNode) client).put("connectionState", state(db, pairing)).put("mode", "connected");
    }
    out.set("clients", clients);
    prune();
    var list = out.putArray("discoveredClients");
    for (var target : discovered.values()) {
      var item = target.deepCopy();
      var row =
          invitation(db, target.path("origin").asText(), target.path("clientInstanceId").asText());
      item.putNull("invitation");
      if (row != null) item.set("invitation", summary(row).put("state", invitationState(row)));
      list.add(item);
    }
  }
}
