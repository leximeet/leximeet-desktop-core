package app.leximeet.core;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Locale;

// 隔离资料上的确定性 50,000 词灌数。供容量基准与测试启动共用，不读取正式用户库。
final class IndexCapacitySupport {
  static final int MAX_WORDS = 50_000;

  private IndexCapacitySupport() {}

  static void seed(Connection db, int count) throws Exception {
    seed(db, count, 0);
  }

  static void seed(Connection db, int count, int noteLength) throws Exception {
    if (count < 1 || count > MAX_WORDS)
      throw new IllegalArgumentException("容量夹具词数必须为 1–" + MAX_WORDS);
    if (noteLength < 0 || noteLength > 8_000 || (noteLength > 0 && count > 10_000))
      throw new IllegalArgumentException("长笔记夹具超过隔离容量预算");
    String note = "N".repeat(noteLength);
    try (PreparedStatement insert =
        db.prepareStatement(
            "INSERT INTO"
                + " words(id,word,normalized,meaning,phonetic,note,book_id,status,created_at,updated_at)"
                + " VALUES(?,?,?,?,?,?,NULL,'new',?,?)")) {
      for (int index = 0; index < count; index++) {
        String id = String.format(Locale.ROOT, "00000000-0000-4000-8000-%012x", index);
        String word = String.format(Locale.ROOT, "benchmark%05d", index);
        String timestamp =
            String.format(Locale.ROOT, "2026-09-16T00:%02d:%02dZ", (index / 60) % 60, index % 60);
        insert.setString(1, id);
        insert.setString(2, word);
        insert.setString(3, word);
        insert.setString(4, "容量基准释义 " + index);
        insert.setString(5, "/bench/");
        insert.setString(6, note);
        insert.setString(7, timestamp);
        insert.setString(8, timestamp);
        insert.addBatch();
        if ((index + 1) % 1_000 == 0) insert.executeBatch();
      }
      insert.executeBatch();
    }
  }
}
