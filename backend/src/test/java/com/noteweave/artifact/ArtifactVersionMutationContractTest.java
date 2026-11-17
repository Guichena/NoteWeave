package com.noteweave.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ArtifactVersionMutationContractTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired ArtifactExportService exports;
    private String workspace;
    private String job;
    private String originalTask;

    @BeforeEach
    void preparePublishedVersionWithFrozenInput() throws Exception {
        workspace = data(mvc.perform(post("/api/v2/workspaces").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"版本变更回归\"}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .path("workspace_id").asText();
        JsonNode created = data(mvc.perform(post("/api/v2/workspaces/" + workspace + "/artifact-jobs")
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of(
                        "skill_key", "study_guide", "user_requirement", "第一版要求", "inputs", Map.of("language", "zh-CN")))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        job = created.path("artifact_job_id").asText();
        originalTask = created.path("task_id").asText();
        publish(originalTask, 1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"QUEUED", "RUNNING", "WAITING_FOR_PROVIDER", "COMPLETING"})
    void activeRunCannotBeReplacedByRegenerationOrRollback(String activeStatus) throws Exception {
        jdbc.update("update artifact_job set status = ? where id = ?", activeStatus, job);
        mvc.perform(post(path(1, "regenerate")).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict());
        mvc.perform(post(path(1, "rollback")).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("select task_id from artifact_job where id = ?", String.class, job)).isEqualTo(originalTask);
        assertThat(jdbc.queryForObject("select status from artifact_job where id = ?", String.class, job)).isEqualTo(activeStatus);
        assertThat(runCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from artifact_version where artifact_job_id = ?", Integer.class, job)).isEqualTo(1);
    }

    @Test
    void sameRequestReusesOneRunAndDifferentPayloadCannotReuseItsIdentity() throws Exception {
        Map<String, Object> request = Map.of("client_request_id", "retry-once", "user_requirement", "重试要求");
        JsonNode first = regenerate(1, request);
        JsonNode replay = regenerate(1, request);
        assertThat(replay.path("task_id").asText()).isEqualTo(first.path("task_id").asText());
        assertThat(runCount()).isEqualTo(2);
        mvc.perform(post(path(1, "regenerate")).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("client_request_id", "retry-once", "user_requirement", "不同要求"))))
                .andExpect(status().isConflict());
        assertThat(runCount()).isEqualTo(2);
    }

    @Test
    void concurrentRequestsCreateOnlyOneNewRunAndKeepItsTaskOwnership() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var first = submitAfterStart(ready, start, "request-1");
        var second = submitAfterStart(ready, start, "request-2");
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder(200, 409);
        assertThat(runCount()).isEqualTo(2);
        String activeTask = jdbc.queryForObject("select task_id from artifact_job where id = ?", String.class, job);
        assertThat(jdbc.queryForObject("select count(*) from artifact_job_run where artifact_job_id = ? and task_id = ?",
                Integer.class, job, activeTask)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from task where target_id = ? and task_type = 'ARTIFACT_JOB'",
                Integer.class, job)).isEqualTo(2);
    }

    @Test
    void regenerationUsesSelectedVersionInputInsteadOfTheLatestRun() throws Exception {
        String secondTask = regenerate(1, Map.of("user_requirement", "第二版要求", "inputs", Map.of("language", "en")))
                .path("task_id").asText();
        publish(secondTask, 2);
        String thirdTask = regenerate(1, Map.of()).path("task_id").asText();
        JsonNode frozen = frozenInput(thirdTask);
        assertThat(frozen.path("user_requirement").asText()).isEqualTo("第一版要求");
        assertThat(mapper.readTree(frozen.path("inputs_json").asText()).path("language").asText()).isEqualTo("zh-CN");
        assertThat(frozen.path("id").asText()).isNotEqualTo(frozenInput(originalTask).path("id").asText());
        assertThat(frozenInput(secondTask).path("user_requirement").asText()).isEqualTo("第二版要求");
    }

    @Test
    void explicitPartialOverridesKeepOtherSelectedVersionInputs() throws Exception {
        String nextTask = regenerate(1, Map.of("user_requirement", "覆盖要求", "inputs", Map.of())).path("task_id").asText();
        JsonNode frozen = frozenInput(nextTask);
        assertThat(frozen.path("user_requirement").asText()).isEqualTo("覆盖要求");
        assertThat(mapper.readTree(frozen.path("inputs_json").asText()).path("language").asText()).isEqualTo("zh-CN");
    }

    @Test
    void missingHistoricalOriginDoesNotSilentlyUseLatestInput() throws Exception {
        String secondTask = regenerate(1, Map.of("user_requirement", "第二版要求")).path("task_id").asText();
        publish(secondTask, 2);
        jdbc.update("update artifact_version set origin_task_id = null where artifact_job_id = ? and version_no = 1", job);
        mvc.perform(post(path(1, "regenerate")).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict());
        assertThat(runCount()).isEqualTo(2);
    }

    private CompletableFuture<Integer> submitAfterStart(CountDownLatch ready, CountDownLatch start, String id) {
        return CompletableFuture.supplyAsync(() -> {
            ready.countDown();
            try {
                if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("并发请求未启动");
                return mvc.perform(post(path(1, "regenerate")).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("client_request_id", id)))).andReturn().getResponse().getStatus();
            } catch (Exception ex) { throw new IllegalStateException(ex); }
        });
    }

    private void publish(String task, int version) {
        String versionId = UUID.randomUUID().toString();
        jdbc.update("""
                insert into artifact_version(id, artifact_job_id, skill_key, version_no, title,
                    content_markdown, result_payload_json, citations_json, origin_task_id)
                values (?, ?, 'study_guide', ?, '学习指南', '# 学习指南', '{}', '[]', ?)
                """, versionId, job, version, task);
        jdbc.update("update task set task_status = 'COMPLETED' where id = ?", task);
        jdbc.update("update artifact_job set status = 'COMPLETED', latest_version_no = ? where id = ?", version, job);
        exports.materializeExports(versionId);
    }

    private JsonNode regenerate(int version, Map<String, Object> request) throws Exception {
        return data(mvc.perform(post(path(version, "regenerate")).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(request))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode frozenInput(String task) {
        return mapper.valueToTree(jdbc.queryForMap("""
                select s.id as "id", s.user_requirement as "user_requirement", s.inputs_json as "inputs_json"
                from artifact_run_input_snapshot s join artifact_job_run r on r.input_snapshot_id = s.id
                where r.task_id = ?
                """, task));
    }

    private int runCount() { return jdbc.queryForObject("select count(*) from artifact_job_run where artifact_job_id = ?", Integer.class, job); }
    private String path(int version, String action) { return "/api/v2/workspaces/" + workspace + "/artifact-jobs/" + job + "/versions/" + version + "/" + action; }
    private JsonNode data(String json) throws Exception { return mapper.readTree(json).path("data"); }
}
