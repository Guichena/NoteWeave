package com.noteweave.research;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 合并补查任务按提交上限均分，4 列的冲突类问题不再建出 4 格任务。 */
class MergedRepairChunksTest {

    private static List<Integer> sizes(int total) {
        return ResearchAgentTaskCoordinatorService.mergedRepairChunks(total).stream()
                .map(range -> range[1] - range[0]).toList();
    }

    @Test
    void chunksStayWithinCompletionLimitAndCoverEveryCell() {
        assertThat(sizes(0)).isEmpty();
        assertThat(sizes(2)).containsExactly(2);
        assertThat(sizes(3)).containsExactly(3);
        assertThat(sizes(4)).containsExactly(2, 2);
        assertThat(sizes(7)).containsExactly(3, 2, 2);
        for (int total = 1; total <= 12; total++) {
            List<int[]> chunks = ResearchAgentTaskCoordinatorService.mergedRepairChunks(total);
            int expectedStart = 0;
            for (int[] range : chunks) {
                assertThat(range[0]).isEqualTo(expectedStart);
                assertThat(range[1] - range[0])
                        .isBetween(1, ResearchAgentTaskCoordinatorService.MAX_MERGED_REPAIR_CELLS);
                expectedStart = range[1];
            }
            assertThat(expectedStart).isEqualTo(total);
        }
    }
}
