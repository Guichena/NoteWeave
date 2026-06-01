package com.noteweave.personal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.llm.dto.LlmResponse;
import com.noteweave.llm.service.LlmClient;
import com.noteweave.support.ContainerizedIntegrationTest;
import com.noteweave.task.model.TaskStatus;
import com.noteweave.task.service.TaskDispatcher;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class Phase7PersonalWikiCompilerIntegrationTest extends ContainerizedIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TaskDispatcher taskDispatcher;

    @MockBean
    private LlmClient llmClient;

    @Test
    void compileShouldCreateArticleConceptCardsAndBacktraceableCitations() throws Exception {
        String ownerToken = registerAndGetToken("phase7_owner_" + System.nanoTime());
        String outsiderToken = registerAndGetToken("phase7_outsider_" + System.nanoTime());
        Long projectId = createProject(ownerToken, "Phase 7 project", "compiler", "wiki");

        JsonNode source = addTextSource(
                ownerToken,
                projectId,
                "Vector retrieval notes",
                """
                RAG combines retrieval and generation.
                A vector store keeps embeddings for semantic search.
                """
        );
        Long sourceId = source.path("data").path("id").asLong();

        given(llmClient.chat(anyList(), any()))
                .willReturn(llmResponse(articleJson(sourceId)))
                .willReturn(llmResponse(conceptJson(sourceId)));

        JsonNode compile = compileSource(ownerToken, sourceId);
        Long taskId = compile.path("data").path("taskId").asLong();

        assertThat(compile.path("data").path("sourceId").asLong()).isEqualTo(sourceId);
        assertThat(compile.path("data").path("taskStatus").asText()).isEqualTo("PENDING");

        taskDispatcher.dispatchPendingMessages();
        waitForTaskStatus(taskId, TaskStatus.SUCCESS);
        waitForSourceCompileStatus(sourceId, "READY");

        Long articleCardId = jdbcTemplate.queryForObject(
                "select id from article_card where source_id = ?",
                Long.class,
                sourceId
        );
        Long ragConceptId = jdbcTemplate.queryForObject(
                "select id from concept_card where research_project_id = ? and normalized_name = ?",
                Long.class,
                projectId,
                "rag"
        );

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from article_card where research_project_id = ?",
                Integer.class,
                projectId
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from concept_card where research_project_id = ?",
                Integer.class,
                projectId
        )).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from article_concept_relation where article_card_id = ?",
                Integer.class,
                articleCardId
        )).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from concept_relation where research_project_id = ?",
                Integer.class,
                projectId
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from article_card_citation where article_card_id = ?",
                Integer.class,
                articleCardId
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from concept_card_citation where concept_card_id = ?",
                Integer.class,
                ragConceptId
        )).isEqualTo(1);
        Long autoWikiPageId = jdbcTemplate.queryForObject(
                "select id from wiki_page where source_personal_source_id = ? and auto_maintained = true",
                Long.class,
                sourceId
        );
        assertThat(autoWikiPageId).isNotNull();
        assertThat(jdbcTemplate.queryForObject(
                "select status from wiki_page where id = ?",
                String.class,
                autoWikiPageId
        )).isEqualTo("PUBLISHED");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from wiki_page_citation where wiki_page_id = ?",
                Integer.class,
                autoWikiPageId
        )).isGreaterThanOrEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from task where task_type = 'WIKI_INDEX' and target_id = ?",
                Integer.class,
                autoWikiPageId
        )).isGreaterThanOrEqualTo(1);
        Long spaceId = jdbcTemplate.queryForObject(
                "select space_id from research_project where id = ?",
                Long.class,
                projectId
        );

        mockMvc.perform(get("/api/v1/spaces/{spaceId}/knowledge-graph", spaceId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nodes[?(@.id=='SOURCE:%d')]".formatted(sourceId)).exists())
                .andExpect(jsonPath("$.data.nodes[?(@.id=='WIKI_PAGE:%d')]".formatted(autoWikiPageId)).exists())
                .andExpect(jsonPath("$.data.edges[?(@.type=='WIKI_SOURCE_PERSONAL_SOURCE')]").exists())
                .andExpect(jsonPath("$.data.edges[?(@.type=='WIKI_CITES_SOURCE')]").exists())
                .andExpect(jsonPath("$.data.edges[?(@.type=='ARTICLE_CITES_SOURCE')]").exists())
                .andExpect(jsonPath("$.data.edges[?(@.type=='CONCEPT_CITES_SOURCE')]").exists());

        mockMvc.perform(get("/api/v1/personal/research-projects/{projectId}/article-cards", projectId)
                        .queryParam("keyword", "vector")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(articleCardId))
                .andExpect(jsonPath("$.data[0].title").value("Vector retrieval notes"));

        mockMvc.perform(get("/api/v1/personal/article-cards/{cardId}", articleCardId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary").value("A concise overview of retrieval-augmented generation basics."))
                .andExpect(jsonPath("$.data.citations[0].sourceType").value("SOURCE"))
                .andExpect(jsonPath("$.data.citations[0].sourceId").value(sourceId))
                .andExpect(jsonPath("$.data.citations[0].startOffset").isNumber())
                .andExpect(jsonPath("$.data.relatedConcepts[0].name").exists());

        mockMvc.perform(get("/api/v1/personal/research-projects/{projectId}/article-cards", projectId)
                        .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("RESEARCH_PROJECT_ACCESS_DENIED"));

        mockMvc.perform(get("/api/v1/personal/research-projects/{projectId}/concept-cards", projectId)
                        .queryParam("keyword", "embedding")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("Vector Store"));

        mockMvc.perform(get("/api/v1/personal/concept-cards/{cardId}", ragConceptId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("RAG"))
                .andExpect(jsonPath("$.data.aliases[0]").value("Retrieval-Augmented Generation"))
                .andExpect(jsonPath("$.data.citations[0].sourceId").value(sourceId))
                .andExpect(jsonPath("$.data.relations[0].relationType").value("USES"))
                .andExpect(jsonPath("$.data.relatedArticles[0].title").value("Vector retrieval notes"));
    }

    @Test
    void deletingSourceShouldArchiveAutoMaintainedWikiPage() throws Exception {
        String ownerToken = registerAndGetToken("phase7_auto_delete_" + System.nanoTime());
        Long projectId = createProject(ownerToken, "Auto Wiki delete project", null, null);
        Long sourceId = addTextSource(
                ownerToken,
                projectId,
                "Auto source",
                "Personal auto wiki pages should leave active views when the source is deleted."
        ).path("data").path("id").asLong();

        given(llmClient.chat(anyList(), any()))
                .willReturn(llmResponse(singleArticleJson(sourceId, "Auto source", "Auto summary", "Personal auto wiki pages should leave active views when the source is deleted.")))
                .willReturn(llmResponse(singleConceptJson(sourceId, "Auto Wiki Source", "Auto Source", "Personal auto wiki pages should leave active views when the source is deleted.")));

        Long taskId = compileSource(ownerToken, sourceId).path("data").path("taskId").asLong();
        taskDispatcher.dispatchPendingMessages();
        waitForTaskStatus(taskId, TaskStatus.SUCCESS);
        waitForSourceCompileStatus(sourceId, "READY");

        Long autoWikiPageId = jdbcTemplate.queryForObject(
                "select id from wiki_page where source_personal_source_id = ? and auto_maintained = true",
                Long.class,
                sourceId
        );

        mockMvc.perform(delete("/api/v1/personal/sources/{sourceId}", sourceId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());

        assertThat(jdbcTemplate.queryForObject(
                "select status from wiki_page where id = ?",
                String.class,
                autoWikiPageId
        )).isEqualTo("ARCHIVED");
        assertThat(jdbcTemplate.queryForObject(
                "select deleted_at is not null from wiki_page where id = ?",
                Boolean.class,
                autoWikiPageId
        )).isTrue();
    }

    @Test
    void compileShouldAcceptFencedOrPrefixedJsonFromLlm() throws Exception {
        String ownerToken = registerAndGetToken("phase7_fenced_json_" + System.nanoTime());
        Long projectId = createProject(ownerToken, "Fenced json project", null, null);
        Long sourceId = addTextSource(ownerToken, projectId, "Fenced source", "Gemini can wrap JSON in a code fence.")
                .path("data")
                .path("id")
                .asLong();

        given(llmClient.chat(anyList(), any()))
                .willReturn(llmResponse("```json\n" + singleArticleJson(sourceId, "Fenced source", "Fenced summary", "Gemini can wrap JSON in a code fence.") + "\n```"))
                .willReturn(llmResponse("Here is the JSON:\n" + singleConceptJson(sourceId, "Fenced Concept", "Fenced Alias", "Gemini can wrap JSON in a code fence.")));

        Long taskId = compileSource(ownerToken, sourceId).path("data").path("taskId").asLong();
        taskDispatcher.dispatchPendingMessages();

        waitForTaskStatus(taskId, TaskStatus.SUCCESS);
        waitForSourceCompileStatus(sourceId, "READY");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from concept_card where research_project_id = ? and normalized_name = 'fenced concept'",
                Integer.class,
                projectId
        )).isEqualTo(1);
    }

    @Test
    void compileShouldFallbackToSourceQuoteWhenLlmEvidenceQuoteIsBlank() throws Exception {
        String ownerToken = registerAndGetToken("phase7_blank_quote_" + System.nanoTime());
        Long projectId = createProject(ownerToken, "Blank quote project", null, null);
        Long sourceId = addTextSource(
                ownerToken,
                projectId,
                "Fallback source",
                """
                Fallback quote should be copied from source text.
                The compiler should not fail the whole source when the model omits an evidence quote.
                """
        ).path("data").path("id").asLong();

        given(llmClient.chat(anyList(), any()))
                .willReturn(llmResponse(singleArticleJson(sourceId, "Fallback source", "Fallback summary", "")))
                .willReturn(llmResponse(singleConceptJson(sourceId, "Fallback Concept", "Fallback Alias", "")));

        Long taskId = compileSource(ownerToken, sourceId).path("data").path("taskId").asLong();
        taskDispatcher.dispatchPendingMessages();

        waitForTaskStatus(taskId, TaskStatus.SUCCESS);
        waitForSourceCompileStatus(sourceId, "READY");
        assertThat(jdbcTemplate.queryForObject(
                "select evidence_quotes_json from article_card where source_id = ?",
                String.class,
                sourceId
        )).contains("Fallback quote should be copied from source text.");
        assertThat(jdbcTemplate.queryForObject(
                "select evidence_quotes_json from concept_card where research_project_id = ? and normalized_name = 'fallback concept'",
                String.class,
                projectId
        )).contains("Fallback quote should be copied from source text.");
    }

    @Test
    void compileShouldRejectReadySourceWithoutReadableText() throws Exception {
        String ownerToken = registerAndGetToken("phase7_missing_text_" + System.nanoTime());
        Long projectId = createProject(ownerToken, "Missing text", null, null);
        Long sourceId = addTextSource(ownerToken, projectId, "Broken source", "text that will be detached")
                .path("data")
                .path("id")
                .asLong();

        jdbcTemplate.update(
                "update source set raw_text_object_key = null, parsed_text_object_key = null, import_status = 'READY' where id = ?",
                sourceId
        );

        mockMvc.perform(post("/api/v1/personal/sources/{sourceId}/compile", sourceId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SOURCE_NOT_READY"));

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from task where task_type = 'SOURCE_COMPILE' and target_id = ?",
                Integer.class,
                sourceId
        )).isZero();
    }

    @Test
    void compileShouldFailAndPersistDiagnosticErrorWhenLlmJsonIsInvalid() throws Exception {
        String ownerToken = registerAndGetToken("phase7_invalid_json_" + System.nanoTime());
        Long projectId = createProject(ownerToken, "Invalid json", null, null);
        Long sourceId = addTextSource(ownerToken, projectId, "Broken compile", "RAG still needs strict JSON.")
                .path("data")
                .path("id")
                .asLong();

        given(llmClient.chat(anyList(), any()))
                .willReturn(llmResponse("not-json-at-all"));

        Long taskId = compileSource(ownerToken, sourceId).path("data").path("taskId").asLong();
        taskDispatcher.dispatchPendingMessages();

        waitForTaskStatus(taskId, TaskStatus.FAILED);
        waitForSourceCompileStatus(sourceId, "FAILED");

        assertThat(jdbcTemplate.queryForObject(
                "select error_message from source where id = ?",
                String.class,
                sourceId
        )).contains("json");
        assertThat(jdbcTemplate.queryForObject(
                "select error_message from task where id = ?",
                String.class,
                taskId
        )).contains("json");
    }

    @Test
    void compileFailureShouldRollbackPartialArticleAndCitationWrites() throws Exception {
        String ownerToken = registerAndGetToken("phase7_atomic_owner_" + System.nanoTime());
        Long projectId = createProject(ownerToken, "Atomic compile", null, null);
        Long sourceId = addTextSource(ownerToken, projectId, "Atomic source", "Atomic evidence quote.")
                .path("data")
                .path("id")
                .asLong();

        given(llmClient.chat(anyList(), any()))
                .willReturn(llmResponse(singleArticleJson(sourceId, "Atomic source", "Atomic summary", "Atomic evidence quote.")))
                .willReturn(llmResponse("not-json-at-all"));

        Long taskId = compileSource(ownerToken, sourceId).path("data").path("taskId").asLong();
        taskDispatcher.dispatchPendingMessages();

        waitForTaskStatus(taskId, TaskStatus.FAILED);
        waitForSourceCompileStatus(sourceId, "FAILED");

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from article_card where source_id = ?",
                Integer.class,
                sourceId
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from article_card_citation acc join article_card ac on ac.id = acc.article_card_id where ac.source_id = ?",
                Integer.class
                ,
                sourceId
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from concept_card where research_project_id = ?",
                Integer.class,
                projectId
        )).isZero();
    }

    @Test
    void sameProjectShouldMergeNormalizedConceptAndCrossProjectShouldStayIsolated() throws Exception {
        String ownerToken = registerAndGetToken("phase7_merge_owner_" + System.nanoTime());
        Long projectA = createProject(ownerToken, "Project A", null, null);
        Long projectB = createProject(ownerToken, "Project B", null, null);

        Long sourceA1 = addTextSource(ownerToken, projectA, "A1", "RAG combines retrieval and generation.")
                .path("data").path("id").asLong();
        given(llmClient.chat(anyList(), any()))
                .willReturn(llmResponse(articleJson(sourceA1)))
                .willReturn(llmResponse(singleConceptJson(sourceA1, "RAG", "Retrieval-Augmented Generation", "RAG combines retrieval and generation.")));
        Long taskA1 = compileSource(ownerToken, sourceA1).path("data").path("taskId").asLong();
        taskDispatcher.dispatchPendingMessages();
        waitForTaskStatus(taskA1, TaskStatus.SUCCESS);
        waitForSourceCompileStatus(sourceA1, "READY");

        Long sourceA2 = addTextSource(ownerToken, projectA, "A2", "rag needs good retrieval quality.")
                .path("data").path("id").asLong();
        given(llmClient.chat(anyList(), any()))
                .willReturn(llmResponse(articleJson(sourceA2)))
                .willReturn(llmResponse(singleConceptJson(sourceA2, "rag", "RAG pipeline", "rag needs good retrieval quality.")));
        Long taskA2 = compileSource(ownerToken, sourceA2).path("data").path("taskId").asLong();
        taskDispatcher.dispatchPendingMessages();
        waitForTaskStatus(taskA2, TaskStatus.SUCCESS);
        waitForSourceCompileStatus(sourceA2, "READY");

        Long sourceB1 = addTextSource(ownerToken, projectB, "B1", "RAG in another project should stay isolated.")
                .path("data").path("id").asLong();
        given(llmClient.chat(anyList(), any()))
                .willReturn(llmResponse(articleJson(sourceB1)))
                .willReturn(llmResponse(singleConceptJson(sourceB1, "RAG", "Retrieval-Augmented Generation", "RAG in another project should stay isolated.")));
        Long taskB1 = compileSource(ownerToken, sourceB1).path("data").path("taskId").asLong();
        taskDispatcher.dispatchPendingMessages();
        waitForTaskStatus(taskB1, TaskStatus.SUCCESS);
        waitForSourceCompileStatus(sourceB1, "READY");

        Long projectAConceptId = jdbcTemplate.queryForObject(
                "select id from concept_card where research_project_id = ? and normalized_name = 'rag'",
                Long.class,
                projectA
        );

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from concept_card where research_project_id = ?",
                Integer.class,
                projectA
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from concept_card where research_project_id = ?",
                Integer.class,
                projectB
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from concept_card_citation where concept_card_id = ?",
                Integer.class,
                projectAConceptId
        )).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from article_concept_relation where concept_card_id = ?",
                Integer.class,
                projectAConceptId
        )).isEqualTo(2);

        JsonNode mergedConcept = objectMapper.readTree(mockMvc.perform(get("/api/v1/personal/concept-cards/{cardId}", projectAConceptId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString())
                .path("data");
        assertThat(mergedConcept.path("evidenceQuotes").toString())
                .contains("\"sourceId\":" + sourceA1)
                .contains("\"sourceId\":" + sourceA2);
    }

    @Test
    void manualConceptUpdateAndMergeShouldPreserveEvidenceAndRejectCrossProjectMerge() throws Exception {
        String ownerToken = registerAndGetToken("phase7_manual_merge_" + System.nanoTime());
        Long projectA = createProject(ownerToken, "Manual merge project", null, null);
        Long projectB = createProject(ownerToken, "Manual merge isolated", null, null);

        Long sourceA1 = addTextSource(ownerToken, projectA, "Retriever note", "Retriever fetches relevant chunks.")
                .path("data").path("id").asLong();
        given(llmClient.chat(anyList(), any()))
                .willReturn(llmResponse(singleArticleJson(sourceA1, "Retriever note", "Retriever overview", "Retriever fetches relevant chunks.")))
                .willReturn(llmResponse(singleConceptJson(sourceA1, "Retriever", "Chunk Retriever", "Retriever fetches relevant chunks.")));
        Long taskA1 = compileSource(ownerToken, sourceA1).path("data").path("taskId").asLong();
        taskDispatcher.dispatchPendingMessages();
        waitForTaskStatus(taskA1, TaskStatus.SUCCESS);
        waitForSourceCompileStatus(sourceA1, "READY");

        Long sourceA2 = addTextSource(ownerToken, projectA, "Planner note", "Search planner orchestrates retrieval.")
                .path("data").path("id").asLong();
        given(llmClient.chat(anyList(), any()))
                .willReturn(llmResponse(singleArticleJson(sourceA2, "Planner note", "Planner overview", "Search planner orchestrates retrieval.")))
                .willReturn(llmResponse(singleConceptJson(sourceA2, "Search Planner", "Retrieval Planner", "Search planner orchestrates retrieval.")));
        Long taskA2 = compileSource(ownerToken, sourceA2).path("data").path("taskId").asLong();
        taskDispatcher.dispatchPendingMessages();
        waitForTaskStatus(taskA2, TaskStatus.SUCCESS);
        waitForSourceCompileStatus(sourceA2, "READY");

        Long sourceB1 = addTextSource(ownerToken, projectB, "Isolated note", "Isolated concept belongs to another project.")
                .path("data").path("id").asLong();
        given(llmClient.chat(anyList(), any()))
                .willReturn(llmResponse(singleArticleJson(sourceB1, "Isolated note", "Isolated overview", "Isolated concept belongs to another project.")))
                .willReturn(llmResponse(singleConceptJson(sourceB1, "Isolated Concept", "Project B Concept", "Isolated concept belongs to another project.")));
        Long taskB1 = compileSource(ownerToken, sourceB1).path("data").path("taskId").asLong();
        taskDispatcher.dispatchPendingMessages();
        waitForTaskStatus(taskB1, TaskStatus.SUCCESS);
        waitForSourceCompileStatus(sourceB1, "READY");

        Long retrieverConceptId = jdbcTemplate.queryForObject(
                "select id from concept_card where research_project_id = ? and normalized_name = 'retriever'",
                Long.class,
                projectA
        );
        Long plannerConceptId = jdbcTemplate.queryForObject(
                "select id from concept_card where research_project_id = ? and normalized_name = 'search planner'",
                Long.class,
                projectA
        );
        Long isolatedConceptId = jdbcTemplate.queryForObject(
                "select id from concept_card where research_project_id = ? and normalized_name = 'isolated concept'",
                Long.class,
                projectB
        );

        Map<String, Object> updatePayload = new HashMap<>();
        updatePayload.put("definition", "Updated retriever definition");
        updatePayload.put("explanation", "Updated retriever explanation");
        updatePayload.put("useCases", java.util.List.of("Grounded retrieval"));
        updatePayload.put("commonMisunderstandings", java.util.List.of("It works without indexing"));

        mockMvc.perform(put("/api/v1/personal/concept-cards/{cardId}", retrieverConceptId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updatePayload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.definition").value("Updated retriever definition"))
                .andExpect(jsonPath("$.data.explanation").value("Updated retriever explanation"))
                .andExpect(jsonPath("$.data.useCases[0]").value("Grounded retrieval"));

        Map<String, Object> sameProjectMergePayload = new HashMap<>();
        sameProjectMergePayload.put("targetConceptId", retrieverConceptId);
        sameProjectMergePayload.put("sourceConceptIds", java.util.List.of(plannerConceptId));

        mockMvc.perform(post("/api/v1/personal/concept-cards/merge")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sameProjectMergePayload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(retrieverConceptId))
                .andExpect(jsonPath("$.data.aliases[0]").value("Chunk Retriever"))
                .andExpect(jsonPath("$.data.aliases[1]").value("Retrieval Planner"));

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from concept_card where research_project_id = ?",
                Integer.class,
                projectA
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from concept_card_citation where concept_card_id = ?",
                Integer.class,
                retrieverConceptId
        )).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from article_concept_relation where concept_card_id = ?",
                Integer.class,
                retrieverConceptId
        )).isEqualTo(2);

        Map<String, Object> crossProjectMergePayload = new HashMap<>();
        crossProjectMergePayload.put("targetConceptId", retrieverConceptId);
        crossProjectMergePayload.put("sourceConceptIds", java.util.List.of(isolatedConceptId));

        mockMvc.perform(post("/api/v1/personal/concept-cards/merge")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(crossProjectMergePayload)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CONCEPT_MERGE_INVALID"));

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from concept_card where research_project_id = ?",
                Integer.class,
                projectB
        )).isEqualTo(1);
    }

    @Test
    void archivedProjectShouldHideDirectCardsAndBlockConceptWriteOperations() throws Exception {
        String ownerToken = registerAndGetToken("phase7_archived_cards_" + System.nanoTime());
        Long projectId = createProject(ownerToken, "Archived cards project", null, null);
        Long sourceId = addTextSource(ownerToken, projectId, "Archived source", "Archived card evidence.")
                .path("data").path("id").asLong();

        given(llmClient.chat(anyList(), any()))
                .willReturn(llmResponse(singleArticleJson(sourceId, "Archived source", "Archived summary", "Archived card evidence.")))
                .willReturn(llmResponse(singleConceptJson(sourceId, "Archived Concept", "Archived Alias", "Archived card evidence.")));
        Long taskId = compileSource(ownerToken, sourceId).path("data").path("taskId").asLong();
        taskDispatcher.dispatchPendingMessages();
        waitForTaskStatus(taskId, TaskStatus.SUCCESS);
        waitForSourceCompileStatus(sourceId, "READY");

        Long articleCardId = jdbcTemplate.queryForObject(
                "select id from article_card where source_id = ?",
                Long.class,
                sourceId
        );
        Long conceptCardId = jdbcTemplate.queryForObject(
                "select id from concept_card where research_project_id = ? and normalized_name = 'archived concept'",
                Long.class,
                projectId
        );

        jdbcTemplate.update(
                "update research_project set status = 'ARCHIVED', deleted_at = current_timestamp, deleted_by = ? where id = ?",
                777L,
                projectId
        );

        mockMvc.perform(get("/api/v1/personal/article-cards/{cardId}", articleCardId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ARTICLE_CARD_NOT_FOUND"));

        mockMvc.perform(get("/api/v1/personal/concept-cards/{cardId}", conceptCardId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CONCEPT_CARD_NOT_FOUND"));

        mockMvc.perform(put("/api/v1/personal/concept-cards/{cardId}", conceptCardId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "definition", "updated",
                                "explanation", "updated",
                                "useCases", java.util.List.of("one"),
                                "commonMisunderstandings", java.util.List.of("two")
                        ))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CONCEPT_CARD_NOT_FOUND"));
    }

    @Test
    void manualMergeShouldPreserveSourceFieldsAndEvidenceQuotes() throws Exception {
        String ownerToken = registerAndGetToken("phase7_merge_preserve_" + System.nanoTime());
        Long projectId = createProject(ownerToken, "Merge preserve project", null, null);

        Long sourceA = addTextSource(ownerToken, projectId, "Source A", "Retriever evidence quote.")
                .path("data").path("id").asLong();
        given(llmClient.chat(anyList(), any()))
                .willReturn(llmResponse(singleArticleJson(sourceA, "Source A", "Summary A", "Retriever evidence quote.")))
                .willReturn(llmResponse("""
                        {
                          "concepts": [
                            {
                              "name": "Retriever",
                              "aliases": ["Chunk Retriever"],
                              "definition": "Definition A.",
                              "explanation": "Explanation A.",
                              "useCases": ["Use A"],
                              "commonMisunderstandings": ["Mistake A"],
                              "evidence": {"sourceId": %d, "quote": "Retriever evidence quote."},
                              "confidence": 0.91
                            }
                          ],
                          "relations": []
                        }
                        """.formatted(sourceA)));
        Long taskA = compileSource(ownerToken, sourceA).path("data").path("taskId").asLong();
        taskDispatcher.dispatchPendingMessages();
        waitForTaskStatus(taskA, TaskStatus.SUCCESS);

        Long sourceB = addTextSource(ownerToken, projectId, "Source B", "Planner evidence quote.")
                .path("data").path("id").asLong();
        given(llmClient.chat(anyList(), any()))
                .willReturn(llmResponse(singleArticleJson(sourceB, "Source B", "Summary B", "Planner evidence quote.")))
                .willReturn(llmResponse("""
                        {
                          "concepts": [
                            {
                              "name": "Planner",
                              "aliases": ["Retrieval Planner"],
                              "definition": "Definition B.",
                              "explanation": "Explanation B.",
                              "useCases": ["Use B"],
                              "commonMisunderstandings": ["Mistake B"],
                              "evidence": {"sourceId": %d, "quote": "Planner evidence quote."},
                              "confidence": 0.92
                            }
                          ],
                          "relations": []
                        }
                        """.formatted(sourceB)));
        Long taskB = compileSource(ownerToken, sourceB).path("data").path("taskId").asLong();
        taskDispatcher.dispatchPendingMessages();
        waitForTaskStatus(taskB, TaskStatus.SUCCESS);

        Long retrieverConceptId = jdbcTemplate.queryForObject(
                "select id from concept_card where research_project_id = ? and normalized_name = 'retriever'",
                Long.class,
                projectId
        );
        Long plannerConceptId = jdbcTemplate.queryForObject(
                "select id from concept_card where research_project_id = ? and normalized_name = 'planner'",
                Long.class,
                projectId
        );

        mockMvc.perform(post("/api/v1/personal/concept-cards/merge")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "targetConceptId", retrieverConceptId,
                                "sourceConceptIds", java.util.List.of(plannerConceptId)
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.aliases[0]").value("Chunk Retriever"))
                .andExpect(jsonPath("$.data.aliases[1]").value("Retrieval Planner"));

        JsonNode merged = objectMapper.readTree(mockMvc.perform(get("/api/v1/personal/concept-cards/{cardId}", retrieverConceptId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString())
                .path("data");

        assertThat(merged.path("useCases").toString()).contains("Use A").contains("Use B");
        assertThat(merged.path("commonMisunderstandings").toString()).contains("Mistake A").contains("Mistake B");
        assertThat(merged.path("evidenceQuotes").toString()).contains("Retriever evidence quote.").contains("Planner evidence quote.");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from concept_card where research_project_id = ?",
                Integer.class,
                projectId
        )).isEqualTo(1);
    }

    @Test
    void retryCompileShouldRevalidateSourceReadiness() throws Exception {
        String ownerToken = registerAndGetToken("phase7_retry_owner_" + System.nanoTime());
        Long projectId = createProject(ownerToken, "Retry readiness project", null, null);
        Long sourceId = addTextSource(ownerToken, projectId, "Retry source", "Retry source text.")
                .path("data").path("id").asLong();

        given(llmClient.chat(anyList(), any()))
                .willReturn(llmResponse("not-json-at-all"));

        Long taskId = compileSource(ownerToken, sourceId).path("data").path("taskId").asLong();
        taskDispatcher.dispatchPendingMessages();
        waitForTaskStatus(taskId, TaskStatus.FAILED);

        jdbcTemplate.update(
                "update source set import_status = 'PENDING', raw_text_object_key = null, parsed_text_object_key = null where id = ?",
                sourceId
        );

        mockMvc.perform(post("/api/v1/tasks/{taskId}/retry", taskId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskStatus").value("PENDING"));

        taskDispatcher.dispatchPendingMessages();
        waitForTaskStatus(taskId, TaskStatus.FAILED);

        assertThat(jdbcTemplate.queryForObject(
                "select compile_status from source where id = ?",
                String.class,
                sourceId
        )).isEqualTo("FAILED");
        assertThat(jdbcTemplate.queryForObject(
                "select error_message from task where id = ?",
                String.class,
                taskId
        )).contains("not ready");
    }

    private JsonNode compileSource(String token, Long sourceId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/personal/sources/{sourceId}/compile", sourceId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
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

    private String registerAndGetToken(String username) throws Exception {
        Map<String, Object> payload = new HashMap<>();
        payload.put("username", username);
        payload.put("email", username + "@example.com");
        payload.put("password", "Password123!");
        payload.put("displayName", "Phase 7 User");

        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andReturn();

        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("accessToken").asText();
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

    private void waitForSourceCompileStatus(Long sourceId, String expectedStatus) {
        long deadline = System.currentTimeMillis() + 10_000L;
        while (System.currentTimeMillis() < deadline) {
            String current = jdbcTemplate.queryForObject(
                    "select compile_status from source where id = ?",
                    String.class,
                    sourceId
            );
            if (expectedStatus.equals(current)) {
                return;
            }
            sleepBriefly();
        }
        throw new AssertionError("Timed out waiting for source " + sourceId + " compile status to reach " + expectedStatus);
    }

    private void sleepBriefly() {
        try {
            Thread.sleep(50L);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting", ex);
        }
    }

    private LlmResponse llmResponse(String content) {
        return LlmResponse.builder()
                .provider("test")
                .model("phase7-test")
                .content(content)
                .inputTokens(32)
                .outputTokens(48)
                .latencyMs(1L)
                .build();
    }

    private String articleJson(Long sourceId) {
        return """
                {
                  "title": "Vector retrieval notes",
                  "summary": "A concise overview of retrieval-augmented generation basics.",
                  "keyPoints": ["RAG combines retrieval and generation", "Vector stores keep embeddings"],
                  "tags": ["RAG", "Vector Search"],
                  "evidenceQuotes": [
                    {
                      "quote": "RAG combines retrieval and generation.",
                      "sourceId": %d,
                      "reason": "Defines the central topic."
                    }
                  ]
                }
                """.formatted(sourceId);
    }

    private String singleArticleJson(Long sourceId, String title, String summary, String quote) {
        return """
                {
                  "title": "%s",
                  "summary": "%s",
                  "keyPoints": ["%s"],
                  "tags": ["Phase 7"],
                  "evidenceQuotes": [
                    {
                      "quote": "%s",
                      "sourceId": %d,
                      "reason": "Primary evidence for the article card."
                    }
                  ]
                }
                """.formatted(title, summary, quote, quote, sourceId);
    }

    private String conceptJson(Long sourceId) {
        return """
                {
                  "concepts": [
                    {
                      "name": "RAG",
                      "aliases": ["Retrieval-Augmented Generation"],
                      "definition": "A pattern that augments generation with retrieval.",
                      "explanation": "RAG fetches relevant context before generation.",
                      "useCases": ["Grounded assistants"],
                      "commonMisunderstandings": ["It removes the need for retrieval quality work"],
                      "evidence": {
                        "sourceId": %d,
                        "quote": "RAG combines retrieval and generation."
                      },
                      "confidence": 0.95
                    },
                    {
                      "name": "Vector Store",
                      "aliases": ["Embedding Index"],
                      "definition": "A storage layer for vector embeddings.",
                      "explanation": "It supports semantic retrieval over embeddings.",
                      "useCases": ["Semantic search"],
                      "commonMisunderstandings": ["It is the same as a relational database"],
                      "evidence": {
                        "sourceId": %d,
                        "quote": "A vector store keeps embeddings for semantic search."
                      },
                      "confidence": 0.90
                    }
                  ],
                  "relations": [
                    {
                      "sourceName": "RAG",
                      "targetName": "Vector Store",
                      "relationType": "USES",
                      "description": "RAG commonly relies on a vector store for retrieval."
                    }
                  ]
                }
                """.formatted(sourceId, sourceId);
    }

    private String singleConceptJson(Long sourceId, String name, String alias, String quote) {
        return """
                {
                  "concepts": [
                    {
                      "name": "%s",
                      "aliases": ["%s"],
                      "definition": "Definition for %s.",
                      "explanation": "%s is important in this source.",
                      "useCases": ["Grounded answers"],
                      "commonMisunderstandings": ["It works without retrieval quality"],
                      "evidence": {
                        "sourceId": %d,
                        "quote": "%s"
                      },
                      "confidence": 0.93
                    }
                  ],
                  "relations": []
                }
                """.formatted(name, alias, name, name, sourceId, quote);
    }
}
