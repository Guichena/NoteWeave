package com.noteweave.research;

import com.noteweave.common.BusinessException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** Validates that Worker prose is only a rendering of the frozen audited ledger. */
@Service
class ResearchAgentSynthesisValidationService {
    private static final Pattern TYPED_TOKEN = Pattern.compile(
            "(?<![\\w.])\\d+(?:[.,]\\d+)*(?:\\s?(?:%|ms|s|MB|GB|TB|USD|EUR|CNY))?(?!\\w|\\.\\d)"
                    + "|(?<![\\d.])v?\\d+\\.\\d+(?:\\.\\d+)*(?!\\d|\\.\\d)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern INLINE_CITATION = Pattern.compile("\\[(evidence:[^\\[\\]\\s]+)\\]");
    /** 原文常写 60.6 percent，结论写 60.6%；比对数字前统一成 %，与 Worker 侧核对口径一致。 */
    private static final Pattern PERCENT_WORD = Pattern.compile("(\\d)\\s*(?:per\\s?cent\\b|％)", Pattern.CASE_INSENSITIVE);
    /** et al. 里的点号不是句号，与 Worker 侧 narrative_report._STATEMENT 一致。 */
    private static final Pattern STATEMENT = Pattern.compile(
            ".+?(?:[。！？!?]|(?<!\\bet al)\\.(?!\\d)(?=\\s|\\[|$)|$)(?:\\s*\\[evidence:[^\\[\\]\\s]+\\])*", Pattern.DOTALL);
    /** 按作者和年份点名研究，与 Worker 侧 _AUTHOR_YEAR 一致：作者姓氏必须出现在所引原文里。 */
    private static final Pattern AUTHOR_YEAR = Pattern.compile(
            "(?<![A-Za-z])([A-Z][A-Za-z'\\-]{1,30})(?:\\s+(?:et\\s+al\\.?|and|&)(?:\\s+[A-Z][A-Za-z'\\-]{1,30})?)?"
                    + "\\s*(?:等人?)?\\s*[（(]\\s*(?:19|20)\\d{2}[a-z]?\\s*[)）]");
    /** 指回上一句那项研究的说法，与 Worker 侧 _SAME_STUDY 一致：必须和上一句引用同一条证据。 */
    private static final Pattern SAME_STUDY = Pattern.compile(
            "同一(?:项)?研究|该研究|这项研究|上述研究|\\bthe same study\\b|\\bthis study\\b", Pattern.CASE_INSENSITIVE);
    /** 与 Worker 成文的标题一致（narrative_report.BACKGROUND_HEADING_*），前端按它们给这一节单独的样式。 */
    static final String BACKGROUND_HEADING_ZH = "## 补充说明（通用知识，未经本次检索核验）";
    static final String BACKGROUND_HEADING_EN = "## Background notes (general knowledge, not verified by this research)";

    void validate(
            Set<String> targetCells,
            List<Map<String, Object>> frozenCells,
            List<Map<String, Object>> claims,
            String markdown,
            Map<String, Object> narrative,
            String trustedContext,
            boolean comparisonTableRequired,
            boolean narrativeRequired
    ) {
        validate(targetCells, frozenCells, claims, markdown, narrative, trustedContext, comparisonTableRequired,
                narrativeRequired, List.of());
    }

    /**
     * leads 是没通过核验但有原文出处的单元格线索：正文和对比表可以绑定并引用它们，
     * 校验口径与已核验的单元格相同（引用只能来自所绑定的单元格，数字和版本必须出现在原文里）；
     * 线索不算作成文必须覆盖的目标，也不能出现在结论清单里。
     */
    void validate(
            Set<String> targetCells,
            List<Map<String, Object>> frozenCells,
            List<Map<String, Object>> claims,
            String markdown,
            Map<String, Object> narrative,
            String trustedContext,
            boolean comparisonTableRequired,
            boolean narrativeRequired,
            List<Map<String, Object>> leads
    ) {
        if (claims.isEmpty() || markdown.isBlank()) throw invalid("Synthesis candidate is empty");
        Map<String, FrozenCell> byKey = new HashMap<>();
        for (Map<String, Object> cell : frozenCells) {
            String key = text(cell.get("cell_key"));
            String value = text(cell.get("candidate_value"));
            Set<String> evidence = new HashSet<>(strings(cell.get("evidence_keys")));
            boolean guarded = Boolean.TRUE.equals(cell.get("guarded"));
            // 研究对象名、列名与支撑原文都来自已核验的账本，成文时可以引用其中的数字与版本
            String context = String.join(" ", text(cell.get("entity_label")), text(cell.get("column_label")),
                    String.join(" ", strings(cell.get("supporting_quotes"))));
            if (key.isBlank() || value.isBlank() || evidence.isEmpty() || byKey.put(
                    key, new FrozenCell(value, evidence, guarded, context, text(cell.get("entity_label")),
                            evidenceQuotes(cell.get("evidence_quotes"), evidence))) != null) {
                throw invalid("Frozen synthesis ledger contains an invalid or duplicate cell");
            }
        }
        if (!byKey.keySet().equals(targetCells)) throw stale("Synthesis ledger target set is stale");

        Set<String> claimedCells = new HashSet<>();
        for (Map<String, Object> claim : claims) {
            String cellKey = text(claim.get("cell_key"));
            FrozenCell cell = byKey.get(cellKey);
            if (cell == null || !claimedCells.add(cellKey)) {
                throw stale("Synthesis claim targets an unbound or duplicate cell");
            }
            List<String> cited = strings(claim.get("evidence_keys"));
            if (cited.isEmpty() || !cell.evidenceKeys().containsAll(cited)) {
                throw invalid("Synthesis claim cites evidence outside the accepted cell ledger");
            }
            boolean guarded = Boolean.TRUE.equals(claim.get("guarded"));
            if (guarded != cell.guarded()) throw invalid("Synthesis claim changed guarded status");
            String expectedClaim = cell.guarded()
                    ? cell.value() + " (limited by unresolved evidence)"
                    : cell.value();
            String claimText = text(claim.get("claim_text"));
            if (!expectedClaim.equals(claimText)) {
                throw invalid("Synthesis claim adds or changes facts outside the audited cell");
            }
            if (!narrativeRequired && (!markdown.contains(claimText)
                    || cited.stream().anyMatch(key -> !markdown.contains("[" + key + "]")))) {
                throw invalid("Synthesis markdown omits its claim or accepted citation");
            }
        }
        if (!claimedCells.equals(targetCells)) throw invalid("Synthesis candidate does not cover every target cell");
        Map<String, FrozenCell> narrativeCells = new HashMap<>(byKey);
        for (Map<String, Object> lead : leads == null ? List.<Map<String, Object>>of() : leads) {
            String key = text(lead.get("cell_key"));
            String value = text(lead.get("candidate_value"));
            Set<String> evidence = new HashSet<>(strings(lead.get("evidence_keys")));
            if (key.isBlank() || value.isBlank() || evidence.isEmpty() || narrativeCells.containsKey(key)) continue;
            String context = String.join(" ", text(lead.get("entity_label")), text(lead.get("column_label")),
                    String.join(" ", strings(lead.get("supporting_quotes"))));
            narrativeCells.put(key, new FrozenCell(value, evidence, false, context, text(lead.get("entity_label")),
                    evidenceQuotes(lead.get("evidence_quotes"), evidence)));
        }
        if (narrativeRequired) {
            String summary = text(narrative.get("executive_summary"));
            Set<String> summaryRefs = inlineCitations(summary);
            if (summaryRefs.size() > 2 || (!typedTokens(summary).isEmpty() && summaryRefs.isEmpty())) {
                throw invalid("摘要中的事实缺少有效引用");
            }
            validateCitedText(summary, byKey, List.copyOf(summaryRefs), byKey, "", "");
            validateNarrative(narrative, narrativeCells, markdown, trustedContext, comparisonTableRequired, targetCells);
        }
    }

    private void validateNarrative(
            Map<String, Object> narrative,
            Map<String, FrozenCell> byKey,
            String markdown,
            String trustedContext,
            boolean comparisonTableRequired,
            Set<String> targetCells
    ) {
        if (!"research-reader-report.v1".equals(text(narrative.get("schema_version")))
                || text(narrative.get("title")).isBlank()
                || text(narrative.get("executive_summary")).isBlank()) {
            throw invalid("Reader report header or schema is invalid");
        }
        List<Map<String, Object>> sections = maps(narrative.get("sections"));
        if (sections.isEmpty()) throw invalid("Reader report has no sections");
        Set<String> covered = new HashSet<>();
        for (Map<String, Object> section : sections) {
            if (text(section.get("heading")).isBlank()) throw invalid("Reader report section heading is empty");
            List<Map<String, Object>> paragraphs = maps(section.get("paragraphs"));
            if (paragraphs.isEmpty()) throw invalid("Reader report section has no paragraphs");
            for (Map<String, Object> paragraph : paragraphs) {
                String prose = text(paragraph.get("text"));
                List<String> cellKeys = strings(paragraph.get("cell_keys"));
                List<String> evidenceKeys = strings(paragraph.get("evidence_keys"));
                if (prose.isBlank() || cellKeys.isEmpty() || evidenceKeys.isEmpty()) {
                    throw invalid("Reader report paragraph is not evidence-bound");
                }
                Set<String> allowedEvidence = new HashSet<>();
                for (String cellKey : cellKeys) {
                    FrozenCell cell = byKey.get(cellKey);
                    if (cell == null) throw stale("Reader report paragraph targets an unknown cell");
                    allowedEvidence.addAll(cell.evidenceKeys());
                }
                if (!allowedEvidence.containsAll(evidenceKeys)) {
                    throw invalid("Reader report paragraph cites evidence outside its bound cells");
                }
                Map<String, FrozenCell> bound = new HashMap<>();
                for (String key : cellKeys) bound.put(key, byKey.get(key));
                validateCitedText(prose, bound, evidenceKeys, byKey, trustedContext, "");
                for (String key : cellKeys) {
                    if (evidenceKeys.stream().anyMatch(byKey.get(key).evidenceKeys()::contains)) covered.add(key);
                }
                if (!markdown.contains(prose)
                        || evidenceKeys.stream().anyMatch(key -> !markdown.contains("[" + key + "]"))) {
                    throw invalid("Reader report markdown omits validated prose or citations");
                }
            }
        }
        if (!covered.containsAll(targetCells)) throw invalid("正文遗漏了已核验的目标单元格");
        Map<String, Object> comparisonTable = map(narrative.get("comparison_table"));
        if (comparisonTableRequired && comparisonTable.isEmpty()) {
            throw invalid("Reader report comparison table is required");
        }
        validateComparisonTable(comparisonTable, byKey, markdown, trustedContext);
        validateBackgroundNotes(maps(narrative.get("background_notes")), markdown);
    }

    /**
     * 通用知识补充只回答证据没有覆盖的规划要点，单独成节并注明未经本次检索核验；
     * 它不能借用证据编号冒充已核验的结论，也不能混进正文其他小节。
     */
    private void validateBackgroundNotes(List<Map<String, Object>> notes, String markdown) {
        if (notes.isEmpty()) return;
        int section = Math.max(markdown.indexOf(BACKGROUND_HEADING_ZH), markdown.indexOf(BACKGROUND_HEADING_EN));
        if (section < 0) throw invalid("Background notes are missing their labelled section");
        int next = markdown.indexOf("\n## ", section + 1);
        String body = markdown.substring(section, next < 0 ? markdown.length() : next);
        for (Map<String, Object> note : notes) {
            String text = text(note.get("text"));
            if (text.isBlank() || text.length() > 300 || text.contains("evidence:")) {
                throw invalid("Background note is empty, too long or cites evidence");
            }
            if (!body.contains(text)) throw invalid("Background note is outside its labelled section");
        }
    }

    private void validateComparisonTable(
            Map<String, Object> table,
            Map<String, FrozenCell> byKey,
            String markdown,
            String trustedContext
    ) {
        if (table.isEmpty()) return;
        List<String> columns = stringItems(table.get("columns"));
        List<Map<String, Object>> rows = maps(table.get("rows"));
        if (columns.size() < 2 || columns.size() > 6 || rows.isEmpty()) {
            throw invalid("Reader report comparison table is invalid");
        }
        for (Map<String, Object> row : rows) {
            List<String> cells = stringItems(row.get("cells"));
            List<String> cellKeys = strings(row.get("cell_keys"));
            List<String> evidenceKeys = strings(row.get("evidence_keys"));
            if (cells.size() != columns.size() || cellKeys.isEmpty() || evidenceKeys.isEmpty()) {
                throw invalid("Reader report comparison row is invalid");
            }
            Set<String> allowedEvidence = new HashSet<>();
            for (String cellKey : cellKeys) {
                FrozenCell cell = byKey.get(cellKey);
                if (cell == null) throw stale("Reader report comparison row targets an unknown cell");
                allowedEvidence.addAll(cell.evidenceKeys());
            }
            if (!allowedEvidence.containsAll(evidenceKeys)) {
                throw invalid("Reader report comparison row cites evidence outside its bound cells");
            }
            Map<String, FrozenCell> bound = new HashMap<>();
            for (String key : cellKeys) bound.put(key, byKey.get(key));
            for (int index = 1; index < cells.size(); index++) {
                String cellText = cells.get(index);
                // 缺失位置没有事实陈述，不要求为列头提供本行证据。
                if ("-".equals(cellText)) continue;
                Set<String> inline = inlineCitations(cellText);
                validateCitedText(cellText, bound, inline.isEmpty() ? evidenceKeys : List.copyOf(inline),
                        byKey, trustedContext, cells.get(0) + " " + columns.get(index));
            }
            if (cells.stream().anyMatch(cell -> !markdown.contains(escapeTableCell(cell)))
                    || evidenceKeys.stream().anyMatch(key -> !markdown.contains("[" + key + "]"))) {
                throw invalid("Reader report markdown omits a validated comparison row or citation");
            }
        }
    }

    private void validateCitedText(String prose, Map<String, FrozenCell> bound, List<String> declared,
                                   Map<String, FrozenCell> allCells, String trustedContext, String subject) {
        Set<String> allowed = new HashSet<>();
        bound.values().forEach(cell -> allowed.addAll(cell.evidenceKeys()));
        Set<String> inline = inlineCitations(prose);
        if (!allowed.containsAll(declared) || !allowed.containsAll(inline)) {
            throw invalid("句子引用了未绑定的证据");
        }
        if (!inline.isEmpty() && !inline.equals(new HashSet<>(declared))) {
            throw invalid("正文列出的证据与句中实际引用不一致");
        }
        Matcher statements = STATEMENT.matcher(prose);
        Set<String> previousRefs = new HashSet<>();
        while (statements.find()) {
            String statement = statements.group();
            if (statement.isBlank()) continue;
            Set<String> refs = inline.isEmpty() ? new HashSet<>(declared) : inlineCitations(statement);
            // 说"同一研究"时要和上一句引用同一条证据，不能把相邻引用的不同研究写成一项
            if (!inline.isEmpty() && !previousRefs.isEmpty() && !refs.isEmpty()
                    && SAME_STUDY.matcher(statement).find() && java.util.Collections.disjoint(refs, previousRefs)) {
                throw invalid("句子说同一研究，但引用的证据与上一句不同");
            }
            if (!refs.isEmpty()) previousRefs = refs;
            List<FrozenCell> cited = bound.values().stream()
                    .filter(cell -> cell.evidenceKeys().stream().anyMatch(refs::contains)).toList();
            StringBuilder sources = new StringBuilder();
            for (FrozenCell cell : cited) {
                if (cell.quotes().isEmpty()) {
                    sources.append(' ').append(trustedContext).append(' ').append(cell.value()).append(' ').append(cell.context());
                } else {
                    cell.quotes().forEach((key, quote) -> { if (refs.contains(key)) sources.append(' ').append(quote); });
                    sources.append(' ').append(cell.entity());
                }
            }
            String statementProse = INLINE_CITATION.matcher(statement).replaceAll("");
            Matcher author = AUTHOR_YEAR.matcher(statementProse);
            while (author.find()) {
                String surname = author.group(1);
                if (!Pattern.compile("(?<![A-Za-z])" + Pattern.quote(surname) + "(?![A-Za-z])")
                        .matcher(sources).find()) {
                    throw invalid("句子点名的研究作者没有出现在所引原文里：" + surname);
                }
            }
            Set<String> unsupported = new java.util.TreeSet<>(typedTokens(statementProse));
            unsupported.removeAll(typedTokens(sources.toString()));
            if (!unsupported.isEmpty()) {
                // 带上句子开头和缺少出处的数字，拒收后可以直接从日志定位是哪一句
                String excerpt = statement.strip();
                throw invalid("句子的数字或版本没有所引原文支持：" + unsupported + "，"
                        + (excerpt.length() > 80 ? excerpt.substring(0, 80) + "…" : excerpt));
            }
            // 句子提到了研究对象时，所引证据至少要属于其中一个，防止把一个对象的事实写到另一个对象名下；
            // 比较句会同时提到两边而只引用其中一边的原文，属于正常写法
            String mentionedText = (subject + " " + INLINE_CITATION.matcher(statement).replaceAll("")).toLowerCase(Locale.ROOT);
            Set<String> mentioned = new HashSet<>();
            for (FrozenCell cell : allCells.values()) {
                String stem = entityStem(cell.entity());
                if (stem.length() >= 3 && Pattern.compile("(?<![A-Za-z0-9])" + Pattern.quote(stem) + "(?![A-Za-z0-9])")
                        .matcher(mentionedText).find()) mentioned.add(stem);
            }
            if (!mentioned.isEmpty() && cited.stream().noneMatch(source -> mentioned.contains(entityStem(source.entity())))) {
                throw invalid("句子中的研究对象与所引证据不一致");
            }
        }
    }

    private String entityStem(String label) {
        return label.strip().replaceFirst("(?i)\\s+v?\\d[\\w. -]*$", "").toLowerCase(Locale.ROOT);
    }

    private Set<String> inlineCitations(String value) {
        Set<String> keys = new HashSet<>();
        Matcher matcher = INLINE_CITATION.matcher(value);
        while (matcher.find()) keys.add(matcher.group(1));
        return keys;
    }

    private Map<String, String> evidenceQuotes(Object value, Set<String> allowed) {
        Map<String, String> quotes = new HashMap<>();
        for (Map<String, Object> item : maps(value)) {
            String key = text(item.get("evidence_key"));
            String quote = text(item.get("quote"));
            if (allowed.contains(key) && !quote.isBlank()) quotes.put(key, quote);
        }
        return quotes;
    }

    private static final String MONTH_NAME = "Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|Jun(?:e)?|"
            + "Jul(?:y)?|Aug(?:ust)?|Sep(?:t(?:ember)?)?|Oct(?:ober)?|Nov(?:ember)?|Dec(?:ember)?";
    private static final String DAY = "0?[1-9]|[12]\\d|3[01]";
    private static final String MONTH = "0?[1-9]|1[0-2]";
    private static final String YEAR = "(?:19|20)\\d{2}";
    /**
     * 日期按整体比对，与 Worker 成文核对（narrative_report._DATE_PATTERNS）一致：原文 December 15, 2026
     * 与成文 2026 年 12 月 15 日是同一个日期。成文比原文更精确（多写了日或月）时仍然对不上。
     */
    private static final List<Pattern> DATE_PATTERNS = List.of(
            Pattern.compile("(?<![\\w.])(?<day>" + DAY + ")[ \\t]+(?<mname>" + MONTH_NAME + ")\\.?,?[ \\t]+(?<year>"
                    + YEAR + ")(?!\\w|\\.\\d)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?<![\\w.])(?<mname>" + MONTH_NAME + ")\\.?[ \\t]+(?:(?<day>" + DAY
                    + ")(?:st|nd|rd|th)?,?[ \\t]+)?(?<year>" + YEAR + ")(?!\\w|\\.\\d)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?<![\\w.])(?<year>" + YEAR + ")[ \\t]*[-/年][ \\t]*(?<month>" + MONTH + ")"
                    + "(?:[ \\t]*[-/月][ \\t]*(?<day>" + DAY + ")[ \\t]*日?|[ \\t]*月)?(?![\\d.]\\d|\\d)"),
            Pattern.compile("(?<![\\w.])(?<year>" + YEAR + ")\\.(?<month>" + MONTH + ")\\.(?<day>" + DAY
                    + ")(?!\\w|\\.\\d)"),
            Pattern.compile("(?<![\\w.])(?<month>" + MONTH + ")[ \\t]*月(?:[ \\t]*(?<day>" + DAY + ")[ \\t]*[日号])?"));
    private static final List<String> MONTH_ABBREVIATIONS = List.of(
            "jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec");

    /** 把日期换成按精度分层的标记，返回去掉日期后的文字；规则与 Worker 侧 _date_tokens 相同。 */
    static String extractDateTokens(String value, Set<String> tokens) {
        List<int[]> spans = new java.util.ArrayList<>();
        for (Pattern pattern : DATE_PATTERNS) {
            Matcher matcher = pattern.matcher(value);
            while (matcher.find()) {
                int start = matcher.start();
                int end = matcher.end();
                if (spans.stream().anyMatch(span -> span[0] < end && start < span[1])) continue;
                String year = group(matcher, "year");
                String monthName = group(matcher, "mname");
                String monthDigits = group(matcher, "month");
                int month;
                if (monthName != null) {
                    month = MONTH_ABBREVIATIONS.indexOf(monthName.substring(0, 3).toLowerCase(Locale.ROOT)) + 1;
                } else if (monthDigits != null) {
                    month = Integer.parseInt(monthDigits);
                } else {
                    continue;
                }
                String dayText = group(matcher, "day");
                Integer day = dayText == null ? null : Integer.parseInt(dayText);
                spans.add(new int[] {start, end});
                tokens.add("month:" + month);
                if (day != null) tokens.add("monthday:" + month + "-" + day);
                if (year != null) {
                    tokens.add(year);
                    tokens.add("date:" + year + "-" + month);
                    if (day != null) tokens.add("date:" + year + "-" + month + "-" + day);
                }
            }
        }
        if (spans.isEmpty()) return value;
        spans.sort(java.util.Comparator.comparingInt(span -> span[0]));
        StringBuilder stripped = new StringBuilder();
        int last = 0;
        for (int[] span : spans) {
            stripped.append(value, last, span[0]).append(' ');
            last = span[1];
        }
        return stripped.append(value.substring(last)).toString();
    }

    private static String group(Matcher matcher, String name) {
        try {
            return matcher.group(name);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    static Set<String> typedTokensForTest(String value) {
        return new ResearchAgentSynthesisValidationService().typedTokens(value);
    }

    private Set<String> typedTokens(String value) {
        Set<String> tokens = new HashSet<>();
        String remaining = extractDateTokens(value, tokens);
        Matcher matcher = TYPED_TOKEN.matcher(PERCENT_WORD.matcher(remaining).replaceAll("$1%"));
        while (matcher.find()) tokens.add(matcher.group().replaceAll("\\s+", "").replace(",", "")
                .toLowerCase(Locale.ROOT).replaceFirst("^v(?=\\d)", ""));
        return tokens;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> maps(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().filter(item -> item instanceof Map<?, ?>)
                .map(item -> (Map<String, Object>) item).toList();
    }

    private List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().map(this::text).filter(item -> !item.isBlank()).distinct().toList();
    }

    private List<String> stringItems(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().map(this::text).filter(item -> !item.isBlank()).toList();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private String escapeTableCell(String value) {
        return value.replace("|", "\\|").replace('\n', ' ').strip();
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).strip();
    }

    private BusinessException invalid(String message) {
        return new BusinessException("RESEARCH_AGENT_SYNTHESIS_INVALID", message);
    }

    private BusinessException stale(String message) {
        return new BusinessException("RESEARCH_AGENT_SYNTHESIS_STALE", message);
    }

    private record FrozenCell(String value, Set<String> evidenceKeys, boolean guarded, String context,
                              String entity, Map<String, String> quotes) { }
}
