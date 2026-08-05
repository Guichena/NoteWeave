package com.noteweave.worker;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.noteweave.common.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

class WorkerTaskCallbackAuthenticatorTest {

    @Test
    void configuredArtifactSecretShouldBindCallbackToTaskAndWorkerType() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.query(
                eq("select task_type from task where id = ?"),
                org.mockito.ArgumentMatchers.<ResultSetExtractor<String>>any(),
                eq("task-1")))
                .thenReturn("ARTIFACT_JOB");
        WorkerTaskCallbackAuthenticator authenticator = new WorkerTaskCallbackAuthenticator(
                jdbcTemplate, "artifact-secret");

        String token = WorkerTaskCallbackAuthenticator.tokenFor(
                "artifact-secret", "ARTIFACT_JOB", "task-1");
        assertThatCode(() -> authenticator.authenticate("task-1", token)).doesNotThrowAnyException();
        assertThatThrownBy(() -> authenticator.authenticate("task-1",
                WorkerTaskCallbackAuthenticator.tokenFor("wrong-secret", "ARTIFACT_JOB", "task-1")))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).code())
                .isEqualTo("WORKER_CALLBACK_AUTH_INVALID");
    }

    @Test
    void blankSecretShouldFailClosed() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.query(
                eq("select task_type from task where id = ?"),
                org.mockito.ArgumentMatchers.<ResultSetExtractor<String>>any(),
                eq("task-1")))
                .thenReturn("ARTIFACT_JOB");
        WorkerTaskCallbackAuthenticator authenticator = new WorkerTaskCallbackAuthenticator(
                jdbcTemplate, "");
        assertThatThrownBy(() -> authenticator.authenticate("task-1", "anything"))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).code())
                .isEqualTo("WORKER_CALLBACK_AUTH_NOT_CONFIGURED");
    }

    @Test
    void researchTasksMustUseTheCanonicalAgentProtocol() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.query(
                eq("select task_type from task where id = ?"),
                org.mockito.ArgumentMatchers.<ResultSetExtractor<String>>any(),
                eq("task-1")))
                .thenReturn("RESEARCH_RUN");
        WorkerTaskCallbackAuthenticator authenticator = new WorkerTaskCallbackAuthenticator(
                jdbcTemplate, "artifact-secret");

        assertThatThrownBy(() -> authenticator.authenticate("task-1", "any-token"))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).code())
                .isEqualTo("WORKER_CALLBACK_TASK_TYPE_INVALID");
    }
}
