package com.noteweave.personal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.error.BusinessException;
import com.noteweave.common.error.ErrorCode;
import com.noteweave.llm.dto.LlmResponse;
import com.noteweave.llm.service.LlmClient;
import com.noteweave.personal.source.fetch.FetchedUrlContent;
import com.noteweave.personal.source.fetch.UrlContentFetcher;
import com.noteweave.storage.service.FileStorageService;
import com.noteweave.support.ContainerizedIntegrationTest;
import com.noteweave.task.model.TaskStatus;
import com.noteweave.task.service.TaskDispatcher;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class Phase6PersonalResearchSourceIntegrationTest extends ContainerizedIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TaskDispatcher taskDispatcher;

    @Autowired
    private FileStorageService fileStorageService;

    @MockBean
    private UrlContentFetcher urlContentFetcher;

    @MockBean
    private LlmClient llmClient;

    @Test
    void projectCrudShouldBeOwnerOnlyAndArchiveHideProject() throws Exception {
        String ownerToken = registerAndGetToken("phase6_project_owner_" + System.nanoTime());
        String outsiderToken = registerAndGetToken("phase6_project_outsider_" + System.nanoTime());

        Long projectId = createProject(ownerToken, "Phase 6 project", "desc", "goal");

        mockMvc.perform(get("/api/v1/personal/research-projects")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(projectId));

        Map<String, Object> update = new HashMap<>();
        update.put("title", "Phase 6 project updated");
        update.put("description", "updated desc");
        update.put("researchGoal", "updated goal");
        mockMvc.perform(put("/api/v1/personal/research-projects/{projectId}", projectId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(update)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("Phase 6 project updated"));

        mockMvc.perform(get("/api/v1/personal/research-projects/{projectId}", projectId)
                        .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("RESEARCH_PROJECT_ACCESS_DENIED"));

        mockMvc.perform(delete("/api/v1/personal/research-projects/{projectId}", projectId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/personal/research-projects/{projectId}", projectId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESEARCH_PROJECT_NOT_FOUND"));

        Map<String, Object> textPayload = new HashMap<>();
        textPayload.put("title", "after archive");
        textPayload.put("content", "text");
        mockMvc.perform(post("/api/v1/personal/research-projects/{projectId}/sources/text", projectId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(textPayload)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESEARCH_PROJECT_NOT_FOUND"));
    }

    @Test
    void textSourceShouldBeReadyImmediatelyAndDeduplicateWithinProject() throws Exception {
        String ownerToken = registerAndGetToken("phase6_text_owner_" + System.nanoTime());
        String outsiderToken = registerAndGetToken("phase6_text_outsider_" + System.nanoTime());
        Long projectId = createProject(ownerToken, "Text project", null, null);

        JsonNode first = addTextSource(ownerToken, projectId, "My notes", "same personal note");
        Long firstSourceId = first.path("data").path("id").asLong();

        assertThat(first.path("data").path("importStatus").asText()).isEqualTo("READY");
        assertThat(first.path("data").path("rawTextObjectKey").asText()).isNotBlank();
        assertThat(first.path("data").path("taskId").isNull()).isTrue();

        JsonNode second = addTextSource(ownerToken, projectId, "Other title", "same personal note");
        Long secondSourceId = second.path("data").path("id").asLong();
        assertThat(secondSourceId).isEqualTo(firstSourceId);

        Integer sourceCount = jdbcTemplate.queryForObject(
                "select count(*) from source where research_project_id = ? and deleted_at is null",
                Integer.class,
                projectId
        );
        assertThat(sourceCount).isEqualTo(1);

        mockMvc.perform(get("/api/v1/personal/sources/{sourceId}", firstSourceId)
                        .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SOURCE_ACCESS_DENIED"));

        mockMvc.perform(delete("/api/v1/personal/sources/{sourceId}", firstSourceId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/personal/sources/{sourceId}", firstSourceId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SOURCE_NOT_FOUND"));
    }

    @Test
    void fileSourceShouldCreatePendingTaskAndBecomeReadyAfterDispatch() throws Exception {
        String ownerToken = registerAndGetToken("phase6_file_owner_" + System.nanoTime());
        Long projectId = createProject(ownerToken, "File project", null, null);

        byte[] bytes = """
                # Phase 6 File

                Reusing DocumentParserService should extract this text.
                """.getBytes(StandardCharsets.UTF_8);

        JsonNode created = uploadFileSource(ownerToken, projectId, "phase6.md", "text/markdown", bytes, "Phase file");
        Long sourceId = created.path("data").path("id").asLong();
        Long taskId = created.path("data").path("taskId").asLong();

        assertThat(created.path("data").path("importStatus").asText()).isEqualTo("PENDING");
        assertThat(taskId).isPositive();

        taskDispatcher.dispatchPendingMessages();
        waitForTaskStatus(taskId, TaskStatus.SUCCESS);
        waitForSourceStatus(sourceId, "READY");

        String parsedTextObjectKey = jdbcTemplate.queryForObject(
                "select parsed_text_object_key from source where id = ?",
                String.class,
                sourceId
        );
        assertThat(parsedTextObjectKey).contains("/parsed-text/source/" + sourceId + "/1.txt");
        assertThat(fileStorageService.objectExists(fileStorageService.testBucket(), parsedTextObjectKey)).isTrue();
    }

    @Test
    void fileSourceShouldRejectUnsupportedTypesBeforeCreatingTask() throws Exception {
        String ownerToken = registerAndGetToken("phase6_file_reject_owner_" + System.nanoTime());
        Long projectId = createProject(ownerToken, "Unsupported file project", null, null);

        MockMultipartFile filePart = new MockMultipartFile(
                "file",
                "phase6.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "fake docx".getBytes(StandardCharsets.UTF_8)
        );
        MockMultipartFile requestPart = new MockMultipartFile(
                "request",
                "",
                MediaType.APPLICATION_JSON_VALUE,
                objectMapper.writeValueAsBytes(Map.of("title", "Unsupported"))
        );

        mockMvc.perform(multipart(HttpMethod.POST, "/api/v1/personal/research-projects/{projectId}/sources/upload", projectId)
                        .file(filePart)
                        .file(requestPart)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_DOCUMENT_TYPE"));

        Integer sourceCount = jdbcTemplate.queryForObject(
                "select count(*) from source where research_project_id = ?",
                Integer.class,
                projectId
        );
        assertThat(sourceCount).isZero();
    }

    @Test
    void urlSourceShouldRejectUnsafeLocalUrlsBeforeCreatingTask() throws Exception {
        String ownerToken = registerAndGetToken("phase6_url_reject_owner_" + System.nanoTime());
        Long projectId = createProject(ownerToken, "Unsafe URL project", null, null);
        String blockedUrl = "http://127.0.0.1/private";

        willThrow(new BusinessException(ErrorCode.VALIDATION_FAILED, "url: unsafe url"))
                .given(urlContentFetcher)
                .validate(blockedUrl);

        mockMvc.perform(post("/api/v1/personal/research-projects/{projectId}/sources/url", projectId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "title", "Blocked",
                                "url", blockedUrl
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        Integer sourceCount = jdbcTemplate.queryForObject(
                "select count(*) from source where research_project_id = ?",
                Integer.class,
                projectId
        );
        assertThat(sourceCount).isZero();
    }

    @Test
    void urlSourceShouldFailThenSupportReimportAndReusePendingTask() throws Exception {
        String ownerToken = registerAndGetToken("phase6_url_owner_" + System.nanoTime());
        Long projectId = createProject(ownerToken, "URL project", null, null);
        String url = url("/flaky");

        given(urlContentFetcher.fetch(url))
                .willThrow(new BusinessException(ErrorCode.SOURCE_IMPORT_FAILED, "URL source returned status 500"));

        JsonNode created = addUrlSource(ownerToken, projectId, url, "Flaky page");
        Long sourceId = created.path("data").path("id").asLong();
        Long firstTaskId = created.path("data").path("taskId").asLong();

        JsonNode reused = reimportSource(ownerToken, sourceId);
        assertThat(reused.path("data").path("taskId").asLong()).isEqualTo(firstTaskId);

        taskDispatcher.dispatchPendingMessages();
        waitForTaskStatus(firstTaskId, TaskStatus.FAILED);
        waitForSourceStatus(sourceId, "FAILED");

        org.mockito.BDDMockito.willReturn(fetchedHtml("flaky success body"))
                .given(urlContentFetcher)
                .fetch(url);

        JsonNode retried = reimportSource(ownerToken, sourceId);
        Long secondTaskId = retried.path("data").path("taskId").asLong();
        assertThat(secondTaskId).isNotEqualTo(firstTaskId);

        taskDispatcher.dispatchPendingMessages();
        waitForTaskStatus(secondTaskId, TaskStatus.SUCCESS);
        waitForSourceStatus(sourceId, "READY");

        String rawTextObjectKey = jdbcTemplate.queryForObject(
                "select raw_text_object_key from source where id = ?",
                String.class,
                sourceId
        );
        assertThat(rawTextObjectKey).contains("/raw-text/source/" + sourceId + "/2.txt");
        assertThat(fileStorageService.objectExists(fileStorageService.testBucket(), rawTextObjectKey)).isTrue();
    }

    @Test
    void urlDedupShouldReuseNormalizedUrlAndCanonicalizeSameContent() throws Exception {
        String ownerToken = registerAndGetToken("phase6_url_dedup_owner_" + System.nanoTime());
        Long projectId = createProject(ownerToken, "URL dedup project", null, null);

        given(urlContentFetcher.fetch(url("/normalize"))).willReturn(fetchedHtml("normalized unique body"));
        given(urlContentFetcher.fetch(url("/success-a"))).willReturn(fetchedHtml("same body"));
        given(urlContentFetcher.fetch(url("/success-b"))).willReturn(fetchedHtml("same body"));

        JsonNode first = addUrlSource(ownerToken, projectId, url("/normalize"), "Normalized URL");
        Long firstSourceId = first.path("data").path("id").asLong();

        JsonNode duplicateUrl = addUrlSource(ownerToken, projectId, url("/normalize#fragment"), "Normalized URL again");
        assertThat(duplicateUrl.path("data").path("id").asLong()).isEqualTo(firstSourceId);

        JsonNode sourceA = addUrlSource(ownerToken, projectId, url("/success-a"), "A");
        JsonNode sourceB = addUrlSource(ownerToken, projectId, url("/success-b"), "B");
        Long sourceAId = sourceA.path("data").path("id").asLong();
        Long sourceBId = sourceB.path("data").path("id").asLong();
        Long taskBId = sourceB.path("data").path("taskId").asLong();

        taskDispatcher.dispatchPendingMessages();
        waitForTaskStatus(sourceA.path("data").path("taskId").asLong(), TaskStatus.SUCCESS);
        waitForTaskStatus(taskBId, TaskStatus.SUCCESS);

        Integer activeCount = jdbcTemplate.queryForObject(
                "select count(*) from source where research_project_id = ? and deleted_at is null",
                Integer.class,
                projectId
        );
        assertThat(activeCount).isEqualTo(2);

        JsonNode taskB = getTask(ownerToken, taskBId);
        assertThat(taskB.path("data").path("resultRefType").asText()).isEqualTo("SOURCE");
        assertThat(taskB.path("data").path("resultRefId").asLong()).isEqualTo(sourceAId);

        assertThat(jdbcTemplate.queryForObject(
                "select deleted_at is not null from source where id = ?",
                Boolean.class,
                sourceBId
        )).isTrue();
    }

    @Test
    void reimportShouldRefreshUrlContentAndInvalidateCompileStatus() throws Exception {
        String ownerToken = registerAndGetToken("phase6_reimport_compile_owner_" + System.nanoTime());
        Long projectId = createProject(ownerToken, "Reimport compile project", null, null);
        String targetUrl = url("/compile-reimport");

        given(urlContentFetcher.fetch(targetUrl))
                .willReturn(fetchedHtml("first body for import"))
                .willReturn(fetchedHtml("second body for import"));

        JsonNode created = addUrlSource(ownerToken, projectId, targetUrl, "Compile URL");
        Long sourceId = created.path("data").path("id").asLong();
        Long importTaskId = created.path("data").path("taskId").asLong();
        given(llmClient.chat(anyList(), any()))
                .willReturn(llmResponse("""
                        {
                          "title": "Compile article",
                          "summary": "first summary",
                          "keyPoints": ["first"],
                          "tags": ["phase6"],
                          "evidenceQuotes": [{"quote": "first body for import", "sourceId": %d, "reason": "first"}]
                        }
                        """.formatted(sourceId)))
                .willReturn(llmResponse("""
                        {
                          "concepts": [
                            {
                              "name": "Fresh Import",
                              "aliases": ["Fresh Import Alias"],
                              "definition": "fresh",
                              "explanation": "fresh",
                              "useCases": ["fresh"],
                              "commonMisunderstandings": ["none"],
                              "evidence": {"sourceId": %d, "quote": "first body for import"},
                              "confidence": 0.9
                            }
                          ],
                          "relations": []
                        }
                        """.formatted(sourceId)));

        taskDispatcher.dispatchPendingMessages();
        waitForTaskStatus(importTaskId, TaskStatus.SUCCESS);
        waitForSourceStatus(sourceId, "READY");

        MvcResult compileResult = mockMvc.perform(post("/api/v1/personal/sources/{sourceId}/compile", sourceId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andReturn();
        Long compileTaskId = objectMapper.readTree(compileResult.getResponse().getContentAsString()).path("data").path("taskId").asLong();
        taskDispatcher.dispatchPendingMessages();
        waitForTaskStatus(compileTaskId, TaskStatus.SUCCESS);

        String firstRawKey = jdbcTemplate.queryForObject(
                "select raw_text_object_key from source where id = ?",
                String.class,
                sourceId
        );
        assertThat(jdbcTemplate.queryForObject(
                "select compile_status from source where id = ?",
                String.class,
                sourceId
        )).isEqualTo("READY");
        assertThat(jdbcTemplate.queryForObject(
                "select compile_status from research_project where id = ?",
                String.class,
                projectId
        )).isEqualTo("READY");

        JsonNode reimport = reimportSource(ownerToken, sourceId);
        Long secondImportTaskId = reimport.path("data").path("taskId").asLong();
        assertThat(secondImportTaskId).isNotEqualTo(importTaskId);
        assertThat(jdbcTemplate.queryForObject(
                "select compile_status from source where id = ?",
                String.class,
                sourceId
        )).isEqualTo("PENDING");

        taskDispatcher.dispatchPendingMessages();
        waitForTaskStatus(secondImportTaskId, TaskStatus.SUCCESS);
        waitForSourceStatus(sourceId, "READY");

        String secondRawKey = jdbcTemplate.queryForObject(
                "select raw_text_object_key from source where id = ?",
                String.class,
                sourceId
        );
        assertThat(secondRawKey).contains("/raw-text/source/" + sourceId + "/2.txt");
        assertThat(secondRawKey).isNotEqualTo(firstRawKey);
        assertThat(readObjectAsString(secondRawKey)).contains("second body for import");
        assertThat(jdbcTemplate.queryForObject(
                "select compile_status from research_project where id = ?",
                String.class,
                projectId
        )).isEqualTo("PENDING");
    }

    @Test
    void archivedParentShouldHideSourceRejectReimportAndSkipPendingTask() throws Exception {
        String ownerToken = registerAndGetToken("phase6_archive_skip_owner_" + System.nanoTime());
        Long projectId = createProject(ownerToken, "Archive race project", null, null);
        String archivedUrl = url("/archived");

        JsonNode created = addUrlSource(ownerToken, projectId, archivedUrl, "Archived page");
        Long sourceId = created.path("data").path("id").asLong();
        Long taskId = created.path("data").path("taskId").asLong();

        jdbcTemplate.update(
                "update research_project set status = 'ARCHIVED', deleted_at = current_timestamp, deleted_by = ? where id = ?",
                999L,
                projectId
        );

        mockMvc.perform(get("/api/v1/personal/sources/{sourceId}", sourceId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SOURCE_NOT_FOUND"));

        mockMvc.perform(post("/api/v1/personal/sources/{sourceId}/import", sourceId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SOURCE_NOT_FOUND"));

        taskDispatcher.dispatchPendingMessages();
        waitForTaskStatus(taskId, TaskStatus.SUCCESS);

        JsonNode task = getTask(ownerToken, taskId);
        assertThat(task.path("data").path("output").path("skipped").asBoolean()).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "select import_status from source where id = ?",
                String.class,
                sourceId
        )).isEqualTo("PENDING");
        assertThat(jdbcTemplate.queryForObject(
                "select raw_text_object_key from source where id = ?",
                String.class,
                sourceId
        )).isNull();

        then(urlContentFetcher).should(never()).fetch(archivedUrl);
    }

    private Long createProject(String token, String title, String description, String goal) throws Exception {
        Map<String, Object> payload = new HashMap<>();
        payload.put("title", title);
        payload.put("description", description);
        payload.put("researchGoal", goal);
        MvcResult result = mockMvc.perform(post("/api/v1/personal/research-projects")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("id").asLong();
    }

    private JsonNode addTextSource(String token, Long projectId, String title, String content) throws Exception {
        Map<String, Object> payload = new HashMap<>();
        payload.put("title", title);
        payload.put("content", content);
        MvcResult result = mockMvc.perform(post("/api/v1/personal/research-projects/{projectId}/sources/text", projectId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode addUrlSource(String token, Long projectId, String url, String title) throws Exception {
        Map<String, Object> payload = new HashMap<>();
        payload.put("url", url);
        payload.put("title", title);
        MvcResult result = mockMvc.perform(post("/api/v1/personal/research-projects/{projectId}/sources/url", projectId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode uploadFileSource(
            String token,
            Long projectId,
            String fileName,
            String contentType,
            byte[] bytes,
            String title
    ) throws Exception {
        MockMultipartFile filePart = new MockMultipartFile("file", fileName, contentType, bytes);
        MockMultipartFile requestPart = new MockMultipartFile(
                "request",
                "",
                MediaType.APPLICATION_JSON_VALUE,
                objectMapper.writeValueAsBytes(Map.of("title", title))
        );
        MvcResult result = mockMvc.perform(multipart(HttpMethod.POST, "/api/v1/personal/research-projects/{projectId}/sources/upload", projectId)
                        .file(filePart)
                        .file(requestPart)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode reimportSource(String token, Long sourceId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/personal/sources/{sourceId}/import", sourceId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode getTask(String token, Long taskId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/tasks/{taskId}", taskId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private void waitForTaskStatus(Long taskId, TaskStatus expectedStatus) {
        long deadline = System.currentTimeMillis() + 10_000L;
        while (System.currentTimeMillis() < deadline) {
            String current = jdbcTemplate.queryForObject(
                    "select task_status from task where id = ?",
                    String.class,
                    taskId
            );
            if (expectedStatus.name().equals(current)) {
                return;
            }
            sleepBriefly();
        }
        throw new AssertionError("Timed out waiting for task " + taskId + " to reach " + expectedStatus);
    }

    private void waitForSourceStatus(Long sourceId, String expectedStatus) {
        long deadline = System.currentTimeMillis() + 10_000L;
        while (System.currentTimeMillis() < deadline) {
            String current = jdbcTemplate.queryForObject(
                    "select import_status from source where id = ?",
                    String.class,
                    sourceId
            );
            if (expectedStatus.equals(current)) {
                return;
            }
            sleepBriefly();
        }
        throw new AssertionError("Timed out waiting for source " + sourceId + " to reach " + expectedStatus);
    }

    private void sleepBriefly() {
        try {
            Thread.sleep(50L);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting", ex);
        }
    }

    private String registerAndGetToken(String username) throws Exception {
        Map<String, Object> payload = new HashMap<>();
        payload.put("username", username);
        payload.put("email", username + "@example.com");
        payload.put("password", "Password123!");
        payload.put("displayName", "Phase 6 User");

        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        return root.path("data").path("accessToken").asText();
    }

    private String url(String path) {
        return "https://example.test" + path;
    }

    private FetchedUrlContent fetchedHtml(String bodyText) {
        String html = "<html><body>" + bodyText + "</body></html>";
        return new FetchedUrlContent(html.getBytes(StandardCharsets.UTF_8), "text/html; charset=utf-8");
    }

    private LlmResponse llmResponse(String content) {
        return LlmResponse.builder()
                .provider("test")
                .model("phase6-test")
                .content(content)
                .inputTokens(16)
                .outputTokens(24)
                .latencyMs(1L)
                .build();
    }

    private String readObjectAsString(String objectKey) throws Exception {
        try (java.io.InputStream inputStream = fileStorageService.getObject(fileStorageService.testBucket(), objectKey)) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
