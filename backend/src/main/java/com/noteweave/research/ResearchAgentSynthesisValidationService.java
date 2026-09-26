package com.noteweave.research;

import com.noteweave.common.BusinessException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** Validates that Worker prose is only a rendering of the frozen audited ledger. */
@Service
class ResearchAgentSynthesisValidationService {
    private static final Pattern TYPED_TOKEN = Pattern.compile(
            "(?<![\\w])(?:\\d+(?:[.,]\\d+)*(?:\\s?(?:%|ms|s|MB|GB|TB|USD|EUR|CNY))?|v?\\d+\\.\\d+(?:\\.\\d+)?)(?![\\w])",
            Pattern.CASE_INSENSITIVE);

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
        if (claims.isEmpty() || markdown.isBlank()) throw invalid("Synthesis candidate is empty");
        Map<String, FrozenCell> byKey = new HashMap<>();
        for (Map<String, Object> cell : frozenCells) {
            String key = text(cell.get("cell_key"));
            String value = text(cell.get("candidate_value"));
            Set<String> evidence = new HashSet<>(strings(cell.get("evidence_keys")));
            boolean guarded = Boolean.TRUE.equals(cell.get("guarded"));
            if (key.isBlank() || value.isBlank() || evidence.isEmpty() || byKey.put(
                    key, new FrozenCell(value, evidence, guarded)) != null) {
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
        if (narrativeRequired) validateNarrative(
                narrative, byKey, markdown, trustedContext, comparisonTableRequired);
    }

    private void validateNarrative(
            Map<String, Object> narrative,
            Map<String, FrozenCell> byKey,
            String markdown,
            String trustedContext,
            boolean comparisonTableRequired
    ) {
        if (!"research-reader-report.v1".equals(text(narrative.get("schema_version")))
                || text(narrative.get("title")).isBlank()
                || text(narrative.get("executive_summary")).isBlank()) {
            throw invalid("Reader report header or schema is invalid");
        }
        List<Map<String, Object>> sections = maps(narrative.get("sections"));
        if (sections.isEmpty()) throw invalid("Reader report has no sections");
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
                StringBuilder sourceText = new StringBuilder(trustedContext);
                for (String cellKey : cellKeys) {
                    FrozenCell cell = byKey.get(cellKey);
                    if (cell == null) throw stale("Reader report paragraph targets an unknown cell");
                    allowedEvidence.addAll(cell.evidenceKeys());
                    sourceText.append(' ').append(cell.value());
                }
                if (!allowedEvidence.containsAll(evidenceKeys)) {
                    throw invalid("Reader report paragraph cites evidence outside its bound cells");
                }
                if (!typedTokens(sourceText.toString()).containsAll(typedTokens(prose))) {
                    throw invalid("Reader report paragraph adds an unsupported typed fact");
                }
                if (!markdown.contains(prose)
                        || evidenceKeys.stream().anyMatch(key -> !markdown.contains("[" + key + "]"))) {
                    throw invalid("Reader report markdown omits validated prose or citations");
                }
            }
        }
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
            StringBuilder sourceText = new StringBuilder(trustedContext);
            for (String cellKey : cellKeys) {
                FrozenCell cell = byKey.get(cellKey);
                if (cell == null) throw stale("Reader report comparison row targets an unknown cell");
                allowedEvidence.addAll(cell.evidenceKeys());
                sourceText.append(' ').append(cell.value());
            }
            if (!allowedEvidence.containsAll(evidenceKeys)) {
                throw invalid("Reader report comparison row cites evidence outside its bound cells");
            }
            if (!typedTokens(sourceText.toString()).containsAll(typedTokens(String.join(" ", cells)))) {
                throw invalid("Reader report comparison row adds an unsupported typed fact");
            }
            if (cells.stream().anyMatch(cell -> !markdown.contains(escapeTableCell(cell)))
                    || evidenceKeys.stream().anyMatch(key -> !markdown.contains("[" + key + "]"))) {
                throw invalid("Reader report markdown omits a validated comparison row or citation");
            }
        }
    }

    private Set<String> typedTokens(String value) {
        Set<String> tokens = new HashSet<>();
        Matcher matcher = TYPED_TOKEN.matcher(value);
        while (matcher.find()) tokens.add(matcher.group().replaceAll("\\s+", "").replace(",", "").toLowerCase());
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

    private record FrozenCell(String value, Set<String> evidenceKeys, boolean guarded) { }
}
