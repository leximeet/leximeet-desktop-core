package app.leximeet.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;

// 本机连接边界：Native 提供可信来源，Core 独占授权、资料事务和持久业务回执。
final class LmcpService {
  static final int MAX_FRAME_BYTES = 262144;
  static final List<String> SCOPES =
      List.of("library:read", "capture:write", "navigation:open", "host:register");
  static final List<String> CAPABILITIES =
      List.of(
          "lmcp.connection/1",
          "desktop.reading/1",
          "desktop.capture/1",
          "desktop.navigation/1",
          "desktop.notebooks/1",
          "desktop.encounters/1",
          "host.registration/1");
  final Database database;
  final Clock clock;
  final Clock businessClock;
  final LmcpContract contract;
  private final SecureRandom random = new SecureRandom();
  private final LmcpReading reading;
  private final DesktopDataModel model;
  private final LmcpConnections connections;

  record Caller(
      String pairingId,
      String clientId,
      String origin,
      String connectionId,
      ObjectNode pairing,
      String expiresAt) {}

  LmcpService(Database database, Clock clock, DesktopWorkspace desktop) throws Exception {
    this(database, clock, clock, desktop);
  }

  LmcpService(Database database, Clock clock, Clock businessClock, DesktopWorkspace desktop)
      throws Exception {
    this.database = database;
    this.clock = clock;
    this.businessClock = businessClock;
    contract = new LmcpContract();
    database.transaction(
        db -> {
          Sql.execute(db, "UPDATE lmcp_sessions SET revoked=1");
          ensureInvitationSecret(db);
          Sql.execute(db, "UPDATE lmcp_host_grants SET revoked=1");
          return null;
        });
    connections = new LmcpConnections(this);
    model = new DesktopDataModel(this, desktop);
    reading = new LmcpReading(this, model);
  }

  void ensureInvitationSecret(Connection db) throws Exception {
    Sql.execute(
        db,
        "INSERT INTO lmcp_meta VALUES('invitationSecret',?) ON CONFLICT(key) DO NOTHING",
        token());
  }

  void checkEventTime(String timestamp) {
    if (Instant.parse(timestamp).isAfter(businessClock.instant().plusSeconds(300)))
      throw error("CLOCK_SKEW", "事件时间超前超过五分钟");
  }

  ObjectNode rpc(JsonNode input) {
    JsonNode envelope = input.path("envelope");
    ObjectNode response =
        Json.MAPPER
            .createObjectNode()
            .put("apiVersion", contract.metadata.path("apiVersion").asText());
    for (String key : List.of("requestId", "connectionId", "method"))
      response.set(key, envelope.path(key));
    try {
      Json.fields(input, "envelope", "extensionOrigin", "connectionId");
      if (Json.MAPPER.writeValueAsBytes(envelope).length > MAX_FRAME_BYTES)
        throw error("PAYLOAD_TOO_LARGE", "请求超过 256 KiB");
      contract.request(envelope);
      String origin = Json.text(input, "extensionOrigin", 200, true);
      if (!origin.matches("chrome-extension://[a-p]{32}/?"))
        throw error("ORIGIN_REJECTED", "宿主来源无效");
      origin = origin.replaceAll("/$", "");
      String connection = input.path("connectionId").asText(),
          method = envelope.path("method").asText();
      if (!connection.equals(envelope.path("connectionId").asText()))
        throw error("STALE_CONNECTION", "请求不属于当前连接");

      String trustedOrigin = origin;
      // 校验响应也在事务中完成；不能先提交资料再发现结果不符合结构。
      JsonNode result =
          database.transaction(
              db -> {
                JsonNode output = dispatch(db, envelope, trustedOrigin, connection);
                if (output.has("_businessRejection")) return output;
                contract.result(method, output);
                if (Json.MAPPER.writeValueAsBytes(output).length > MAX_FRAME_BYTES - 512)
                  throw error("PAYLOAD_TOO_LARGE", "响应超出帧预算");
                return output;
              });
      if (result.has("_businessRejection"))
        response.put("ok", false).set("error", result.path("_businessRejection"));
      else response.put("ok", true).set("result", result);
    } catch (ApiException failure) {
      response.remove("result");
      response.put("ok", false).set("error", failure(failure));
    } catch (Exception failure) {
      System.err.println("[lmcp] " + failure.getClass().getSimpleName());
      response.remove("result");
      response.put("ok", false).set("error", failure(error("INTERNAL_ERROR", "本机操作未完成")));
    }
    return response;
  }

  private JsonNode dispatch(Connection db, JsonNode envelope, String origin, String connection)
      throws Exception {
    String method = envelope.path("method").asText();
    JsonNode params = envelope.path("params");
    if (method.equals("hello")) return hello(db, params, origin, connection);
    if (method.equals("requestConnection")) {
      String client = params.path("clientInstanceId").asText();
      connections.requireClient(origin, client, connection);
      if (params.path("action").asText().equals("cancel")) {
        var invitation = connections.invitation(db, origin, client);
        if (invitation == null
            || !invitation
                .path("invitationId")
                .asText()
                .equals(params.path("invitationId").asText()))
          throw error("INVITATION_INVALID", "邀请不属于当前客户端");
        connections.cancel(db, invitation);
      } else connections.request(db, origin, client, connection, "plugin");
      return connections.status(db, params, origin, connection);
    }
    if (method.equals("getConnectionStatus"))
      return connections.status(db, params, origin, connection);
    if (method.equals("pair")) return pair(db, params, origin, connection);
    if (method.equals("resumeSession")) return resume(db, params, origin, connection);
    Caller caller = authorize(db, envelope.path("authorization"), origin, connection);
    JsonNode definition = contract.methods.get(method);
    if (definition == null) throw error("METHOD_NOT_FOUND", "未定义的方法");
    if (definition.path("write").asBoolean()) return mutate(db, caller, method, params);
    return switch (method) {
      case "renewSession" -> session(db, caller.pairing(), connection);
      case "revokePairing" -> {
        if (!params.path("pairingId").asText().equals(caller.pairingId()))
          throw error("FORBIDDEN", "不能撤销其他设备");
        revoke(db, caller.pairingId());
        yield Json.MAPPER
            .createObjectNode()
            .put("pairingId", caller.pairingId())
            .put("revoked", true);
      }
      case "disconnect" -> {
        connections.setState(db, caller.pairingId(), "disconnected");
        endPairingConnections(db, caller.pairingId());
        yield Json.MAPPER.createObjectNode().put("disconnected", true).put("pairingRetained", true);
      }
      case "registerHost" -> registerHost(db, caller, params);
      case "getOperation" -> operation(db, caller, params);
      default -> reading.dispatch(db, caller, method, params);
    };
  }

  private JsonNode mutate(Connection db, Caller caller, String method, JsonNode params)
      throws Exception {
    String mutation = params.path("mutationId").asText();
    JsonNode receipt =
        Sql.first(
            db,
            "SELECT * FROM lmcp_operations WHERE pairing_id=? AND epoch=? AND mutation_id=?",
            caller.pairingId(),
            caller.pairing().path("epoch").asText(),
            mutation);
    String fingerprint =
        LmcpContract.digest(
            Json.MAPPER.createObjectNode().put("method", method).set("params", params));
    if (receipt != null) {
      if (!receipt.path("input_hash").asText().equals(fingerprint))
        throw error("IDEMPOTENCY_KEY_REUSED", "提交标识已用于不同请求");
      if (receipt.path("status").asText().equals("rejected"))
        throw fromError(Json.MAPPER.readTree(receipt.path("result").asText()));
      return receipt.path("status").asText().equals("pending")
          ? Json.MAPPER.createObjectNode().put("opened", false).put("navigationPending", true)
          : Json.MAPPER.readTree(receipt.path("result").asText());
    }
    // 业务拒绝同样保存终态，但必须先回滚所有可能的关系写入。
    var savepoint = db.setSavepoint();
    try {
      ObjectNode result =
          method.equals("recordEncounter")
              ? reading.encounter(db, caller, params)
              : reading.navigation(db, params);
      contract.result(method, result);
      if (Json.MAPPER.writeValueAsBytes(result).length > MAX_FRAME_BYTES - 512)
        throw error("PAYLOAD_TOO_LARGE", "采集回执超出帧预算");
      String status = method.equals("openInDesktop") ? "pending" : "applied";
      String delivery = status.equals("pending") ? token() : null;
      Sql.execute(
          db,
          "INSERT INTO lmcp_operations VALUES(?,?,?,?,?,?,?,?,?,?,?)",
          caller.pairingId(),
          caller.pairing().path("epoch").asText(),
          mutation,
          method,
          fingerprint,
          status,
          result.toString(),
          now(),
          caller.connectionId(),
          delivery,
          owner(db, caller).toString());
      if (delivery != null) {
        ObjectNode navigation =
            Json.object(params)
                .deepCopy()
                .put("deliveryToken", delivery)
                .put("pairingId", caller.pairingId())
                .put("connectionId", caller.connectionId());
        if (result.has("uiWordId")) navigation.set("uiWordId", result.path("uiWordId"));
        if (result.has("uiSearchTerm")) navigation.set("uiSearchTerm", result.path("uiSearchTerm"));
        result.put("navigationPending", true).set("navigationDelivery", navigation);
      }
      db.releaseSavepoint(savepoint);
      return result;
    } catch (ApiException rejected) {
      db.rollback(savepoint);
      db.releaseSavepoint(savepoint);
      Sql.execute(
          db,
          "INSERT INTO lmcp_operations VALUES(?,?,?,?,?,?,?,?,?,?,?)",
          caller.pairingId(),
          caller.pairing().path("epoch").asText(),
          mutation,
          method,
          fingerprint,
          "rejected",
          failure(rejected).toString(),
          now(),
          caller.connectionId(),
          null,
          owner(db, caller).toString());
      // 返回包装由 rpc 外部转换。必须让拒绝回执所在事务提交。
      return Json.MAPPER.createObjectNode().set("_businessRejection", failure(rejected));
    }
  }

  ObjectNode manage(JsonNode input) throws Exception {
    Json.fields(
        input,
        "action",
        "clientInstanceId",
        "pairingId",
        "connectionId",
        "mutationId",
        "deliveryToken",
        "opened",
        "origin",
        "invitationId");
    return database.transaction(
        db -> {
          String action = Json.text(input, "action", 40, true);
          if (action.equals("requestConnection")) {
            var target =
                connections.desktopTarget(
                    Json.text(input, "clientInstanceId", 80, true),
                    input.has("origin") ? Json.text(input, "origin", 200, true) : null);
            connections.request(
                db,
                target.path("origin").asText(),
                target.path("clientInstanceId").asText(),
                target.path("connectionId").asText(),
                "desktop");
          } else if (action.equals("cancelInvitation")) {
            connections.cancel(
                db, connections.byId(db, Json.text(input, "invitationId", 80, true)));
          }
          if (action.equals("completeNavigation")) return completeNavigation(db, input);
          if (action.equals("revoke")) {
            if (input.has("pairingId")) revoke(db, input.path("pairingId").asText());
            else
              for (JsonNode row :
                  Sql.rows(
                      db,
                      "SELECT pairing_id FROM lmcp_pairings WHERE client_id=?",
                      Json.text(input, "clientInstanceId", 80, true)))
                revoke(db, row.path("pairing_id").asText());
          } else if (action.equals("disconnect")) {
            for (JsonNode row :
                Sql.rows(
                    db,
                    "SELECT pairing_id FROM lmcp_pairings WHERE client_id=?",
                    Json.text(input, "clientInstanceId", 80, true))) {
              connections.setState(db, row.path("pairing_id").asText(), "disconnected");
              endPairingConnections(db, row.path("pairing_id").asText());
            }
          } else if (action.equals("connectionClosed")) {
            String connection = Json.text(input, "connectionId", 80, true);
            connections.closed(connection);
            Sql.execute(db, "UPDATE lmcp_sessions SET revoked=1 WHERE connection_id=?", connection);
            Sql.execute(
                db, "UPDATE lmcp_host_grants SET revoked=1 WHERE connection_id=?", connection);
          } else if (!Set.of("state", "requestConnection", "cancelInvitation").contains(action))
            throw error("INVALID_ARGUMENT", "未知连接管理动作");
          ObjectNode state =
              identity(db)
                  .put("desktopInstanceId", meta(db, "desktopInstanceId"))
                  .put("contractVersion", contract.metadata.path("packageVersion").asText());
          connections.appendManageState(db, state);
          return state;
        });
  }

  private ObjectNode completeNavigation(Connection db, JsonNode input) throws Exception {
    String pairing = Json.text(input, "pairingId", 80, true),
        mutation = Json.text(input, "mutationId", 80, true);
    var row =
        Sql.first(
            db,
            "SELECT * FROM lmcp_operations WHERE pairing_id=? AND mutation_id=? AND"
                + " status='pending' AND method='openInDesktop'",
            pairing,
            mutation);
    var p = Sql.first(db, "SELECT * FROM lmcp_pairings WHERE pairing_id=?", pairing);
    if (row == null
        || p == null
        || p.path("revoked").asBoolean()
        || !row.path("epoch").asText().equals(p.path("epoch").asText())
        || !row.path("connection_id").asText().equals(input.path("connectionId").asText())
        || !secureEquals(row.path("delivery_token").asText(), input.path("deliveryToken").asText()))
      throw error("UNAUTHORIZED", "导航确认身份无效");
    JsonNode savedOwner = Json.MAPPER.readTree(row.path("owner").asText());
    if (!savedOwner.path("workspaceId").asText().equals(meta(db, "workspaceId"))
        || !savedOwner.path("generation").asText().equals(meta(db, "generation"))
        || !savedOwner.path("desktopInstanceId").asText().equals(meta(db, "desktopInstanceId"))
        || !savedOwner.path("clientInstanceId").asText().equals(p.path("client_id").asText()))
      throw error("GENERATION_MISMATCH", "导航属于其他工作区上下文");
    if (!input.path("opened").isBoolean()) throw error("INVALID_ARGUMENT", "opened 必须为布尔值");
    var result = Json.MAPPER.createObjectNode().put("opened", input.path("opened").asBoolean());
    Sql.execute(
        db,
        "UPDATE lmcp_operations SET status='applied',result=?,delivery_token=NULL WHERE"
            + " pairing_id=? AND epoch=? AND mutation_id=?",
        result.toString(),
        pairing,
        row.path("epoch").asText(),
        mutation);
    return result;
  }

  private ObjectNode hello(Connection db, JsonNode params, String origin, String connection)
      throws Exception {
    if (!params.path("connectionId").asText().equals(connection))
      throw error("STALE_CONNECTION", "握手连接身份不同");
    checkCompatibility(params, contract.metadata);
    connections.observe(origin, connection, params);
    var out =
        Json.MAPPER
            .createObjectNode()
            .put("displayName", "词遇 Desktop")
            .put("apiVersion", contract.metadata.path("apiVersion").asText())
            .put("desktopInstanceId", meta(db, "desktopInstanceId"))
            .put("connectionId", connection)
            .put("maxFrameBytes", MAX_FRAME_BYTES)
            .put("contractVersion", contract.metadata.path("packageVersion").asText())
            .put("contractDigest", contract.metadata.path("contractDigest").asText());
    out.set("methods", Json.MAPPER.valueToTree(contract.methods.keySet()));
    out.set("capabilities", Json.MAPPER.valueToTree(CAPABILITIES));
    return out;
  }

  /**
   * 请求已通过冻结 Schema；此处只协商版本与实际能力，不授权读取资料。正式 1.x 的摘要是交付身份，
   * 不能代替最低 API 和必需能力检查；任一端为预发布时仍须候选版本与摘要都相同。
   */
  static void checkCompatibility(JsonNode params, JsonNode metadata) {
    String minimum = params.path("minApiVersion").asText();
    String actual = metadata.path("apiVersion").asText();
    if (!minimum.split("\\.")[0].equals(actual.split("\\.")[0])
        || versionCompare(minimum, actual) > 0)
      throw error("UNSUPPORTED_VERSION", "桌面 API 主版本不同或低于客户端最低要求");
    for (JsonNode requested : params.path("requiredCapabilities"))
      if (!CAPABILITIES.contains(requested.asText()))
        throw error("CAPABILITY_UNAVAILABLE", "桌面未实现必需能力");
    String clientVersion = params.path("contractVersion").asText();
    String serverVersion = metadata.path("packageVersion").asText();
    if ((clientVersion.contains("-") || serverVersion.contains("-"))
        && (!clientVersion.equals(serverVersion)
            || !params
                .path("contractDigest")
                .asText()
                .equals(metadata.path("contractDigest").asText())))
      throw error("CONTRACT_MISMATCH", "预发布候选契约快照不一致");
  }

  private ObjectNode pair(Connection db, JsonNode p, String origin, String connection)
      throws Exception {
    var invitation = connections.validatePair(db, p, origin, connection);
    String token =
        LmcpContract.hash(
            "lmcp.pairing/1:"
                + p.path("invitationToken").asText()
                + ":"
                + p.path("invitationId").asText());
    if (invitation.path("state").asText().equals("accepted")) {
      var pairing =
          Sql.first(
              db,
              "SELECT * FROM lmcp_pairings WHERE pairing_id=?",
              invitation.path("pairingId").asText());
      if (pairing == null
          || pairing.path("revoked").asBoolean()
          || !pairing.path("epoch").asText().equals(invitation.path("authorizationEpoch").asText())
          || !secureEquals(pairing.path("token_hash").asText(), LmcpContract.hash(token)))
        throw error("PAIRING_REVOKED", "原邀请授权已撤销或替换");
      if (connections.state(db, pairing).equals("disconnected"))
        throw error("CONNECTION_ENDED", "用户已明确断开连接");
      return pairedSession(db, pairing, connection, origin, token);
    }
    var old =
        Sql.first(
            db,
            "SELECT * FROM lmcp_pairings WHERE client_id=? AND origin=?",
            p.path("clientInstanceId").asText(),
            origin);
    String id = old == null ? uuid() : old.path("pairing_id").asText();
    if (old == null)
      Sql.execute(
          db,
          "INSERT INTO"
              + " lmcp_pairings(pairing_id,client_id,display_name,origin,token_hash,created_at)"
              + " VALUES(?,?,?,?,?,?)",
          id,
          p.path("clientInstanceId").asText(),
          invitation.path("displayName").asText(),
          origin,
          LmcpContract.hash(token),
          now());
    else {
      revoke(db, id);
      Sql.execute(
          db,
          "UPDATE lmcp_pairings SET revoked=0,token_hash=?,display_name=? WHERE pairing_id=?",
          LmcpContract.hash(token),
          invitation.path("displayName").asText(),
          id);
    }
    var pairing = Sql.first(db, "SELECT * FROM lmcp_pairings WHERE pairing_id=?", id);
    connections.accepted(db, invitation, pairing);
    return pairedSession(db, pairing, connection, origin, token);
  }

  private ObjectNode pairedSession(
      Connection db, ObjectNode pairing, String connection, String origin, String token)
      throws Exception {
    String id = pairing.path("pairing_id").asText();
    ObjectNode out = session(db, pairing, connection);
    out.set(
        "pairingCredential",
        owner(
                db,
                new Caller(id, pairing.path("client_id").asText(), origin, connection, pairing, ""))
            .put("pairingToken", token));
    return out;
  }

  private ObjectNode resume(Connection db, JsonNode p, String origin, String connection)
      throws Exception {
    var pairing =
        Sql.first(
            db, "SELECT * FROM lmcp_pairings WHERE pairing_id=?", p.path("pairingId").asText());
    if (pairing == null
        || pairing.path("revoked").asBoolean()
        || !pairing.path("origin").asText().equals(origin)
        || !pairing.path("client_id").asText().equals(p.path("clientInstanceId").asText())
        || !pairing.path("epoch").asText().equals(p.path("authorizationEpoch").asText())
        || !secureEquals(
            pairing.path("token_hash").asText(),
            LmcpContract.hash(p.path("pairingToken").asText())))
      throw error("PAIRING_REVOKED", "设备配对无效或已撤销");
    for (String key : List.of("desktopInstanceId", "workspaceId", "generation"))
      if (!meta(db, key).equals(p.path(key).asText()))
        throw error("GENERATION_MISMATCH", "资料空间身份已变化");
    if (connections.state(db, pairing).equals("disconnected"))
      throw error("CONNECTION_ENDED", "用户已明确断开连接");
    return session(db, pairing, connection);
  }

  private ObjectNode session(Connection db, ObjectNode pairing, String connection)
      throws Exception {
    String id = uuid(), token = token(), expiry = timestamp(clock.instant().plusSeconds(86400));
    Sql.execute(
        db,
        "INSERT INTO lmcp_sessions VALUES(?,?,?,?,?,0)",
        id,
        pairing.path("pairing_id").asText(),
        LmcpContract.hash(token),
        connection,
        expiry);
    ObjectNode authorization =
        identity(db)
            .put("sessionId", id)
            .put("sessionToken", token)
            .put("authorizationEpoch", pairing.path("epoch").asText());
    var out =
        Json.MAPPER
            .createObjectNode()
            .put("expiresAt", expiry)
            .put("readLeaseUntil", timestamp(clock.instant().plusSeconds(30)))
            .put("pairingId", pairing.path("pairing_id").asText());
    out.set("authorization", authorization);
    out.set("scopes", Json.MAPPER.valueToTree(SCOPES));
    out.set(
        "owner",
        owner(
            db,
            new Caller(
                pairing.path("pairing_id").asText(),
                pairing.path("client_id").asText(),
                pairing.path("origin").asText(),
                connection,
                pairing,
                expiry)));
    return out;
  }

  private Caller authorize(Connection db, JsonNode authorization, String origin, String connection)
      throws Exception {
    var session =
        Sql.first(
            db,
            "SELECT * FROM lmcp_sessions WHERE session_id=?",
            authorization.path("sessionId").asText());
    if (session == null
        || session.path("revoked").asBoolean()
        || !secureEquals(
            session.path("token_hash").asText(),
            LmcpContract.hash(authorization.path("sessionToken").asText())))
      throw error("UNAUTHORIZED", "本机会话无效");
    if (!session.path("connection_id").asText().equals(connection))
      throw error("STALE_CONNECTION", "会话不属于当前端口");
    if (!Instant.parse(session.path("expires_at").asText()).isAfter(clock.instant()))
      throw error("SESSION_EXPIRED", "会话已过期");
    var pairing =
        Sql.first(
            db,
            "SELECT * FROM lmcp_pairings WHERE pairing_id=?",
            session.path("pairing_id").asText());
    if (pairing == null
        || pairing.path("revoked").asBoolean()
        || !pairing.path("origin").asText().equals(origin)
        || !pairing
            .path("epoch")
            .asText()
            .equals(authorization.path("authorizationEpoch").asText()))
      throw error("PAIRING_REVOKED", "授权已撤销或来源不同");
    for (String key : List.of("workspaceId", "generation"))
      if (!meta(db, key).equals(authorization.path(key).asText()))
        throw error("GENERATION_MISMATCH", "资料空间已变化");
    return new Caller(
        pairing.path("pairing_id").asText(),
        pairing.path("client_id").asText(),
        origin,
        connection,
        pairing,
        session.path("expires_at").asText());
  }

  private ObjectNode registerHost(Connection db, Caller caller, JsonNode p) throws Exception {
    Set<String> known =
        Set.of(
            "browser.context/1",
            "browser.selection/1",
            "browser.highlight/1",
            "browser.open-source/1",
            "browser.side-panel/1");
    if (!p.path("extensionId").asText().equals("browser/1"))
      throw error("CAPABILITY_UNAVAILABLE", "未支持此插件扩展");
    for (JsonNode cap : p.path("capabilities"))
      if (!known.contains(cap.asText())) throw error("CAPABILITY_UNAVAILABLE", "未知浏览器能力");
    Sql.execute(db, "UPDATE lmcp_host_grants SET revoked=1 WHERE pairing_id=?", caller.pairingId());
    String id = uuid(), token = token(), expires = timestamp(clock.instant().plusSeconds(600));
    Sql.execute(
        db,
        "INSERT INTO lmcp_host_grants VALUES(?,?,?,?,?,?,0)",
        id,
        caller.pairingId(),
        caller.connectionId(),
        LmcpContract.hash(token),
        p.path("capabilities").toString(),
        expires);
    var out =
        Json.MAPPER
            .createObjectNode()
            .put("grantId", id)
            .put("grantToken", token)
            .put("expiresAt", expires)
            .put("extensionId", "browser/1");
    out.set("capabilities", p.path("capabilities"));
    out.set("owner", owner(db, caller));
    return out;
  }

  private ObjectNode operation(Connection db, Caller caller, JsonNode p) throws Exception {
    var row =
        Sql.first(
            db,
            "SELECT * FROM lmcp_operations WHERE pairing_id=? AND epoch=? AND mutation_id=?",
            caller.pairingId(),
            caller.pairing().path("epoch").asText(),
            p.path("mutationId").asText());
    var out =
        Json.MAPPER
            .createObjectNode()
            .put("mutationId", p.path("mutationId").asText())
            .put("status", row == null ? "unknown" : row.path("status").asText());
    if (row != null) {
      out.set("method", row.path("method"));
      if (!row.path("status").asText().equals("pending"))
        out.set(
            row.path("status").asText().equals("applied") ? "result" : "error",
            Json.MAPPER.readTree(row.path("result").asText()));
    }
    return out;
  }

  private void revoke(Connection db, String pairing) throws Exception {
    var row = Sql.first(db, "SELECT epoch FROM lmcp_pairings WHERE pairing_id=?", pairing);
    if (row == null) throw error("ENTITY_NOT_FOUND", "未找到设备配对");
    Sql.execute(
        db,
        "UPDATE lmcp_pairings SET revoked=1,epoch=? WHERE pairing_id=?",
        DesktopDataModel.nextRevision(row.path("epoch").asText()),
        pairing);
    endPairingConnections(db, pairing);
  }

  private void endPairingConnections(Connection db, String pairing) throws Exception {
    Sql.execute(db, "UPDATE lmcp_sessions SET revoked=1 WHERE pairing_id=?", pairing);
    Sql.execute(db, "UPDATE lmcp_host_grants SET revoked=1 WHERE pairing_id=?", pairing);
  }

  private void endConnection(Connection db, String pairing, String connection) throws Exception {
    Sql.execute(
        db,
        "UPDATE lmcp_sessions SET revoked=1 WHERE pairing_id=? AND connection_id=?",
        pairing,
        connection);
    Sql.execute(
        db,
        "UPDATE lmcp_host_grants SET revoked=1 WHERE pairing_id=? AND connection_id=?",
        pairing,
        connection);
  }

  ObjectNode owner(Connection db, Caller caller) throws Exception {
    return identity(db)
        .put("pairingId", caller.pairingId())
        .put("desktopInstanceId", meta(db, "desktopInstanceId"))
        .put("clientInstanceId", caller.clientId())
        .put("authorizationEpoch", caller.pairing().path("epoch").asText());
  }

  ObjectNode identity(Connection db) throws Exception {
    return Json.MAPPER
        .createObjectNode()
        .put("workspaceId", meta(db, "workspaceId"))
        .put("generation", meta(db, "generation"));
  }

  static String meta(Connection db, String key) throws Exception {
    return Sql.first(db, "SELECT value FROM lmcp_meta WHERE key=?", key).path("value").asText();
  }

  static String revision(Connection db, String key) throws Exception {
    return meta(db, key);
  }

  static void bump(Connection db, String key) throws Exception {
    Sql.execute(
        db,
        "UPDATE lmcp_meta SET value=? WHERE key=?",
        DesktopDataModel.nextRevision(meta(db, key)),
        key);
  }

  static ObjectNode failure(ApiException e) {
    return Json.MAPPER
        .createObjectNode()
        .put("code", e.code())
        .put("message", e.getMessage())
        .put("retryable", Set.of("INTERNAL_ERROR", "STORAGE_FULL").contains(e.code()));
  }

  static ApiException error(String code, String message) {
    return new ApiException(400, code, message);
  }

  static ApiException fromError(JsonNode e) {
    return error(e.path("code").asText(), e.path("message").asText());
  }

  static boolean secureEquals(String a, String b) {
    return MessageDigest.isEqual(
        a.getBytes(java.nio.charset.StandardCharsets.UTF_8),
        b.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  static String timestamp(Instant value) {
    return new java.time.format.DateTimeFormatterBuilder()
        .appendInstant(3)
        .toFormatter()
        .format(value);
  }

  String now() {
    return timestamp(clock.instant());
  }

  String businessNow() {
    return timestamp(businessClock.instant());
  }

  static String uuid() {
    return UUID.randomUUID().toString();
  }

  private String token() {
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static int versionCompare(String a, String b) {
    String[] aa = a.split("\\."), bb = b.split("\\.");
    for (int i = 0; i < 3; i++) {
      int c = new java.math.BigInteger(aa[i]).compareTo(new java.math.BigInteger(bb[i]));
      if (c != 0) return c;
    }
    return 0;
  }
}
