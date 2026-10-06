package app.leximeet.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Connection;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

// 采集的共同安全边界：先脱敏并重算 UTF-16 范围，再截取目标所在句，最后在事务内查重。
final class CapturePolicy {
  record Preferences(
      int duplicateWindowDays, boolean sensitiveRedactionEnabled, int contextMaxLength) {
    ObjectNode json() {
      return Json.MAPPER
          .createObjectNode()
          .put("duplicateWindowDays", duplicateWindowDays)
          .put("sensitiveRedactionEnabled", sensitiveRedactionEnabled)
          .put("contextMaxLength", contextMaxLength);
    }
  }

  record Segment(String text, ArrayNode ranges) {}

  private record Span(int start, int end) {}

  // 每条规则都只捕获应隐藏的内容，不把“password”等字段名当作敏感值。
  private static final List<Pattern> SENSITIVE =
      List.of(
          Pattern.compile(
              "(?i)(?<![A-Za-z0-9._%+-])([A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,})(?![A-Za-z0-9_-])"),
          Pattern.compile("(?<![0-9])((?:\\+?86[- ]?)?1[3-9][0-9]{9})(?![0-9])"),
          Pattern.compile(
              "(?<![0-9])((?:\\+?[0-9]{1,3}[- ])?(?:\\([0-9]{2,4}\\)[- ]?|[0-9]{2,4}[-"
                  + " ])[0-9]{3,4}[- ][0-9]{3,4})(?![0-9])"),
          Pattern.compile("(?<![0-9])([0-9]{17}[0-9Xx])(?![0-9])"),
          Pattern.compile(
              "(?<![A-Za-z0-9_-])([A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,})(?![A-Za-z0-9_-])"),
          Pattern.compile(
              "(?i)\\b(?:password|passwd|pwd|token|api[_-]?key|secret|authorization)\\p{IsWhite_Space}*[:=]\\p{IsWhite_Space}*[\"']?([^\\p{IsWhite_Space}\"',;&]+)"),
          Pattern.compile("(?i)\\bBearer\\p{IsWhite_Space}+([A-Za-z0-9._~+/-]+=*)"),
          Pattern.compile("\\b((?:sk-|ghp_|github_pat_|AKIA)[A-Za-z0-9_-]{16,})\\b"));
  private static final Pattern CARD =
      Pattern.compile("(?<![0-9])([0-9](?:[ -]?[0-9]){12,18})(?![0-9])");

  static Preferences preferences(Connection db) throws Exception {
    JsonNode settings =
        SettingsSupport.normalize(
            Json.object(
                Json.MAPPER.readTree(
                    Sql.first(db, "SELECT payload FROM settings WHERE id=1")
                        .path("payload")
                        .asText())));
    return new Preferences(
        settings.path("captureDuplicateWindowDays").asInt(),
        settings.path("captureSensitiveRedactionEnabled").asBoolean(),
        settings.path("captureContextMaxLength").asInt());
  }

  // 所有持久化语境（包括原句）都是安全句子；不能另存一份未脱敏原文。
  static ObjectNode prepare(JsonNode original, Preferences policy) {
    ObjectNode data = Json.object(original).deepCopy();
    String surface = data.path("surface").asText();
    Segment sentence =
        segment(
            data.path("originalSentence").asText(), surface, data.path("occurrenceRanges"), policy);
    Segment excerpt =
        segment(data.path("savedExcerpt").asText(), surface, data.path("excerptRanges"), policy);
    data.put("originalSentence", sentence.text()).set("occurrenceRanges", sentence.ranges());
    data.put("savedExcerpt", excerpt.text()).set("excerptRanges", excerpt.ranges());
    if (policy.sensitiveRedactionEnabled()) {
      Json.object(data.path("annotation"))
          .put("note", redact(data.path("annotation").path("note").asText()));
      Json.object(data.path("source"))
          .put("title", redact(data.path("source").path("title").asText()));
      if (data.path("source").path("url").isTextual())
        Json.object(data.path("source"))
            .put("url", redact(data.path("source").path("url").asText()));
    }
    return data;
  }

  static Segment segment(String input, String surface, JsonNode ranges, Preferences policy) {
    LmcpReading.validateRanges(input, surface, ranges);
    if (ranges.isEmpty()) throw ApiException.badRequest("采集语境需要明确的单词位置");
    List<Span> spans = policy.sensitiveRedactionEnabled() ? spans(input) : List.of();
    for (JsonNode range : ranges)
      for (Span span : spans)
        if (range.path("start").asInt() < span.end() && range.path("end").asInt() > span.start())
          throw new ApiException(400, "SENSITIVE_SELECTION", "选中的单词属于敏感内容，已阻止采集");
    String safe = replace(input, spans);
    ArrayNode mapped = Json.MAPPER.createArrayNode();
    for (JsonNode range : ranges)
      mapped
          .addObject()
          .put("start", map(range.path("start").asInt(), spans))
          .put("end", map(range.path("end").asInt(), spans));
    int focusStart = mapped.path(0).path("start").asInt(),
        focusEnd = mapped.path(0).path("end").asInt();
    int start = focusStart, end = focusEnd;
    while (start > 0 && !boundary(safe, start - 1)) start--;
    while (end < safe.length() && !boundary(safe, end)) end++;
    if (end < safe.length() && !white(safe.charAt(end))) end++;
    while (start < focusStart && white(safe.charAt(start))) start++;
    while (end > focusEnd && white(safe.charAt(end - 1))) end--;
    if (end - start > policy.contextMaxLength()) {
      int left =
          Math.max(start, focusStart - (policy.contextMaxLength() - (focusEnd - focusStart)) / 2);
      left = Math.min(left, end - policy.contextMaxLength());
      int right = Math.min(end, left + policy.contextMaxLength());
      if (left > 0 && Character.isLowSurrogate(safe.charAt(left))) left++;
      if (right < safe.length() && Character.isLowSurrogate(safe.charAt(right))) right--;
      start = left;
      end = right;
    }
    ArrayNode clipped = Json.MAPPER.createArrayNode();
    for (JsonNode range : mapped) {
      int from = range.path("start").asInt(), to = range.path("end").asInt();
      if (from >= start && to <= end)
        clipped.addObject().put("start", from - start).put("end", to - start);
    }
    String text = safe.substring(start, end);
    LmcpReading.validateRanges(text, surface, clipped);
    if (clipped.isEmpty()) throw ApiException.badRequest("语境长度无法保留选中的单词");
    return new Segment(text, clipped);
  }

  static ArrayNode ranges(String text, String surface) {
    var out = Json.MAPPER.createArrayNode();
    var matcher =
        Pattern.compile("(?<![\\p{L}])" + Pattern.quote(surface) + "(?![\\p{L}])").matcher(text);
    while (matcher.find()) out.addObject().put("start", matcher.start()).put("end", matcher.end());
    return out;
  }

  // 自动匹配没有用户指定选区：跳过敏感位置，取首个可用词形。显式选区仍由 segment 严格拒绝。
  static Segment automaticSegment(String text, String headword, Preferences policy) {
    var matcher =
        Pattern.compile("(?i)(?<![A-Za-z])" + Pattern.quote(headword) + "(?![A-Za-z])")
            .matcher(text);
    List<Span> sensitive = policy.sensitiveRedactionEnabled() ? spans(text) : List.of();
    boolean found = false;
    while (matcher.find()) {
      found = true;
      if (overlaps(matcher.start(), matcher.end(), sensitive)) continue;
      String surface = matcher.group();
      ArrayNode positions = Json.MAPPER.createArrayNode();
      for (JsonNode range : ranges(text, surface))
        if (!overlaps(range.path("start").asInt(), range.path("end").asInt(), sensitive))
          positions.add(range);
      return segment(text, surface, positions, policy);
    }
    if (found) throw new ApiException(400, "SENSITIVE_SELECTION", "匹配位置全部属于敏感内容，已阻止采集");
    return null;
  }

  // 重复语境不修改历史实体，但本次回执必须符合当前安全政策，避免把旧明文传回插件。
  static ObjectNode duplicateProjection(JsonNode entity, Preferences policy) {
    var projected = Json.object(entity).deepCopy();
    projected.set("data", prepare(projected.path("data"), policy));
    return projected;
  }

  private static boolean overlaps(int from, int to, List<Span> values) {
    for (Span span : values) if (from < span.end() && to > span.start()) return true;
    return false;
  }

  // 按同一公共 entryId/自定义身份与安全句子查重；不凭词头合并两个公共词条。
  static String duplicate(
      Connection db, String wordId, String context, Preferences policy, Instant now)
      throws Exception {
    if (policy.duplicateWindowDays() == 0) return null;
    String key = contextKey(context);
    try (var query =
        db.prepareStatement(
            "SELECT e.id,e.context FROM encounters e WHERE e.word_id=? AND"
                + " julianday(e.created_at)>=julianday(?) AND julianday(e.created_at)<=julianday(?)"
                + " AND NOT EXISTS(SELECT 1 FROM encounter_details d WHERE d.encounter_id=e.id AND"
                + " d.undone_at IS NOT NULL) ORDER BY e.created_at DESC,e.id")) {
      query.setString(1, wordId);
      query.setString(2, now.minusSeconds(policy.duplicateWindowDays() * 86400L).toString());
      query.setString(3, now.toString());
      try (var rows = query.executeQuery()) {
        while (rows.next()) if (contextKey(rows.getString(2)).equals(key)) return rows.getString(1);
      }
    }
    return null;
  }

  static String contextKey(String text) {
    // 查重键忽略同一句的大小写，原语境与 UTF-16 位置仍原样保存；标点不做宽泛合并。
    return Normalizer.normalize(text, Normalizer.Form.NFC)
        .replaceAll("(?U)\\s+", " ")
        .replaceAll("^ +| +$", "")
        .toLowerCase(Locale.ROOT);
  }

  static String redact(String text) {
    return replace(text, spans(text));
  }

  private static boolean boundary(String text, int index) {
    char c = text.charAt(index);
    if ("。！？\n\r".indexOf(c) >= 0) return true;
    // 邮箱、域名和小数中的点不是句末；末尾标点或后接空白/闭引号才切句。
    return ".!?".indexOf(c) >= 0
        && (index + 1 == text.length()
            || white(text.charAt(index + 1))
            || "\"'”’)]}".indexOf(text.charAt(index + 1)) >= 0);
  }

  private static boolean white(char c) {
    return c == ' ' || c >= 9 && c <= 13 || c == '\u0085' || Character.isSpaceChar(c);
  }

  private static int map(int index, List<Span> spans) {
    int mapped = index;
    for (Span span : spans) if (span.end() <= index) mapped += 3 - (span.end() - span.start());
    return mapped;
  }

  private static String replace(String text, List<Span> spans) {
    StringBuilder out = new StringBuilder();
    int from = 0;
    for (Span span : spans) {
      out.append(text, from, span.start()).append("xxx");
      from = span.end();
    }
    return out.append(text, from, text.length()).toString();
  }

  private static List<Span> spans(String text) {
    List<Span> values = new ArrayList<>();
    for (Pattern pattern : SENSITIVE) {
      var matcher = pattern.matcher(text);
      while (matcher.find()) {
        int end = matcher.end(1);
        // token/password 值可含点，但句末标点不能随敏感值消失，否则下一句会被拼入语境。
        while (end > matcher.start(1) && boundary(text, end - 1)) end--;
        if (end > matcher.start(1)) values.add(new Span(matcher.start(1), end));
      }
    }
    var cards = CARD.matcher(text);
    while (cards.find())
      if (luhn(cards.group(1))) values.add(new Span(cards.start(1), cards.end(1)));
    values.sort(Comparator.comparingInt(Span::start));
    List<Span> merged = new ArrayList<>();
    for (Span span : values) {
      if (!merged.isEmpty() && span.start() <= merged.getLast().end()) {
        Span last = merged.removeLast();
        merged.add(new Span(last.start(), Math.max(last.end(), span.end())));
      } else merged.add(span);
    }
    return merged;
  }

  private static boolean luhn(String value) {
    String digits = value.replaceAll("[ -]", "");
    int sum = 0;
    boolean twice = false;
    for (int index = digits.length() - 1; index >= 0; index--) {
      int number = digits.charAt(index) - '0';
      if (twice) {
        number *= 2;
        if (number > 9) number -= 9;
      }
      sum += number;
      twice = !twice;
    }
    return sum % 10 == 0;
  }

  private CapturePolicy() {}
}
