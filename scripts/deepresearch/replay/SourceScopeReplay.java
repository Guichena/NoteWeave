package com.noteweave.research;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
public class SourceScopeReplay {
  public static void main(String[] args) throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    String[][] samples = {
      {"MySQL8.4", "https://dev.mysql.com/doc/refman/9.3/en/index.html", "manual", "", "", "SOURCE_VERSION_MISMATCH"},
      {"MySQL 8.4", "https://dev.mysql.com/doc/refman/8.4/en/index.html", "manual", "", "", ""},
      {"PostgreSQL18", "https://postgresql.org/docs/17/index.html", "manual", "", "", "SOURCE_VERSION_MISMATCH"},
      {"JDK 21", "https://openjdk.org/jeps/444", "JEP 444: Virtual Threads", "Virtual threads support blocking APIs.", "JDK 21", ""},
      {"Rust 2024 edition", "https://doc.rust-lang.org/1.85/reference/index.html", "reference", "", "", ""},
      {"Python 3.13", "https://docs.python.org/dev/library/", "Python 3.13", "", "", "SOURCE_DEVELOPMENT_BRANCH_MISMATCH"},
      {"Python 3.13", "https://docs.example/guide/", "reference", "", "", "SOURCE_VERSION_UNRESOLVED"},
      {"Python", "https://docs.python.org/3/whatsnew/3.13.html", "changes", "", "", ""}
    };
    List<Map<String,Object>> cases = new ArrayList<>();
    for (String[] sample : samples) {
      var scope = ResearchSourceScopePolicy.freeze("row:scope", sample[0], "当前支持状态", "2026-10-20");
      var result = ResearchSourceScopePolicy.evaluate(mapper.valueToTree(scope), sample[1], sample[2], sample[3], sample[4]);
      if (!sample[5].isEmpty() && !((List<?>) result.get("reason_codes")).contains(sample[5])) throw new IllegalStateException("范围错误未被阻止");
      if (sample[5].isEmpty() && !((List<?>) result.get("reason_codes")).isEmpty()) throw new IllegalStateException("正确来源被误拒");
      cases.add(Map.of("scope", scope, "url", sample[1], "source_title", sample[2], "quote", sample[3], "window_text", sample[4], "expected", result));
    }
    Files.writeString(Path.of(System.getenv("NOTEWEAVE_REPLAY_OUT") + "/source-scope-cases.json"), mapper.writeValueAsString(cases));
    System.out.println("{\"source_scope_java_cases\":8}");
  }
}
