package com.noteweave.research;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** 临时：用后端校验逐份核对 replay_ab.py 重放出的新旧两版成文，不提交。用 -Dreplay.file=路径 运行。 */
class ReplayedNarrativeValidationTest {

    private static final List<String> MARKERS =
            List.of("compare", "comparison", "versus", " vs ", " vs.", "比较", "对比", "差异", "区别", "异同");

    @Test
    @SuppressWarnings("unchecked")
    void validateReplayedNarratives() throws Exception {
        String path = System.getProperty("replay.file");
        if (path == null) return;
        ResearchAgentSynthesisValidationService validator = new ResearchAgentSynthesisValidationService();
        List<Map<String, Object>> rows = new ObjectMapper().readValue(new File(path), new TypeReference<>() {});
        int checked = 0;
        List<String> failures = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> input = (Map<String, Object>) row.get("synthesis_input");
            if (input == null) continue;
            List<Map<String, Object>> cells = (List<Map<String, Object>>) input.get("cells");
            List<Map<String, Object>> leads = (List<Map<String, Object>>) input.getOrDefault("leads", List.of());
            Set<String> targets = new LinkedHashSet<>();
            List<Map<String, Object>> claims = new ArrayList<>();
            for (Map<String, Object> cell : cells) {
                String key = String.valueOf(cell.get("cell_key"));
                targets.add(key);
                claims.add(Map.of("claim_text", String.valueOf(cell.get("candidate_value")), "cell_key", key,
                        "evidence_keys", new ArrayList<>(new TreeSet<>((List<String>) cell.get("evidence_keys"))),
                        "guarded", Boolean.TRUE.equals(cell.get("guarded"))));
            }
            String question = String.valueOf(input.getOrDefault("question", ""));
            List<Map<String, Object>> all = new ArrayList<>(cells);
            all.addAll(leads);
            boolean table = comparisonTableRequired(question, all);
            for (String side : List.of("old", "new")) {
                Map<String, Object> result = (Map<String, Object>) row.get(side);
                if (result == null) continue;
                checked++;
                try {
                    validator.validate(targets, cells, claims, String.valueOf(result.get("markdown")),
                            (Map<String, Object>) result.get("narrative"), question, table, true, leads);
                } catch (RuntimeException exception) {
                    failures.add(side + " " + row.get("label") + " " + row.get("case") + " mode=" + result.get("mode")
                            + " :: " + exception.getMessage());
                }
            }
        }
        System.out.println("REPLAY_CHECKED " + checked + " FAILED " + failures.size());
        failures.forEach(failure -> System.out.println("REPLAY_FAIL " + failure));
    }

    private static boolean comparisonTableRequired(String question, List<Map<String, Object>> cells) {
        String normalized = " " + question.toLowerCase(Locale.ROOT) + " ";
        long entities = cells.stream().map(cell -> String.valueOf(cell.getOrDefault("entity_label", ""))
                .toLowerCase(Locale.ROOT)).filter(label -> !label.isBlank()).distinct().count();
        return MARKERS.stream().anyMatch(normalized::contains) && entities >= 2;
    }
}
