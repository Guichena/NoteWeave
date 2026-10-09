package com.noteweave.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import com.noteweave.memory.MemoryRuntime;
import com.noteweave.memory.MemoryRuntimeQuery;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Background coordinators (e.g. video learning children) create Artifact jobs with no request
 * session. With the local-user fallback off — as in production — the independent Context V2 compile
 * and memory recall must still work for the already-verified actor.
 */
@SpringBootTest(properties = {
        "noteweave.security.local-user-fallback=false",
        "noteweave.quota.enabled=true",
        "noteweave.quota.local-fallback-enabled=true"
})
@ActiveProfiles("test")
class ContextV2VerifiedActorCompileTest {

    @Autowired private ConversationContextCompilerV2Service compiler;
    @Autowired private MemoryRuntime memoryRuntime;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private com.noteweave.task.TaskService taskService;

    @Test
    void verifiedActorCompilesIndependentContextWithoutARequestSession() {
        String workspaceId = Ids.newId();
        jdbc.update("insert into workspace(id, owner_id, name, status) values (?, 'local-user', ?, 'ACTIVE')",
                workspaceId, "verified-actor-context");

        assertThatThrownBy(() -> compiler.compile(workspaceId, "local-user", "", 0,
                "整理成图文讲义", "ARTIFACT:knowledge_blog", 2000))
                .as("the interactive entry still requires a session")
                .isInstanceOf(BusinessException.class);

        ContextProjectionV2 projection = compiler.compileIndependentForVerifiedActor(
                workspaceId, "local-user", "整理成图文讲义", "ARTIFACT:knowledge_blog", 2000);
        assertThat(projection).isNotNull();
        assertThat(memoryRuntime.recall(new MemoryRuntimeQuery(workspaceId, "local-user"))).isNotNull();
    }

    @Test
    void verifiedActorTaskChargesTheWorkloadRateWithoutARequestSession() {
        String workspaceId = Ids.newId();
        jdbc.update("insert into workspace(id, owner_id, name, status) values (?, 'local-user', ?, 'ACTIVE')",
                workspaceId, "verified-actor-quota");

        assertThatThrownBy(() -> taskService.createTask(workspaceId, "ARTIFACT_JOB", "ARTIFACT_JOB",
                Ids.newId(), "QUEUED", "session-less"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.code()).isEqualTo("AUTHENTICATION_REQUIRED"));

        String taskId = taskService.createTaskForActor(workspaceId, "ARTIFACT_JOB", "ARTIFACT_JOB",
                Ids.newId(), "QUEUED", "background child", "local-user");
        assertThat(jdbc.queryForObject("select progress_phase from task where id = ?", String.class, taskId))
                .isEqualTo("QUEUED");
    }
}
