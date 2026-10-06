package app.leximeet.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Connection;
import java.time.Clock;
import java.util.Set;

// 六种练习共享积分，进度和草稿各自独立；Core 由真实回忆信号推导熟悉度和记忆调度。
final class DesktopPractice {
  static final Set<String> MODES =
      Set.of("word-list", "meaning-choice", "copy", "recall", "listening", "cloze");
  private final Clock clock;

  private final DesktopWords words;
  private final DesktopReviews reviews;

  DesktopPractice(Clock clock, DesktopWords words, DesktopReviews reviews) {
    this.clock = clock;
    this.words = words;
    this.reviews = reviews;
  }

  boolean record(Connection db, JsonNode input) throws Exception {
    return record(db, input, null);
  }

  boolean record(Connection db, JsonNode input, ObjectNode frozen) throws Exception {
    java.time.Instant occurredAt =
        clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    Json.fields(
        input,
        "action",
        "wordId",
        "mode",
        "answer",
        "signal",
        "assisted",
        "submissionId",
        "attemptId",
        "choiceId",
        "origin");
    String submission = Json.id(input, "submissionId", true), mode = mode(input);
    var detail = words.detail(db, Json.id(input, "wordId", true));
    String requested = detail.path("id").asText();
    String expectedWord =
        frozen == null ? detail.path("word").asText() : frozen.path("word").asText();
    var beforeLearning = DesktopFamiliarity.assess(db, requested, occurredAt);
    String originName = Json.choice(input, "origin", "practice", "practice", "notification");
    boolean list = mode.equals("word-list");
    String attempt = input.has("attemptId") ? Json.id(input, "attemptId", true) : submission;
    boolean reveal = input.path("signal").asText().equals("reveal");
    String signal =
        reveal
            ? "reveal"
            : list ? Json.choice(input, "signal", "", "familiar", "unfamiliar") : "answer";
    if (!list && input.has("signal") && !reveal) throw ApiException.badRequest("答题模式不接受列表反馈");
    if (input.has("assisted") && !input.path("assisted").isBoolean())
      throw ApiException.badRequest("提示标记必须为布尔值");
    boolean aided = input.path("assisted").asBoolean();
    boolean choice = mode.equals("meaning-choice");
    ObjectNode evidence =
        Json.MAPPER
            .createObjectNode()
            .put("entryId", detail.path("entryId").asText(""))
            .put("expected", normalize(expectedWord));
    boolean correct = false;
    if (!reveal) {
      if (list) correct = signal.equals("familiar");
      else if (choice) {
        String selected = Json.id(input, "choiceId", true);
        var question = frozen == null ? choices(db, requested, attempt) : frozen;
        boolean offered = false;
        for (JsonNode option : question.path("options"))
          if (option.path("id").asText().equals(selected)) offered = true;
        if (!offered) throw ApiException.badRequest("请选择本题提供的释义");
        correct = selected.equals(requested);
        evidence.put("choiceId", selected).set("options", question.path("options"));
      } else {
        String answer =
            judge(
                Json.text(input, "answer", 4000, false),
                frozen != null && frozen.path("caseSensitive").asBoolean());
        evidence.put("answer", answer);
        correct =
            answer.equals(
                judge(expectedWord, frozen != null && frozen.path("caseSensitive").asBoolean()));
      }
    }
    ObjectNode duplicate =
        Sql.first(db, "SELECT * FROM desktop_practice_facts WHERE submission_id=?", submission);
    if (duplicate != null) {
      if (!duplicate.path("undone_at").isNull()
          || !duplicate.path("origin").asText().equals(originName)
          || !duplicate.path("attempt_id").asText().equals(attempt)
          || !duplicate.path("evidence").asText().equals(evidence.toString())
          || !duplicate.path("word_id").asText().equals(requested)
          || !duplicate.path("mode").asText().equals(mode)
          || duplicate.path("correct").asBoolean() != correct
          || !duplicate.path("signal").asText().equals(signal)
          || duplicate.path("assisted").asBoolean() != aided)
        throw ApiException.conflict("提交标识已用于其他练习");
      return correct;
    }
    // 本机时钟回退时可以读历史，但不能把较早时间的新反馈追加到较晚的既有记忆后面。
    var latest =
        Sql.first(
            db,
            "SELECT created_at FROM desktop_practice_facts WHERE word_id=? ORDER BY"
                + " length(logical_clock) DESC,logical_clock DESC LIMIT 1",
            requested);
    if (latest != null
        && occurredAt.isBefore(java.time.Instant.parse(latest.path("created_at").asText())))
      throw ApiException.conflict("系统时间早于上次练习，请校准时间后重试");
    var previousAttempt =
        Sql.first(
            db,
            "SELECT word_id,mode FROM desktop_practice_facts WHERE attempt_id=? LIMIT 1",
            attempt);
    if (previousAttempt != null
        && (!previousAttempt.path("word_id").asText().equals(requested)
            || !previousAttempt.path("mode").asText().equals(mode)))
      throw ApiException.conflict("同一次练习不能更换词条或模式");
    String word = words.materialize(db, requested, false);
    String sequence =
        new java.math.BigInteger(
                Sql.first(db, "SELECT sequence FROM desktop_learning_origin WHERE id=1")
                    .path("sequence")
                    .asText())
            .add(java.math.BigInteger.ONE)
            .toString();
    Sql.execute(db, "UPDATE desktop_learning_origin SET sequence=? WHERE id=1", sequence);
    var origin = Sql.first(db, "SELECT * FROM desktop_learning_origin WHERE id=1");
    Sql.execute(
        db,
        "INSERT INTO"
            + " desktop_practice_facts(id,submission_id,word_id,mode,correct,created_at,signal,assisted,study_day,attempt_id,rule_version,device_id,device_seq,logical_clock,zone,evidence,origin,review_completed)"
            + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        submission,
        submission,
        word,
        mode,
        correct ? 1 : 0,
        occurredAt.toString(),
        signal,
        aided ? 1 : 0,
        java.time.LocalDate.ofInstant(occurredAt, clock.getZone()).toString(),
        attempt,
        LearningPolicy.VERSION,
        origin.path("device_id").asText(),
        origin.path("sequence").asText(),
        nextLogicalClock(db),
        clock.getZone().getId(),
        evidence.toString(),
        originName,
        previousAttempt == null
                && correct
                && !aided
                && !mode.equals("copy")
                && !reveal
                && beforeLearning.path("status").asText().equals("review")
                && !beforeLearning
                    .path("graduatedDay")
                    .asText()
                    .equals(java.time.LocalDate.now(clock).toString())
                && !occurredAt.isBefore(
                    java.time.Instant.parse(beforeLearning.path("reviewEligibleAt").asText()))
            ? 1
            : 0);
    var familiarity = DesktopFamiliarity.assess(db, word, occurredAt);
    // 临摹正确可以加分，但不把看着答案输入认作独立回忆；提示和临摹均不推迟到期复习。
    if (familiarity.path("lastEffective").asBoolean()) {
      var card = Sql.first(db, "SELECT memory,learned FROM desktop_cards WHERE word_id=?", word);
      int round =
          card == null
              ? 1
              : Json.MAPPER.readTree(card.path("memory").asText()).path("round").asInt();
      var assessment =
          Json.MAPPER
              .createObjectNode()
              .put("action", "review")
              .put("wordId", word)
              .put("rating", correct ? "good" : "again")
              .put("kind", !beforeLearning.path("graduatedAt").isNull() ? "review" : "new")
              .put("round", round)
              .put("submissionId", submission);
      reviews.recordPractice(db, assessment, occurredAt);
    }
    Sql.execute(
        db, "UPDATE words SET status=? WHERE id=?", familiarity.path("status").asText(), word);
    DesktopFamiliarity.saveProjection(db, word, familiarity);
    return correct;
  }

  ObjectNode choices(Connection db, String wordId, String seed) throws Exception {
    return choices(db, wordId, seed, "");
  }

  ObjectNode choices(Connection db, String wordId, String seed, String sessionId) throws Exception {
    // 本机模式 session 使用共享词序；调用方仍传 sessionId，不必了解本机缓存布局。
    if (!sessionId.isEmpty()) {
      var stored =
          Sql.first(
              db, "SELECT payload FROM desktop_practice_sessions WHERE session_id=?", sessionId);
      if (stored != null)
        sessionId =
            DesktopPracticeCheckpoint.queueId(
                Json.MAPPER.readTree(stored.path("payload").asText()));
    }
    var detail = words.detail(db, wordId);
    var result = Json.MAPPER.createObjectNode();
    var options = new java.util.ArrayList<ObjectNode>();
    var meanings = new java.util.HashSet<String>();
    String target = displayMeaning(detail);
    if (!target.isEmpty()) {
      options.add(
          Json.MAPPER.createObjectNode().put("id", detail.path("id").asText()).put("text", target));
      meanings.add(target);
      var random = new java.util.Random((wordId + seed).hashCode());
      if (!sessionId.isEmpty()) {
        // 先从这轮真实冻结范围挑选；随机只影响发题，发出的题目仍完整保存并用于重试判分。
        var local = new java.util.ArrayList<JsonNode>();
        Sql.rows(
                db,
                "SELECT word_id AS id FROM desktop_practice_items WHERE session_id=? AND"
                    + " word_id<>? ORDER BY position LIMIT 80",
                sessionId,
                detail.path("id").asText())
            .forEach(local::add);
        java.util.Collections.shuffle(local, random);
        addChoices(db, local, detail, options, meanings);
      }
      if (options.size() < 4) {
        // 用 position 索引定位一个有界候选窗，避免每道题都出现词典开头同样的三个干扰项。
        int maximum =
            Sql.first(db, "SELECT COALESCE(MAX(position),0) AS n FROM lexicon.entries")
                .path("n")
                .asInt();
        int start = Math.floorMod((wordId + seed).hashCode(), maximum + 1);
        var publicWords = new java.util.ArrayList<JsonNode>();
        Sql.rows(
                db,
                "SELECT id FROM lexicon.entries WHERE position>=? ORDER BY position,id LIMIT 80",
                start)
            .forEach(publicWords::add);
        if (publicWords.size() < 80)
          Sql.rows(
                  db,
                  "SELECT id FROM lexicon.entries WHERE position<? ORDER BY position,id LIMIT ?",
                  start,
                  80 - publicWords.size())
              .forEach(publicWords::add);
        java.util.Collections.shuffle(publicWords, random);
        addChoices(db, publicWords, detail, options, meanings);
      }
    }
    // 资料不足不出单选项“送分题”；保留切换练习方式的入口。
    if (options.size() < 2) options.clear();
    java.util.Collections.shuffle(options, new java.util.Random((wordId + seed).hashCode()));
    result.set("options", Json.MAPPER.valueToTree(options));
    return result;
  }

  private void addChoices(
      Connection db,
      java.util.List<JsonNode> candidates,
      JsonNode target,
      java.util.List<ObjectNode> options,
      java.util.Set<String> meanings)
      throws Exception {
    for (JsonNode row : candidates) {
      var candidate = words.detail(db, row.path("id").asText());
      String text = displayMeaning(candidate);
      if (!candidate.path("word").asText().equalsIgnoreCase(target.path("word").asText())
          && !text.isEmpty()
          && meanings.add(text))
        options.add(
            Json.MAPPER
                .createObjectNode()
                .put("id", candidate.path("id").asText())
                .put("text", text));
      if (options.size() == 4) break;
    }
  }

  // 不能把第一义项视为主释义；词头综合释义与本机补充才是本题的展示真源。
  static String displayMeaning(JsonNode detail) {
    String text = detail.path("meaning").asText("").strip();
    if (text.isBlank()) text = detail.path("entry").path("headword_summary_zh").asText("").strip();
    if (text.isBlank())
      text = detail.path("entry").path("senses").path(0).path("short_gloss").asText("").strip();
    if (text.equals("暂无中文释义")) return "";
    if (!text.matches(".*[\\p{IsHan}].*")) return "";
    // 选项与提示完全使用此冻结文本；不让界面看到一种义项却按另一种义项判分。
    int length = Math.min(199, text.length());
    if (length < text.length() && Character.isLowSurrogate(text.charAt(length))) length--;
    return length < text.length() ? text.substring(0, length) + "…" : text;
  }

  private static String nextLogicalClock(Connection db) throws Exception {
    var row =
        Sql.first(
            db,
            "SELECT logical_clock FROM desktop_practice_facts ORDER BY length(logical_clock)"
                + " DESC,logical_clock DESC LIMIT 1");
    String next =
        new java.math.BigInteger(row == null ? "0" : row.path("logical_clock").asText())
            .add(java.math.BigInteger.ONE)
            .toString();
    if (next.length() > 40) throw ApiException.conflict("学习事件序号超出协议边界");
    return next;
  }

  private static String judge(String value, boolean caseSensitive) {
    String normalized =
        java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFC).strip();
    return caseSensitive ? normalized : normalized.toLowerCase(java.util.Locale.ROOT);
  }

  private static String normalize(String value) {
    return java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKC)
        .trim()
        .toLowerCase(java.util.Locale.ROOT);
  }

  static String mode(JsonNode input) {
    String mode = Json.text(input, "mode", 40, false);
    if (mode.isEmpty()) mode = "word-list";
    if (!MODES.contains(mode)) throw ApiException.badRequest("练习模式无效");
    return mode;
  }
}
