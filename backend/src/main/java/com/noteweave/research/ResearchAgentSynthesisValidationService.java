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
    private static final Pattern STATEMENT = Pattern.compile(
            ".+?(?:[。！？!?]|\\.(?!\\d)(?=\\s|\\[|$)|$)(?:\\s*\\[evidence:[^\\[\\]\\s]+\\])*", Pattern.DOTALL);

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
        while (statements.find()) {
            String statement = statements.group();
            if (statement.isBlank()) continue;
            Set<String> refs = inline.isEmpty() ? new HashSet<>(declared) : inlineCitations(statement);
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
            if (!typedTokens(sources.toString()).containsAll(typedTokens(INLINE_CITATION.matcher(statement).replaceAll("")))) {
                throw invalid("句子的数字或版本没有所引原文支持");
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

    private Set<String> typedTokens(String value) {
        Set<String> tokens = new HashSet<>();
        Matcher matcher = TYPED_TOKEN.matcher(value);
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
