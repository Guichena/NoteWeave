package com.noteweave.research;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * M4-A: the deployment-level hydration switch is the outer of the two feature-flag layers. With it
 * off, hydrate() must fail closed even for a Run whose own captured snapshot authorized hydration
 * (i.e. a caller that bypassed {@link ResearchAgentCheckpointHydrator#available}).
 */
@SpringBootTest
@ActiveProfiles("test")
class ResearchAgentCheckpointHydrationDisabledTest {

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ResearchAgentCheckpointHydrator hydrator;

    @Test
    void hydrateFailsClosedWhenTheDeploymentSwitchIsOff() {
        String workspaceId = Ids.newId();
        jdbcTemplate.update("""
                insert into workspace(id, owner_id, name, status)
                values (?, 'local-user', 'm4a-disabled', 'ACTIVE')
                """, workspaceId);
        String runId = seedRunWithHydrationAuthorized(workspaceId);
        String descendant = seedRunWithHydrationAuthorized(workspaceId);

        assertThat(hydrator.available(workspaceId, runId, 1)).isFalse();
        assertThatThrownBy(() -> hydrator.hydrate(workspaceId, runId, 1, descendant))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.code())
                                .isEqualTo("RESEARCH_CHECKPOINT_HYDRATION_DISABLED"));
    }

    private String seedRunWithHydrationAuthorized(String workspaceId) {
        String taskId = Ids.newId();
        String runId = Ids.newId();
        jdbcTemplate.update("""
                insert into task(id, workspace_id, task_type, task_status, target_type, target_id)
                values (?, ?, 'RESEARCH_RUN', 'RUNNING', 'RESEARCH_RUN', ?)
                """, taskId, workspaceId, runId);
        jdbcTemplate.update("""
                insert into research_run(id, workspace_id, task_id, question, profile_key, source_scope_json,
                    status, agent_execution_mode, agent_feature_flags_json)
                values (?, ?, ?, 'm4a disabled question', 'DEFAULT', '[]', 'RUNNING', 'INCREMENTAL_V1',
                    json_object('checkpoint_hydration_v2', true))
                """, runId, workspaceId, taskId);
        return runId;
    }
}
