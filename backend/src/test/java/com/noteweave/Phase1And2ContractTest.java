package com.noteweave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.storage.ObjectStorage;
import com.noteweave.chat.RetrievalHydrator;
import com.noteweave.answer.strategy.AnswerContext;
import com.noteweave.answer.strategy.EvidenceBundle;
import com.noteweave.answer.strategy.EvidenceOwnershipGuard;
import com.noteweave.common.BusinessException;
import com.noteweave.infra.JdbcEvidenceOwnershipAdapter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class Phase1And2ContractTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectStorage storage;

    @Test
    void retrievalHydrationShouldExcludeSupersededSourceSnapshots() throws Exception {
        String workspaceId = createWorkspace();
        String fileObjectId = UUID.randomUUID().toString();
        String sourceId = UUID.randomUUID().toString();
        String oldSnapshotId = UUID.randomUUID().toString();
        String currentSnapshotId = UUID.randomUUID().toString();
        String oldChunkId = UUID.randomUUID().toString();
        String currentChunkId = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                insert into file_object(id, workspace_id, object_key, sha256, file_size, mime_type)
                values (?, ?, ?, ?, 1, 'text/plain')
                """, fileObjectId, workspaceId, "test/current-snapshot.txt", "a".repeat(64));
        jdbcTemplate.update("""
                insert into source(
                    id, workspace_id, file_object_id, title, source_type, status,
                    parse_status, index_status
                ) values (?, ?, ?, 'versioned-source', 'USER_UPLOAD', 'READY', 'PARSED', 'INDEXED')
                """, sourceId, workspaceId, fileObjectId);
        jdbcTemplate.update("""
                insert into source_snapshot(
                    id, source_id, file_object_id, version_no, object_key, sha256,
                    parse_status, index_status
                ) values (?, ?, ?, 1, 'snapshot/1', ?, 'PARSED', 'INDEXED')
                """, oldSnapshotId, sourceId, fileObjectId, "b".repeat(64));
        jdbcTemplate.update("""
                insert into source_snapshot(
                    id, source_id, file_object_id, version_no, object_key, sha256,
                    parse_status, index_status
                ) values (?, ?, ?, 2, 'snapshot/2', ?, 'PARSED', 'INDEXED')
                """, currentSnapshotId, sourceId, fileObjectId, "c".repeat(64));
        jdbcTemplate.update("""
                insert into source_chunk(
                    id, workspace_id, source_id, source_snapshot_id, chunk_no, content,
                    token_estimate, projection_status
                ) values (?, ?, ?, ?, 0, 'superseded-content', 1, 'PROJECTED')
                """, oldChunkId, workspaceId, sourceId, oldSnapshotId);
        jdbcTemplate.update("""
                insert into source_chunk(
                    id, workspace_id, source_id, source_snapshot_id, chunk_no, content,
                    token_estimate, projection_status
                ) values (?, ?, ?, ?, 0, 'current-content', 1, 'PROJECTED')
                """, currentChunkId, workspaceId, sourceId, currentSnapshotId);
        jdbcTemplate.update("""
                insert into source_window(id, source_chunk_id, window_no, content)
                values (?, ?, 0, 'superseded-window')
                """, UUID.randomUUID().toString(), oldChunkId);
        jdbcTemplate.update("""
                insert into source_window(id, source_chunk_id, window_no, content)
                values (?, ?, 0, 'current-window')
                """, UUID.randomUUID().toString(), currentChunkId);

        RetrievalHydrator hydrator = new RetrievalHydrator(jdbcTemplate);
        assertThat(hydrator.hydratePassageOwnership(
                workspaceId, List.of(oldChunkId, currentChunkId)))
                .containsOnlyKeys(currentChunkId);
        assertThat(hydrator.hydrateNoteWindows(workspaceId, List.of(sourceId)).get(sourceId))
                .extracting(window -> window.sourceSnapshotId())
                .containsExactly(currentSnapshotId);

        String knowledgeItemId = UUID.randomUUID().toString();
        String oldKnowledgeVersionId = UUID.randomUUID().toString();
        String knowledgeVersionId = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                insert into knowledge_item(
                    id, workspace_id, item_type, title, status, latest_version_id
                ) values (?, ?, 'WIKI', 'owned-page', 'ACTIVE', null)
                """, knowledgeItemId, workspaceId);
        jdbcTemplate.update("""
                insert into knowledge_version(id, item_id, version_no, content)
                values (?, ?, 1, 'superseded-content')
                """, oldKnowledgeVersionId, knowledgeItemId);
        jdbcTemplate.update("""
                insert into knowledge_version(id, item_id, version_no, content)
                values (?, ?, 2, 'owned-content')
                """, knowledgeVersionId, knowledgeItemId);
        jdbcTemplate.update("""
                update knowledge_item set latest_version_id = ? where id = ?
                """, knowledgeVersionId, knowledgeItemId);
        EvidenceOwnershipGuard ownershipGuard = new EvidenceOwnershipGuard(
                new JdbcEvidenceOwnershipAdapter(jdbcTemplate));
        List<EvidenceBundle.Evidence> currentEvidence = List.of(
                new EvidenceBundle.Evidence(
                        "passage:" + currentChunkId, "PASSAGE", sourceId, currentSnapshotId,
                        currentChunkId, "", "", "", "", "", 1, 1, 1,
                        "workspace-source:" + sourceId, Instant.now(), "test", 1, Map.of()),
                new EvidenceBundle.Evidence(
                        "knowledge-version:" + knowledgeVersionId, "KNOWLEDGE_VERSION",
                        "", "", "", knowledgeItemId, knowledgeVersionId,
                        "", "", "", 1, 1, 1,
                        "workspace-knowledge:" + knowledgeItemId,
                        Instant.now(), "test", 1, Map.of())
        );
        ownershipGuard.requireCurrent(
                new AnswerContext(
                        workspaceId, "conversation", "message", "query",
                        java.util.Set.of(), Map.of(), Instant.now()),
                currentEvidence
        );
        String otherWorkspaceId = createWorkspace();
        AnswerContext otherWorkspaceContext = new AnswerContext(
                otherWorkspaceId, "conversation", "message", "query",
                java.util.Set.of(), Map.of(), Instant.now());
        assertThatThrownBy(() -> ownershipGuard.requireCurrent(
                otherWorkspaceContext,
                List.of(currentEvidence.get(0))
        )).isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).code())
                .isEqualTo("EVIDENCE_SCOPE_VIOLATION");
        assertThatThrownBy(() -> ownershipGuard.requireCurrent(
                otherWorkspaceContext,
                List.of(currentEvidence.get(1))
        )).isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).code())
                .isEqualTo("EVIDENCE_SCOPE_VIOLATION");
        List<EvidenceBundle.Evidence> supersededKnowledgeEvidence = List.of(
                new EvidenceBundle.Evidence(
                        "knowledge-version:" + oldKnowledgeVersionId,
                        "KNOWLEDGE_VERSION", "", "", "", knowledgeItemId,
                        oldKnowledgeVersionId, "", "", "", 1, 1, 1,
                        "workspace-knowledge:" + knowledgeItemId,
                        Instant.now(), "test", 1, Map.of())
        );
        assertThatThrownBy(() -> ownershipGuard.requireCurrent(
                new AnswerContext(
                        workspaceId, "conversation", "message", "query",
                        java.util.Set.of(), Map.of(), Instant.now()),
                supersededKnowledgeEvidence
        )).isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).code())
                .isEqualTo("EVIDENCE_SCOPE_VIOLATION");
        List<EvidenceBundle.Evidence> supersededEvidence = List.of(
                new EvidenceBundle.Evidence(
                        "passage:" + oldChunkId, "PASSAGE", sourceId, oldSnapshotId,
                        oldChunkId, "", "", "", "", "", 1, 1, 1,
                        "workspace-source:" + sourceId, Instant.now(), "test", 1, Map.of())
        );
        assertThatThrownBy(() -> ownershipGuard.requireCurrent(
                new AnswerContext(
                        workspaceId, "conversation", "message", "query",
                        java.util.Set.of(), Map.of(), Instant.now()),
                supersededEvidence
        )).isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).code())
                .isEqualTo("EVIDENCE_SCOPE_VIOLATION");
    }

    @Test
    void phase1And2ShouldRunWorkspaceUploadRagCitationFlow() throws Exception {
        String workspaceId = createWorkspace();
        byte[] first = "NoteWeave 是一个 NotebookLM 形态的研究工作台。\n".getBytes(StandardCharsets.UTF_8);
        byte[] second = "阶段2实现资料上传、解析切片、问答RAG和引用闭环。".getBytes(StandardCharsets.UTF_8);
        String uploadId = createUpload(
                workspaceId,
                first.length + second.length,
                Math.max(first.length, second.length)
        );

        mockMvc.perform(put("/api/v2/uploads/{uploadId}/chunks/{chunkIndex}", uploadId, 0)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(first))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accepted").value(true));

        mockMvc.perform(put("/api/v2/uploads/{uploadId}/chunks/{chunkIndex}", uploadId, 1)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(second))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accepted").value(true));

        MvcResult completeResult = mockMvc.perform(post("/api/v2/uploads/{uploadId}/complete", uploadId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.parse_status").value(org.hamcrest.Matchers.anyOf(org.hamcrest.Matchers.equalTo("PARSED"), org.hamcrest.Matchers.equalTo("PARSING_QUEUED"))))
                .andExpect(jsonPath("$.data.index_status").value(org.hamcrest.Matchers.anyOf(org.hamcrest.Matchers.equalTo("INDEXED"), org.hamcrest.Matchers.equalTo("INDEX_QUEUED"))))
                .andReturn();

        JsonNode complete = objectMapper.readTree(completeResult.getResponse().getContentAsString());
        String sourceId = complete.path("data").path("source_id").asText();
        String taskId = complete.path("data").path("task_id").asText();
        assertThat(sourceId).isNotBlank();

        mockMvc.perform(get("/api/v2/tasks/{taskId}", taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.task_status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.result_ref").value(sourceId));

        mockMvc.perform(get("/api/v2/tasks/{taskId}/events", taskId))
                .andExpect(status().isOk())
                .andExpect(header().string("Deprecation", "true"))
                .andExpect(header().string("Link", org.hamcrest.Matchers.containsString("/event-history")))
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id: ")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event: task.status")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event: task.completed")));

        mockMvc.perform(get("/api/v2/tasks/{taskId}/event-history", taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].event_id").isNotEmpty())
                .andExpect(jsonPath("$.data[0].event_type").isNotEmpty());

        String conversationId = createConversation(workspaceId);
        MvcResult messageResult = mockMvc.perform(post("/api/v2/conversations/{conversationId}/messages", conversationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "content", "阶段2实现了什么？",
                                "answer_mode", "QA",
                                "client_request_id", "req-1"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.assistant_request_id").isNotEmpty())
                .andExpect(jsonPath("$.data.answer_run_id").isNotEmpty())
                .andExpect(jsonPath("$.data.answer_stream_url").isNotEmpty())
                .andExpect(jsonPath("$.data.retrieval_degraded").value(false))
                .andExpect(jsonPath("$.data.retrieval_degradation_reasons").isEmpty())
                .andReturn();

        JsonNode message = objectMapper.readTree(messageResult.getResponse().getContentAsString());
        String assistantRequestId = message.path("data").path("assistant_request_id").asText();
        String answerRunId = message.path("data").path("answer_run_id").asText();

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/answer-runs/{runId}", workspaceId, answerRunId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value(
                        org.hamcrest.Matchers.isOneOf("GENERATING", "FINALIZING", "COMPLETED")))
                .andExpect(jsonPath("$.data.revision_status").value(
                        org.hamcrest.Matchers.isOneOf("STREAMING", "FINAL")))
                .andExpect(jsonPath("$.data.retrieval_plan_version").value("qa-weknora-hybrid-v1"))
                .andExpect(jsonPath("$.data.maximum_output_tokens").value(1200))
                .andExpect(jsonPath("$.data.retrieval_degraded").value(true))
                .andExpect(jsonPath("$.data.retrieval_degradation_reasons")
                        .value(org.hamcrest.Matchers.hasItem("qa_mysql_fallback")));

        Map<String, Object> revisionContract = jdbcTemplate.queryForMap("""
                select prompt_version, evidence_bundle_ref
                from message_revision
                where answer_run_id = ? and revision_no = 1
                """, answerRunId);
        assertThat(revisionContract.get("prompt_version")).isEqualTo("qa-weknora-hybrid-v1");
        assertThat(revisionContract.get("evidence_bundle_ref").toString())
                .startsWith("evidence-bundle:");

        Map<String, Object> retrievalSnapshots = jdbcTemplate.queryForMap("""
                select retrieval_plan_json, evidence_bundle_json
                from answer_run
                where id = ?
                """, answerRunId);
        JsonNode persistedPlan = objectMapper.readTree(
                retrievalSnapshots.get("retrieval_plan_json").toString());
        JsonNode persistedBundle = objectMapper.readTree(
                retrievalSnapshots.get("evidence_bundle_json").toString());
        assertThat(persistedPlan.path("version").asText()).isEqualTo("qa-weknora-hybrid-v1");
        assertThat(persistedPlan.path("steps").get(0).path("channel").asText())
                .isEqualTo("QA_PASSAGE");
        assertThat(persistedPlan.path("budget").path("max_evidence").asInt()).isEqualTo(6);
        assertThat(persistedPlan.path("steps").get(0).path("filters")
                .path("relevance_policy").asText()).isEqualTo("qa-lexical-sufficiency-v2");
        assertThat(persistedPlan.path("steps").get(0).path("filters")
                .path("selection_policy").asText()).isEqualTo("qa-source-diverse-budget-v2");
        assertThat(persistedPlan.path("steps").get(0).path("filters")
                .path("strategy_profile").asText()).isEqualTo("qa-weknora-hybrid-v1");
        assertThat(persistedBundle.path("schema_version").asText())
                .isEqualTo("evidence-bundle-snapshot-v1");
        assertThat(persistedBundle.path("evidence").get(0).path("source_snapshot_id").asText())
                .isNotBlank();
        assertThat(retrievalSnapshots.get("evidence_bundle_json").toString())
                .doesNotContain("excerpt")
                .doesNotContain("title")
                .doesNotContain("content");

        String retrievalSummaryPayload = jdbcTemplate.queryForObject("""
                select payload_json
                from answer_event
                where answer_run_id = ? and event_type = 'retrieval.summary'
                """, String.class, answerRunId);
        JsonNode retrievalSummary = objectMapper.readTree(retrievalSummaryPayload);
        assertThat(retrievalSummary.path("trace_schema_version").asText())
                .isEqualTo("retrieval-execution-trace-v1");
        assertThat(retrievalSummary.path("plan_version").asText()).isEqualTo("qa-weknora-hybrid-v1");
        assertThat(retrievalSummary.path("strategy_profile").asText()).isEqualTo("qa-weknora-hybrid-v1");
        assertThat(retrievalSummary.path("relevance_policy").asText())
                .isEqualTo("qa-lexical-sufficiency-v2");
        assertThat(retrievalSummary.path("selection_policy").asText())
                .isEqualTo("qa-source-diverse-budget-v2");
        assertThat(retrievalSummary.path("candidate_count").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(retrievalSummary.path("admitted_candidate_count").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(retrievalSummary.path("selected_evidence_count").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(retrievalSummary.path("selected_evidence_characters").asInt()).isGreaterThan(0);
        assertThat(retrievalSummary.path("degraded").asBoolean()).isTrue();
        assertThat(retrievalSummary.path("degradation_reasons").toString())
                .contains("qa_mysql_fallback");
        assertThat(retrievalSummary.path("maximum_output_tokens").asInt()).isEqualTo(1200);
        assertThat(retrievalSummary.path("execution_trace").path("steps").get(0)
                .path("channel").asText()).isEqualTo("QA_PASSAGE");
        assertThat(retrievalSummary.path("execution_trace").path("steps").get(0)
                .path("degraded").asBoolean()).isTrue();
        assertThat(retrievalSummary.path("execution_trace").path("steps").get(0)
                .path("measurements").path("mysql_fallback_used").asInt()).isEqualTo(1);
        assertThat(retrievalSummary.path("execution_trace").path("steps").get(0)
                .path("measurements").path("strategy_v2_enabled").asInt()).isEqualTo(1);
        assertThat(retrievalSummary.path("execution_trace").path("selected_evidence").isArray())
                .isTrue();
        assertThat(retrievalSummaryPayload)
                .doesNotContain("excerpt")
                .doesNotContain("title")
                .doesNotContain("content");

        ChatStreamTestSupport.performAnswerRun(mockMvc, workspaceId, answerRunId)
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("## 直接回答")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("## 证据选择")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("查询意图")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Deep Research"))))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event:citation.upsert")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event:answer.completed")));

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/answer-runs/{runId}", workspaceId, answerRunId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.revision_status").value("FINAL"))
                .andExpect(jsonPath("$.data.first_token_at").isNotEmpty())
                .andExpect(jsonPath("$.data.finished_at").isNotEmpty());

        ChatStreamTestSupport.perform(mockMvc, assistantRequestId)
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event:chat.delta")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event:chat.completed")));

        ChatStreamTestSupport.performAnswerRun(mockMvc, workspaceId, answerRunId)
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event:answer.snapshot")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event:answer.completed")));

        Long completedCursor = jdbcTemplate.queryForObject(
                "select event_seq from answer_run where workspace_id = ? and id = ?",
                Long.class,
                workspaceId,
                answerRunId
        );
        ChatStreamTestSupport.performAnswerRunAfter(
                        mockMvc, workspaceId, answerRunId, Long.toString(completedCursor))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("event:answer.snapshot"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("event:answer.completed"))));

        Integer revisionCount = jdbcTemplate.queryForObject(
                "select count(*) from message_revision where answer_run_id = ? and status = 'FINAL'",
                Integer.class,
                answerRunId
        );
        Long distinctEventSequenceCount = jdbcTemplate.queryForObject(
                "select count(distinct seq) from answer_event where answer_run_id = ?",
                Long.class,
                answerRunId
        );
        Long eventCount = jdbcTemplate.queryForObject(
                "select count(*) from answer_event where answer_run_id = ?",
                Long.class,
                answerRunId
        );
        assertThat(revisionCount).isEqualTo(1);
        assertThat(distinctEventSequenceCount).isEqualTo(eventCount);

        Integer citationCount = jdbcTemplate.queryForObject("select count(*) from citation", Integer.class);
        Integer messageCitationCount = jdbcTemplate.queryForObject("select count(*) from message_citation", Integer.class);
        assertThat(citationCount).isNotNull().isGreaterThan(0);
        assertThat(messageCitationCount).isNotNull().isGreaterThan(0);
    }

    @Test
    void qaRagShouldSelectDiverseEvidenceAcrossWorkspaceSources() throws Exception {
        String workspaceId = createWorkspace();
        completeSingleChunkUpload(workspaceId, "rag-a.md", """
                RAG 资料 A 说明检索增强生成需要先做工作台级资料召回，再把证据片段打包给模型。
                它强调 citation-grounded answer，不能把聊天历史当事实来源。
                """.getBytes(StandardCharsets.UTF_8));
        completeSingleChunkUpload(workspaceId, "rag-b.md", """
                RAG 资料 B 说明证据选择要覆盖不同来源，比较类问题应该避免只拿同一份资料的多个片段。
                它强调 evidence rerank、source diversity 和 citation 回跳。
                """.getBytes(StandardCharsets.UTF_8));
        String conversationId = createConversation(workspaceId);

        MvcResult messageResult = mockMvc.perform(post("/api/v2/conversations/{conversationId}/messages", conversationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "content", "比较 RAG 的证据选择和 citation 要求",
                                "answer_mode", "QA",
                                "client_request_id", "qa-diverse"
                        ))))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode message = objectMapper.readTree(messageResult.getResponse().getContentAsString());
        String assistantRequestId = message.path("data").path("assistant_request_id").asText();
        String assistantMessageId = message.path("data").path("assistant_message_id").asText();

        ChatStreamTestSupport.perform(mockMvc, assistantRequestId)
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("查询意图：comparison")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("来源覆盖")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("RAG 资料 A")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("RAG 资料 B")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event:chat.citation")));

        Integer distinctSourceCount = jdbcTemplate.queryForObject("""
                select count(distinct c.source_id)
                from message_citation mc
                join citation c on c.id = mc.citation_id
                where mc.message_id = ?
                """, Integer.class, assistantMessageId);
        assertThat(distinctSourceCount).isNotNull().isGreaterThanOrEqualTo(2);
    }

    @Test
    void qaRagShouldPersistCitationsOnlyFromExplicitSourceScope() throws Exception {
        String workspaceId = createWorkspace();
        completeSingleChunkUpload(workspaceId, "scope-allowed.md", """
                ScopedRAG 允许资料说明回答必须尊重用户显式选择的资料范围。
                该资料包含 allowed-only-evidence 标记。
                """.getBytes(StandardCharsets.UTF_8));
        completeSingleChunkUpload(workspaceId, "scope-denied.md", """
                ScopedRAG 未选择资料也讨论显式资料范围，但包含 denied-only-evidence 标记。
                """.getBytes(StandardCharsets.UTF_8));
        String allowedSourceId = jdbcTemplate.queryForObject(
                "select id from source where workspace_id = ? and title = ?",
                String.class, workspaceId, "scope-allowed.md");
        String deniedSourceId = jdbcTemplate.queryForObject(
                "select id from source where workspace_id = ? and title = ?",
                String.class, workspaceId, "scope-denied.md");
        String conversationId = createConversation(workspaceId);

        MvcResult messageResult = mockMvc.perform(post(
                        "/api/v2/conversations/{conversationId}/messages", conversationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "content", "ScopedRAG 的显式资料范围要求是什么",
                                "answer_mode", "QA",
                                "client_request_id", "qa-source-scope",
                                "source_scope_source_ids", List.of(allowedSourceId)
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode message = objectMapper.readTree(messageResult.getResponse().getContentAsString());
        String assistantRequestId = message.path("data").path("assistant_request_id").asText();
        String assistantMessageId = message.path("data").path("assistant_message_id").asText();

        ChatStreamTestSupport.perform(mockMvc, assistantRequestId)
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("allowed-only-evidence")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("denied-only-evidence"))));

        List<String> citedSourceIds = jdbcTemplate.queryForList("""
                select distinct c.source_id
                from message_citation mc
                join citation c on c.id = mc.citation_id
                where mc.message_id = ?
                """, String.class, assistantMessageId);
        assertThat(citedSourceIds).containsExactly(allowedSourceId).doesNotContain(deniedSourceId);

        mockMvc.perform(post("/api/v2/conversations/{conversationId}/messages", conversationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "content", "不应静默忽略 scope",
                                "answer_mode", "NOTE",
                                "client_request_id", "note-source-scope-unsupported",
                                "source_scope_source_ids", List.of(allowedSourceId)
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ANSWER_SOURCE_SCOPE_UNSUPPORTED"));
    }

    @Test
    void qaModeShouldCarryRollingConversationContextForFollowUpQuestions() throws Exception {
        String workspaceId = createWorkspace();
        completeSingleChunkUpload(workspaceId, "alpha-context.md", """
                AlphaSpec 的关键要求包括分阶段发布、证据留痕和来源可回跳。
                AlphaSpec 还要求回答过程优先围绕当前主题持续展开，而不是切到别的资料。
                AlphaSpec 的第二个关键要求是证据留痕，这能保证后续追问仍然围绕同一主题继续验证。
                """.getBytes(StandardCharsets.UTF_8));
        completeSingleChunkUpload(workspaceId, "beta-context.md", """
                BetaSpec 讨论的是离线导入、批量解析和异步索引。
                它和 AlphaSpec 的关键要求不是同一个主题。
                """.getBytes(StandardCharsets.UTF_8));
        String conversationId = createConversation(workspaceId);

        sendCompletedMessage(conversationId, "QA", "先介绍 AlphaSpec 的关键要求");
        sendCompletedMessage(conversationId, "QA", "把它分成两点讲");
        JsonNode followUp = sendMessage(conversationId, "QA", "第二点为什么重要");
        String assistantRequestId = followUp.path("data").path("assistant_request_id").asText();
        String assistantMessageId = followUp.path("data").path("assistant_message_id").asText();

        ChatStreamTestSupport.perform(mockMvc, assistantRequestId)
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("会话上下文：已纳入最近连续对话窗口")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("连续对话窗口：最近 2 轮相关对话")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("主题锚点：")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("AlphaSpec")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event:chat.citation")));

        Integer alphaCitationCount = jdbcTemplate.queryForObject("""
                select count(*)
                from message_citation mc
                join citation c on c.id = mc.citation_id
                where mc.message_id = ? and c.title = 'alpha-context.md'
                """, Integer.class, assistantMessageId);
        assertThat(alphaCitationCount).isNotNull().isGreaterThan(0);
    }

    @Test
    void unknownAnswerModeShouldReturnStableDomainErrorWithoutWritingMessages() throws Exception {
        String workspaceId = createWorkspace();
        String conversationId = createConversation(workspaceId);
        Integer before = jdbcTemplate.queryForObject(
                "select count(*) from conversation_message where conversation_id = ?",
                Integer.class,
                conversationId);

        mockMvc.perform(post("/api/v2/conversations/{conversationId}/messages", conversationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "content", "unsupported mode must fail before persistence",
                                "answer_mode", "research",
                                "client_request_id", "unsupported-answer-mode"
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ANSWER_MODE_UNSUPPORTED"));

        Integer after = jdbcTemplate.queryForObject(
                "select count(*) from conversation_message where conversation_id = ?",
                Integer.class,
                conversationId);
        assertThat(after).isEqualTo(before);
    }

    @Test
    void qaModeShouldUseBoundedRecentTailUntilReadySegmentSummaryExists() throws Exception {
        String workspaceId = createWorkspace();
        completeSingleChunkUpload(workspaceId, "alpha-summary.md", """
                AlphaSpec 包含四类连续相关要求：分阶段发布、证据留痕、来源回跳和主题连续推进。
                这些要求共同强调回答链路要围绕同一主题逐步展开，并保留可验证依据。
                """.getBytes(StandardCharsets.UTF_8));
        String conversationId = createConversation(workspaceId);

        sendCompletedMessage(conversationId, "QA", "AlphaSpec 的分阶段发布要求是什么");
        sendCompletedMessage(conversationId, "QA", "AlphaSpec 的证据留痕要求是什么");
        sendCompletedMessage(conversationId, "QA", "AlphaSpec 的来源回跳要求是什么");
        sendCompletedMessage(conversationId, "QA", "AlphaSpec 的主题连续要求是什么");
        JsonNode followUp = sendMessage(conversationId, "QA", "继续总结它们的共同原则");
        String assistantRequestId = followUp.path("data").path("assistant_request_id").asText();

        ChatStreamTestSupport.perform(mockMvc, assistantRequestId)
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("会话上下文：已纳入最近连续对话窗口")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("连续对话窗口：最近 3 轮相关对话")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("前序主题摘要："))))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("AlphaSpec")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event:chat.citation")));
    }

    @Test
    void qaModeShouldCutOffOldWindowWhenUserStartsANewExplicitTopic() throws Exception {
        String workspaceId = createWorkspace();
        completeSingleChunkUpload(workspaceId, "alpha-topic.md", """
                AlphaSpec 关注主题连续展开和证据留痕。
                """.getBytes(StandardCharsets.UTF_8));
        completeSingleChunkUpload(workspaceId, "beta-topic.md", """
                BetaSpec 的重点是离线导入、批量解析和异步索引。
                """.getBytes(StandardCharsets.UTF_8));
        String conversationId = createConversation(workspaceId);

        sendMessage(conversationId, "QA", "先介绍 AlphaSpec 的关键要求");
        JsonNode followUp = sendMessage(conversationId, "QA", "请介绍 BetaSpec 的离线导入要求");
        String assistantRequestId = followUp.path("data").path("assistant_request_id").asText();
        String assistantMessageId = followUp.path("data").path("assistant_message_id").asText();

        ChatStreamTestSupport.perform(mockMvc, assistantRequestId)
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("会话上下文：已纳入最近连续对话窗口"))))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("BetaSpec")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("event:chat.citation")));

        Integer betaCitationCount = jdbcTemplate.queryForObject("""
                select count(*)
                from message_citation mc
                join citation c on c.id = mc.citation_id
                where mc.message_id = ? and c.title = 'beta-topic.md'
                """, Integer.class, assistantMessageId);
        assertThat(betaCitationCount).isNotNull().isGreaterThan(0);
    }

    @Test
    void createWorkspaceShouldValidateName() throws Exception {
        mockMvc.perform(post("/api/v2/workspaces")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", ""))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void answerRunCancellationShouldPreserveWhicheverTerminalStateWins() throws Exception {
        String workspaceId = createWorkspace();
        String conversationId = createConversation(workspaceId);
        JsonNode message = sendMessage(conversationId, "QA", "这个回答将在开始流式前被取消");
        String runId = message.path("data").path("answer_run_id").asText();
        String assistantRequestId = message.path("data").path("assistant_request_id").asText();

        MvcResult cancellation = mockMvc.perform(delete(
                        "/api/v2/workspaces/{workspaceId}/answer-runs/{runId}", workspaceId, runId))
                .andReturn();
        assertThat(cancellation.getResponse().getStatus()).isIn(200, 409);

        if (cancellation.getResponse().getStatus() == 200) {
            JsonNode cancelled = objectMapper.readTree(cancellation.getResponse().getContentAsString());
            assertThat(cancelled.path("data").path("status").asText()).isEqualTo("CANCELLED");
            assertThat(cancelled.path("data").path("revision_status").asText()).isEqualTo("PARTIAL");
            assertThat(cancelled.path("data").path("finished_at").asText()).isNotBlank();

            ChatStreamTestSupport.perform(mockMvc, assistantRequestId)
                    .andExpect(status().isOk())
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("event:chat.failed")))
                    .andExpect(content().string(org.hamcrest.Matchers.not(
                            org.hamcrest.Matchers.containsString("event:chat.completed"))));

            ChatStreamTestSupport.performAnswerRun(mockMvc, workspaceId, runId)
                    .andExpect(status().isOk())
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("event:answer.cancelled")))
                    .andExpect(content().string(org.hamcrest.Matchers.not(
                            org.hamcrest.Matchers.containsString("event:answer.completed"))));
        } else {
            JsonNode conflict = objectMapper.readTree(cancellation.getResponse().getContentAsString());
            assertThat(conflict.path("code").asText()).isEqualTo("ANSWER_RUN_TERMINAL");
        }

        String terminalStatus = jdbcTemplate.queryForObject(
                "select status from answer_run where workspace_id = ? and id = ?",
                String.class,
                workspaceId,
                runId
        );
        assertThat(terminalStatus).isIn("CANCELLED", "COMPLETED");
    }

    @Test
    void duplicateUploadsShouldReuseCanonicalFileObject() throws Exception {
        String workspaceId = createWorkspace();
        byte[] content = "同一份资料重复上传时应该复用 file_object，并保留可读取的规范化对象。".getBytes(StandardCharsets.UTF_8);

        completeSingleChunkUpload(workspaceId, "duplicate-a.md", content);
        completeSingleChunkUpload(workspaceId, "duplicate-b.md", content);

        Map<String, Object> fileObject = jdbcTemplate.queryForMap("""
                select id, object_key, ref_count from file_object where workspace_id = ?
                """, workspaceId);
        Integer fileObjectCount = jdbcTemplate.queryForObject(
                "select count(*) from file_object where workspace_id = ?",
                Integer.class,
                workspaceId
        );
        Integer sourceCount = jdbcTemplate.queryForObject(
                "select count(*) from source where workspace_id = ?",
                Integer.class,
                workspaceId
        );

        assertThat(fileObjectCount).isEqualTo(1);
        assertThat(sourceCount).isEqualTo(2);
        assertThat(fileObject.get("ref_count")).isEqualTo(2);
        assertThat(storage.exists("noteweave-source", (String) fileObject.get("object_key"))).isTrue();
        assertThat(storage.read("noteweave-source", (String) fileObject.get("object_key"))).isEqualTo(content);
    }

    @Test
    void uploadChunkShouldValidateOptionalContentMd5() throws Exception {
        String workspaceId = createWorkspace();
        String uploadId = createUpload(workspaceId);
        byte[] content = "需要被校验的资料分片".getBytes(StandardCharsets.UTF_8);

        mockMvc.perform(put("/api/v2/uploads/{uploadId}/chunks/{chunkIndex}", uploadId, 0)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .header("Content-MD5", "bad-md5")
                        .content(content))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("UPLOAD_CHUNK_MD5_MISMATCH"));

        mockMvc.perform(put("/api/v2/uploads/{uploadId}/chunks/{chunkIndex}", uploadId, 0)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .header("Content-MD5", base64Md5(content))
                        .content(content))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accepted").value(true));
    }

    private String createWorkspace() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v2/workspaces")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "阶段2工作台",
                                "description", "用于阶段1/2契约测试"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.workspace_id").isNotEmpty())
                .andReturn();
        String workspaceId = objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("workspace_id").asText();
        // This historical contract exercises the current v2 QA behavior explicitly; the
        // dedicated rollout contract covers the production-safe default-off path.
        jdbcTemplate.update("""
                update workspace
                set retrieval_strategy_v2_enabled = true
                where id = ?
                """, workspaceId);
        return workspaceId;
    }

    private String createUpload(String workspaceId) throws Exception {
        return createUpload(workspaceId, 128, 64);
    }

    private String createUpload(String workspaceId, long fileSize, int chunkSize) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/uploads", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "file_name", "phase2.md",
                                "file_size", fileSize,
                                "mime_type", "text/markdown",
                                "chunk_size", chunkSize,
                                "total_chunks", 2
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.upload_id").isNotEmpty())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("upload_id").asText();
    }

    private void completeSingleChunkUpload(String workspaceId, String fileName, byte[] content) throws Exception {
        MvcResult init = mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/uploads", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "file_name", fileName,
                                "file_size", content.length,
                                "mime_type", "text/markdown",
                                "chunk_size", content.length,
                                "total_chunks", 1
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        String uploadId = objectMapper.readTree(init.getResponse().getContentAsString()).path("data").path("upload_id").asText();
        mockMvc.perform(put("/api/v2/uploads/{uploadId}/chunks/{chunkIndex}", uploadId, 0)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(content))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v2/uploads/{uploadId}/complete", uploadId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.parse_status").value(org.hamcrest.Matchers.anyOf(org.hamcrest.Matchers.equalTo("PARSED"), org.hamcrest.Matchers.equalTo("PARSING_QUEUED"))))
                .andExpect(jsonPath("$.data.index_status").value(org.hamcrest.Matchers.anyOf(org.hamcrest.Matchers.equalTo("INDEXED"), org.hamcrest.Matchers.equalTo("INDEX_QUEUED"))));
    }

    private String createConversation(String workspaceId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/conversations", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "title", "阶段2问答",
                                "conversation_type", "WORKSPACE_CHAT"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.conversation_id").isNotEmpty())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("conversation_id").asText();
    }

    private JsonNode sendMessage(String conversationId, String answerMode, String content) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v2/conversations/{conversationId}/messages", conversationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "content", content,
                                "answer_mode", answerMode,
                                "client_request_id", answerMode + "-" + System.nanoTime()
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.assistant_message_id").isNotEmpty())
                .andExpect(jsonPath("$.data.assistant_request_id").isNotEmpty())
                .andExpect(jsonPath("$.data.answer_run_id").isNotEmpty())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode sendCompletedMessage(
            String conversationId,
            String answerMode,
            String content
    ) throws Exception {
        JsonNode response = sendMessage(conversationId, answerMode, content);
        ChatStreamTestSupport.perform(
                        mockMvc,
                        response.path("data").path("assistant_request_id").asText())
                .andExpect(status().isOk())
                .andExpect(content().string(
                        org.hamcrest.Matchers.containsString("event:chat.completed")));
        return response;
    }

    private String base64Md5(byte[] content) throws Exception {
        return Base64.getEncoder().encodeToString(MessageDigest.getInstance("MD5").digest(content));
    }
}
