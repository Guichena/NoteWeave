package com.noteweave.research;

import static com.noteweave.research.ResearchReadModelMapper.blankIfNull;
import static com.noteweave.research.ResearchReadModelMapper.extractStringList;
import static com.noteweave.research.ResearchReadModelMapper.stringValue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.springframework.stereotype.Component;

/** Builds verifier-gated row counts and bounded samples for research read models. */
@Component
public class ResearchVerifierGatedSummaryAssembler {

    public Map<String, Object> build(
            List<Map<String, Object>> rows,
            Map<String, Object> recoveryTargets
    ) {
        if (rows == null || rows.isEmpty()) {
            return Map.of(
                    "blocked_row_count", 0,
                    "guardrailed_row_count", 0,
                    "recovery_targeted_blocked_row_count", 0,
                    "uncovered_blocked_row_count", 0,
                    "requirement_partial_blocked_row_count", 0,
                    "blocked_row_samples", List.of(),
                    "guardrailed_row_samples", List.of(),
                    "need_more_evidence_row_samples", List.of()
            );
        }
        List<Map<String, Object>> blockedRows = rows.stream()
                .filter(this::isVerifierGatedRow)
                .toList();
        int targetedBlockedRowCount = (int) blockedRows.stream()
                .filter(row -> matchesRecoveryTargets(row, recoveryTargets))
                .count();
        int uncoveredBlockedRowCount = Math.max(blockedRows.size() - targetedBlockedRowCount, 0);
        int requirementPartialBlockedRowCount = (int) blockedRows.stream()
                .filter(row -> "PARTIAL".equals(stringValue(row.get("requirement_completion_status"))))
                .count();
        LinkedHashMap<String, Object> summary = new LinkedHashMap<>();
        summary.put("blocked_row_count", blockedRows.size());
        summary.put("guardrailed_row_count", (int) blockedRows.stream()
                .filter(row -> !isConflictedRow(row))
                .count());
        summary.put("recovery_targeted_blocked_row_count", targetedBlockedRowCount);
        summary.put("uncovered_blocked_row_count", uncoveredBlockedRowCount);
        summary.put("requirement_partial_blocked_row_count", requirementPartialBlockedRowCount);
        summary.put("blocked_row_samples", buildRowsByPredicate(rows, this::isVerifierGatedRow, 2));
        summary.put("guardrailed_row_samples", buildRowsByPredicate(
                rows,
                row -> isVerifierGatedRow(row) && !isConflictedRow(row),
                2
        ));
        summary.put("need_more_evidence_row_samples", buildRowsByPredicate(
                rows,
                row -> "NEED_MORE_EVIDENCE".equals(stringValue(row.get("row_status"))),
                2
        ));
        return summary;
    }

    private List<Map<String, Object>> buildRowsByPredicate(
            List<Map<String, Object>> rows,
            Predicate<Map<String, Object>> predicate,
            int limit
    ) {
        if (rows == null || rows.isEmpty() || limit <= 0) {
            return List.of();
        }
        List<Map<String, Object>> samples = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if (!predicate.test(row)) {
                continue;
            }
            samples.add(buildRowSample(row));
            if (samples.size() >= limit) {
                break;
            }
        }
        return samples;
    }

    private Map<String, Object> buildRowSample(Map<String, Object> row) {
        LinkedHashMap<String, Object> sample = new LinkedHashMap<>();
        sample.put("row_id", blankIfNull(stringValue(row.get("row_id"))));
        sample.put("source_id", blankIfNull(stringValue(row.get("source_id"))));
        sample.put("source_title", blankIfNull(stringValue(row.get("source_title"))));
        sample.put("evidence_id", blankIfNull(stringValue(row.get("evidence_id"))));
        sample.put("search_query", blankIfNull(stringValue(row.get("search_query"))));
        sample.put("claim_text", blankIfNull(stringValue(row.get("claim_text"))));
        sample.put("row_status", blankIfNull(stringValue(row.get("row_status"))));
        sample.put("support_level", blankIfNull(stringValue(row.get("support_level"))));
        sample.put("verification_status", blankIfNull(stringValue(row.get("verification_status"))));
        sample.put("requirement_completion_status", blankIfNull(stringValue(row.get("requirement_completion_status"))));
        sample.put("repair_hint", blankIfNull(stringValue(row.get("repair_hint"))));
        sample.put("branch_id", blankIfNull(stringValue(row.get("branch_id"))));
        sample.put("matched_requirement_ids", extractStringList(row.get("matched_requirement_ids")));
        sample.put("ready_requirement_ids", extractStringList(row.get("ready_requirement_ids")));
        sample.put("missing_columns", extractStringList(row.get("missing_columns")));
        return sample;
    }

    private boolean isVerifierGatedRow(Map<String, Object> row) {
        return !isVerifiedRow(row);
    }

    private boolean isVerifiedRow(Map<String, Object> row) {
        return "VERIFIED".equals(stringValue(row.get("row_status")));
    }

    private boolean isConflictedRow(Map<String, Object> row) {
        return "CONFLICTED".equals(stringValue(row.get("row_status")));
    }

    private boolean matchesRecoveryTargets(Map<String, Object> row, Map<String, Object> recoveryTargets) {
        if (recoveryTargets == null || recoveryTargets.isEmpty()) {
            return false;
        }
        List<String> targetRequirementIds = extractStringList(recoveryTargets.get("requirement_ids"));
        List<String> targetColumns = extractStringList(recoveryTargets.get("target_columns"));
        List<String> targetQueries = extractStringList(recoveryTargets.get("target_queries"));
        List<String> targetSources = extractStringList(recoveryTargets.get("target_sources"));
        List<String> rowRequirementIds = new ArrayList<>(extractStringList(row.get("matched_requirement_ids")));
        rowRequirementIds.addAll(extractStringList(row.get("ready_requirement_ids")));
        if (rowRequirementIds.stream().anyMatch(targetRequirementIds::contains)) {
            return true;
        }
        if (extractStringList(row.get("missing_columns")).stream().anyMatch(targetColumns::contains)) {
            return true;
        }
        String searchQuery = blankToNull(stringValue(row.get("search_query")));
        if (matchesTargetText(searchQuery, targetQueries)) {
            return true;
        }
        String readFocus = blankToNull(stringValue(row.get("read_focus")));
        if (matchesTargetText(readFocus, targetQueries)) {
            return true;
        }
        String sourceTitle = blankToNull(stringValue(row.get("source_title")));
        return matchesTargetText(sourceTitle, targetSources);
    }

    private boolean matchesTargetText(String value, List<String> targets) {
        if (value == null || value.isBlank() || targets == null || targets.isEmpty()) {
            return false;
        }
        for (String target : targets) {
            if (target == null || target.isBlank()) {
                continue;
            }
            if (target.contains(value) || value.contains(target)) {
                return true;
            }
        }
        return false;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
