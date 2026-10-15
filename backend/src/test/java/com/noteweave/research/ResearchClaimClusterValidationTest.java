package com.noteweave.research;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 多单元格任务的候选簇按单元格计数：每个单元格最多 6 个，一次完成最多 36 个。 */
class ResearchClaimClusterValidationTest {

    @Test
    void threeCellsWithSixClustersEachAreAccepted() {
        assertThatCode(() -> ResearchClaimClusterService.validateClusters(clusters(3, 6))).doesNotThrowAnyException();
    }

    @Test
    void moreThanSixClustersForOneCellAreRejected() {
        assertThatThrownBy(() -> ResearchClaimClusterService.validateClusters(clusters(1, 7)))
                .isInstanceOf(RuntimeException.class);
    }

    private static List<Map<String, Object>> clusters(int cells, int perCell) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (int cell = 0; cell < cells; cell++) {
            for (int index = 0; index < perCell; index++) {
                String key = "ev-" + cell + "-" + index;
                String claim = "claim " + cell + " " + index;
                result.add(Map.of(
                        "cluster_key", "cluster-" + cell + "-" + index,
                        "cell_key", "entity-1:column-" + cell,
                        "claim_text", claim,
                        "evidence_keys", List.of(key),
                        "confidence_ppm", 500000,
                        "equivalent_claims", List.of(Map.of(
                                "evidence_key", key, "claim_text", claim, "source_domain", "example.com"))));
            }
        }
        return result;
    }
}
