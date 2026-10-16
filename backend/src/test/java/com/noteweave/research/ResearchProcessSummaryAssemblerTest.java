package com.noteweave.research;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 多智能体研究没有单循环轮次，检索时间线的检索词要从各任务实际执行的检索词里取。 */
class ResearchProcessSummaryAssemblerTest {

    private final ResearchProcessSummaryAssembler assembler = new ResearchProcessSummaryAssembler();

    @Test
    void agentRunsFillTheTimelineFromTaskQueries() {
        ResearchProcessSummaryResponse summary = assembler.build(null, null, null, 0, null,
                List.of("PostgreSQL 17 jsonb GIN", "", "MySQL 8.4 multi-valued index site:dev.mysql.com",
                        "PostgreSQL 17 jsonb GIN"));

        assertThat(summary.searchReadTimeline().allSearchQueries())
                .containsExactly("PostgreSQL 17 jsonb GIN", "MySQL 8.4 multi-valued index site:dev.mysql.com");
    }

    @Test
    void runsWithoutTaskQueriesKeepAnEmptyTimeline() {
        assertThat(assembler.build(null, null, null, 0, null).searchReadTimeline().allSearchQueries()).isEmpty();
    }
}
