package com.noteweave.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.chat.ChatService;
import com.noteweave.common.Ids;
import com.noteweave.research.ResearchAgentIncrementalFinalizationService;
import com.noteweave.research.ResearchAgentTaskCoordinatorService;
import com.noteweave.research.ResearchAgentTaskService;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ConversationTurnModuleContractTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ConversationTopicProjectionV2Service topicProjectionV2Service;
    @Autowired ConversationTopicSummaryV2Service topicSummaryV2Service;
    @Autowired ConversationContextCompilerV2Service contextCompilerV2Service;
    @Autowired ContextV2RolloutService contextRolloutV2Service;
    @Autowired ResearchAgentTaskCoordinatorService researchTaskCoordinator;
    @Autowired ResearchAgentTaskService researchAgentTaskService;
    @Autowired ResearchAgentIncrementalFinalizationService researchFinalizationService;
    @SpyBean ChatService chatService;
    @SpyBean ConversationContextProjectionService contextProjectionService;

    @Test
    void sameAnswerSubmissionReturnsTheOriginalReceipt() throws Exception {
        String workspaceId = createWorkspace();
        assertThat(contextRolloutV2Service.set(workspaceId, "SHADOW").shadowEffective()).isFalse();
        String conversationId = createConversation(workspaceId);
        Map<String, Object> request = Map.of(
                "content", "Explain the first invariant",
                "answer_mode", "QA",
                "client_request_id", "answer-idempotency-1"
        );

        JsonNode first = submit(conversationId, request, 200);
        JsonNode second = submit(conversationId, request, 200);

        assertThat(second.path("submission_id").asText())
                .isNotBlank()
                .isEqualTo(first.path("submission_id").asText());
        assertThat(second.path("message_id").asText())
                .isEqualTo(first.path("message_id").asText());
        assertThat(second.path("assistant_message_id").asText())
                .isEqualTo(first.path("assistant_message_id").asText());
        assertThat(second.path("answer_run_id").asText())
                .isEqualTo(first.path("answer_run_id").asText());
        assertThat(first.path("execution_kind").asText()).isEqualTo("ANSWER");
        assertThat(first.path("reused").asBoolean()).isFalse();
        assertThat(second.path("reused").asBoolean()).isTrue();
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from context_v2_shadow_snapshot where answer_run_id = ?
                """, Integer.class, first.path("answer_run_id").asText())).isZero();
    }

    @Test
    void answerSubmissionPersistsOneImmutableInputSnapshot() throws Exception {
        String workspaceId = createWorkspace();
        String conversationId = createConversation(workspaceId);
        Map<String, Object> request = Map.of(
                "content", "snapshot the initial input",
                "answer_mode", "QA",
                "client_request_id", "answer-snapshot-1"
        );
        JsonNode receipt = submit(conversationId, request, 200);
        submit(conversationId, request, 200);

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/runs/ANSWER/{runId}/input-snapshot",
                        workspaceId, receipt.path("answer_run_id").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.execution_kind").value("ANSWER"))
                .andExpect(jsonPath("$.data.run_id").value(receipt.path("answer_run_id").asText()))
                .andExpect(jsonPath("$.data.query_message_id").value(receipt.path("message_id").asText()))
                .andExpect(jsonPath("$.data.assistant_message_id").value(receipt.path("assistant_message_id").asText()))
                .andExpect(jsonPath("$.data.requested_turn_mode").value("QA"))
                .andExpect(jsonPath("$.data.retrieval_config.strategy").value("AUTO"))
                .andExpect(jsonPath("$.data.compiler_version").isNotEmpty())
                .andExpect(jsonPath("$.data.prompt_version").isNotEmpty())
                .andExpect(jsonPath("$.data.token_budget.maximum_output_tokens").isNumber());
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from run_input_snapshot where answer_run_id = ?",
                Integer.class, receipt.path("answer_run_id").asText())).isEqualTo(1);
    }

    @Test
    void answerSnapshotPersistsTheProjectionUsedByPromptCompilationWithoutSelectingAgain() throws Exception {
        String workspaceId = createWorkspace();
        String conversationId = createConversation(workspaceId);
        submit(conversationId, Map.of(
                "content", "context seed",
                "answer_mode", "QA",
                "client_request_id", "exact-context-seed"
        ), 200);
        clearInvocations(contextProjectionService);

        JsonNode receipt = submit(conversationId, Map.of(
                "content", "more?",
                "answer_mode", "QA",
                "client_request_id", "exact-context-current"
        ), 200);

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/runs/ANSWER/{runId}/input-snapshot",
                        workspaceId, receipt.path("answer_run_id").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.snapshot.recent_message_refs.length()").value(3))
                .andExpect(jsonPath("$.data.snapshot.recent_message_refs[2].message_id")
                        .value(receipt.path("message_id").asText()));
        verify(contextProjectionService, never()).select(anyString(), anyString(), anyInt());
    }

    @Test
    void inputSnapshotKeepsTheEntireContiguousHistoryWhileSummaryBuildLags() throws Exception {
        String workspaceId = createWorkspace();
        String conversationId = createConversation(workspaceId);
        java.util.List<JsonNode> priorTurns = new java.util.ArrayList<>();
        for (int index = 1; index <= 6; index++) {
            priorTurns.add(submit(conversationId, Map.of(
                    "content", "raw-tail prior turn " + index,
                    "answer_mode", "QA",
                    "client_request_id", "raw-tail-prior-" + index
            ), 200));
        }
        Map<String, Object> request = Map.of(
                "content", "more?",
                "answer_mode", "QA",
                "client_request_id", "raw-tail-current-1"
        );
        JsonNode receipt = submit(conversationId, request, 200);

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/runs/ANSWER/{runId}/input-snapshot",
                        workspaceId, receipt.path("answer_run_id").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.snapshot.segment_summary_refs.length()").value(0))
                .andExpect(jsonPath("$.data.snapshot.recent_message_refs.length()").value(13))
                .andExpect(jsonPath("$.data.snapshot.recent_message_refs[0].message_id")
                        .value(priorTurns.get(0).path("message_id").asText()))
                .andExpect(jsonPath("$.data.snapshot.recent_message_refs[0].message_seq").value(1))
                .andExpect(jsonPath("$.data.snapshot.recent_message_refs[12].message_id")
                        .value(receipt.path("message_id").asText()))
                .andExpect(jsonPath("$.data.snapshot.recent_message_refs[12].message_seq").value(13))
                .andExpect(jsonPath("$.data.snapshot.recent_message_refs[12].role").value("USER"));

        JsonNode reused = submit(conversationId, request, 200);
        assertThat(reused.path("answer_run_id").asText()).isEqualTo(receipt.path("answer_run_id").asText());
    }

    @Test
    void finalizedAnswerQueuesOneAsyncSummaryBuildForTheOldActivePrefix() throws Exception {
        String workspaceId = createWorkspace();
        String conversationId = createConversation(workspaceId);
        for (int index = 1; index <= 5; index++) {
            submit(conversationId, Map.of(
                    "content", "queued-summary turn " + index,
                    "answer_mode", "QA",
                    "client_request_id", "queued-summary-turn-" + index
            ), 200);
        }

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/conversations/{conversationId}/segment-summary-builds",
                        workspaceId, conversationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].covered_start_seq").value(1))
                .andExpect(jsonPath("$.data[0].covered_end_seq").value(3))
                .andExpect(jsonPath("$.data[0].revision_status").value("BUILDING"))
                .andExpect(jsonPath("$.data[0].task_type").value("CONVERSATION_SUMMARY"))
                .andExpect(jsonPath("$.data[0].task_status").value("PENDING"));

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/conversations/{conversationId}/segment-summary-builds",
                        workspaceId, conversationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    void promotedAutomaticallyQueuedSummaryIsSelectedByTheNextSnapshot() throws Exception {
        String workspaceId = createWorkspace();
        String conversationId = createConversation(workspaceId);
        for (int index = 1; index <= 5; index++) {
            submit(conversationId, Map.of(
                    "content", "promoted-queued-summary turn " + index,
                    "answer_mode", "QA",
                    "client_request_id", "promoted-queued-summary-turn-" + index
            ), 200);
        }
        MvcResult builds = mockMvc.perform(get(
                        "/api/v2/workspaces/{workspaceId}/conversations/{conversationId}/segment-summary-builds",
                        workspaceId, conversationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andReturn();
        JsonNode build = objectMapper.readTree(builds.getResponse().getContentAsString()).path("data").get(0);
        String segmentId = build.path("segment_id").asText();
        String revisionId = build.path("summary_revision_id").asText();

        mockMvc.perform(post("/internal/conversation-segments/{segmentId}/summary-revisions/{revisionId}/promote",
                        segmentId, revisionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "summary_text", "automatically queued prefix summary",
                                "content_hash", "d".repeat(64)
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("READY"));

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/conversations/{conversationId}/segment-summary-builds",
                        workspaceId, conversationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].task_status").value("COMPLETED"));

        JsonNode next = submit(conversationId, Map.of(
                "content", "more?",
                "answer_mode", "QA",
                "client_request_id", "promoted-queued-summary-next"
        ), 200);
        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/runs/ANSWER/{runId}/input-snapshot",
                        workspaceId, next.path("answer_run_id").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.snapshot.segment_summary_refs[0].summary_revision_id").value(revisionId))
                .andExpect(jsonPath("$.data.snapshot.recent_message_refs[0].message_seq").value(4))
                .andExpect(jsonPath("$.data.snapshot.recent_message_refs.length()").value(8));
    }

    @Test
    void readySegmentSummaryReplacesItsCoveredMessagesInTheSnapshot() throws Exception {
        String workspaceId = createWorkspace();
        String conversationId = createConversation(workspaceId);
        java.util.List<JsonNode> priorTurns = new java.util.ArrayList<>();
        for (int index = 1; index <= 6; index++) {
            priorTurns.add(submit(conversationId, Map.of(
                    "content", "summary-covered prior turn " + index,
                    "answer_mode", "QA",
                    "client_request_id", "summary-covered-prior-" + index
            ), 200));
        }
        String segmentId = java.util.UUID.randomUUID().toString();
        String revisionId = java.util.UUID.randomUUID().toString();
        jdbcTemplate.update("""
                insert into conversation_segment(
                    id, workspace_id, conversation_id, covered_start_seq, covered_end_seq
                ) values (?, ?, ?, 1, 6)
                """, segmentId, workspaceId, conversationId);
        jdbcTemplate.update("""
                insert into segment_summary_revision(
                    id, segment_id, revision_no, status, source_segment_version, summary_text, content_hash, ready_at
                ) values (?, ?, 1, 'READY', 0, 'Early active path summary', ?, current_timestamp)
                """, revisionId, segmentId, "a".repeat(64));

        JsonNode receipt = submit(conversationId, Map.of(
                "content", "more?",
                "answer_mode", "QA",
                "client_request_id", "summary-backed-current-1"
        ), 200);

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/runs/ANSWER/{runId}/input-snapshot",
                        workspaceId, receipt.path("answer_run_id").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.snapshot.segment_summary_refs.length()").value(1))
                .andExpect(jsonPath("$.data.snapshot.segment_summary_refs[0].segment_id").value(segmentId))
                .andExpect(jsonPath("$.data.snapshot.segment_summary_refs[0].summary_revision_id").value(revisionId))
                .andExpect(jsonPath("$.data.snapshot.segment_summary_refs[0].covered_end_seq").value(6))
                .andExpect(jsonPath("$.data.snapshot.recent_message_refs.length()").value(7))
                .andExpect(jsonPath("$.data.snapshot.recent_message_refs[0].message_id")
                        .value(priorTurns.get(3).path("message_id").asText()))
                .andExpect(jsonPath("$.data.snapshot.recent_message_refs[0].message_seq").value(7))
                .andExpect(jsonPath("$.data.snapshot.recent_message_refs[6].message_id")
                        .value(receipt.path("message_id").asText()));
    }

    @Test
    void buildingSegmentSummaryIsIgnoredUntilItIsReady() throws Exception {
        String workspaceId = createWorkspace();
        String conversationId = createConversation(workspaceId);
        java.util.List<JsonNode> priorTurns = new java.util.ArrayList<>();
        for (int index = 1; index <= 6; index++) {
            priorTurns.add(submit(conversationId, Map.of(
                    "content", "building-summary prior turn " + index,
                    "answer_mode", "QA",
                    "client_request_id", "building-summary-prior-" + index
            ), 200));
        }
        String segmentId = java.util.UUID.randomUUID().toString();
        jdbcTemplate.update("""
                insert into conversation_segment(
                    id, workspace_id, conversation_id, covered_start_seq, covered_end_seq
                ) values (?, ?, ?, 1, 6)
                """, segmentId, workspaceId, conversationId);
        jdbcTemplate.update("""
                insert into segment_summary_revision(
                    id, segment_id, revision_no, status, source_segment_version, summary_text
                ) values (?, ?, 1, 'BUILDING', 0, 'Not ready')
                """, java.util.UUID.randomUUID().toString(), segmentId);

        JsonNode receipt = submit(conversationId, Map.of(
                "content", "more?",
                "answer_mode", "QA",
                "client_request_id", "building-summary-current-1"
        ), 200);

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/runs/ANSWER/{runId}/input-snapshot",
                        workspaceId, receipt.path("answer_run_id").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.snapshot.segment_summary_refs.length()").value(0))
                .andExpect(jsonPath("$.data.snapshot.recent_message_refs.length()").value(13))
                .andExpect(jsonPath("$.data.snapshot.recent_message_refs[0].message_id")
                        .value(priorTurns.get(0).path("message_id").asText()))
                .andExpect(jsonPath("$.data.snapshot.recent_message_refs[0].message_seq").value(1));
    }

    @Test
    void staleSummaryPromotionCannotMakeItsRevisionVisibleToANewRun() throws Exception {
        String workspaceId = createWorkspace();
        String conversationId = createConversation(workspaceId);
        for (int index = 1; index <= 6; index++) {
            submit(conversationId, Map.of(
                    "content", "stale-summary prior turn " + index,
                    "answer_mode", "QA",
                    "client_request_id", "stale-summary-prior-" + index
            ), 200);
        }
        String segmentId = java.util.UUID.randomUUID().toString();
        String revisionId = java.util.UUID.randomUUID().toString();
        jdbcTemplate.update("""
                insert into conversation_segment(
                    id, workspace_id, conversation_id, covered_start_seq, covered_end_seq, lock_version
                ) values (?, ?, ?, 1, 6, 1)
                """, segmentId, workspaceId, conversationId);
        jdbcTemplate.update("""
                insert into segment_summary_revision(
                    id, segment_id, revision_no, status, source_segment_version
                ) values (?, ?, 1, 'BUILDING', 0)
                """, revisionId, segmentId);

        mockMvc.perform(post("/internal/conversation-segments/{segmentId}/summary-revisions/{revisionId}/promote",
                        segmentId, revisionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "summary_text", "stale summary must not be selected",
                                "content_hash", "b".repeat(64)
                        ))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SEGMENT_SUMMARY_PROMOTION_STALE"));

        JsonNode receipt = submit(conversationId, Map.of(
                "content", "more?",
                "answer_mode", "QA",
                "client_request_id", "stale-summary-current-1"
        ), 200);
        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/runs/ANSWER/{runId}/input-snapshot",
                        workspaceId, receipt.path("answer_run_id").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.snapshot.segment_summary_refs.length()").value(0));
    }

    @Test
    void currentSummaryPromotionMakesTheRevisionVisibleToANewRun() throws Exception {
        String workspaceId = createWorkspace();
        String conversationId = createConversation(workspaceId);
        for (int index = 1; index <= 6; index++) {
            submit(conversationId, Map.of(
                    "content", "current-summary prior turn " + index,
                    "answer_mode", "QA",
                    "client_request_id", "current-summary-prior-" + index
            ), 200);
        }
        String segmentId = java.util.UUID.randomUUID().toString();
        String revisionId = java.util.UUID.randomUUID().toString();
        jdbcTemplate.update("""
                insert into conversation_segment(
                    id, workspace_id, conversation_id, covered_start_seq, covered_end_seq, lock_version
                ) values (?, ?, ?, 1, 6, 0)
                """, segmentId, workspaceId, conversationId);
        jdbcTemplate.update("""
                insert into segment_summary_revision(
                    id, segment_id, revision_no, status, source_segment_version
                ) values (?, ?, 1, 'BUILDING', 0)
                """, revisionId, segmentId);

        mockMvc.perform(post("/internal/conversation-segments/{segmentId}/summary-revisions/{revisionId}/promote",
                        segmentId, revisionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "summary_text", "current summary is ready",
                                "content_hash", "c".repeat(64)
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("READY"));

        JsonNode receipt = submit(conversationId, Map.of(
                "content", "more?",
                "answer_mode", "QA",
                "client_request_id", "current-summary-current-1"
        ), 200);
        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/runs/ANSWER/{runId}/input-snapshot",
                        workspaceId, receipt.path("answer_run_id").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.snapshot.segment_summary_refs[0].summary_revision_id").value(revisionId));
    }

    @Test
    void sameIdempotencyKeyWithDifferentPayloadReturnsConflict() throws Exception {
        String conversationId = createConversation(createWorkspace());
        submit(conversationId, Map.of(
                "content", "original question",
                "answer_mode", "QA",
                "client_request_id", "answer-conflict-1"
        ), 200);

        mockMvc.perform(post("/api/v2/conversations/{conversationId}/messages", conversationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "content", "changed question",
                                "answer_mode", "QA",
                                "client_request_id", "answer-conflict-1"
                        ))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TURN_SUBMISSION_CONFLICT"));
    }

    @Test
    void invalidRetrievalConfigFailsBeforeItReservesTheIdempotencyKey() throws Exception {
        String conversationId = createConversation(createWorkspace());
        String clientRequestId = "invalid-retrieval-config-1";
        mockMvc.perform(post("/api/v2/conversations/{conversationId}/messages", conversationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "content", "do not persist this invalid retrieval request",
                                "answer_mode", "QA",
                                "client_request_id", clientRequestId,
                                "retrieval_strategy", "NONE",
                                "retrieval_channels", java.util.List.of("WORKSPACE")
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RETRIEVAL_CONFIG_INVALID"));

        submit(conversationId, Map.of(
                "content", "valid retry with the same idempotency key",
                "answer_mode", "QA",
                "client_request_id", clientRequestId
        ), 200);
    }

    @Test
    void noneRetrievalConfigPersistsAnEmptyRetrievalPlan() throws Exception {
        String workspaceId = createWorkspace();
        String conversationId = createConversation(workspaceId);
        JsonNode receipt = submit(conversationId, Map.of(
                "content", "answer without retrieval",
                "answer_mode", "QA",
                "client_request_id", "none-retrieval-1",
                "retrieval_strategy", "NONE",
                "retrieval_channels", java.util.List.of()
        ), 200);

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/runs/ANSWER/{runId}/input-snapshot",
                        workspaceId, receipt.path("answer_run_id").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.retrieval_config.strategy").value("NONE"))
                .andExpect(jsonPath("$.data.retrieval_config.channels.length()").value(0))
                .andExpect(jsonPath("$.data.snapshot.retrieval_plan.steps.length()").value(0));
        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/answer-runs/{runId}/evidence",
                        workspaceId, receipt.path("answer_run_id").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.run_id").value(receipt.path("answer_run_id").asText()))
                .andExpect(jsonPath("$.data.evidence.length()").value(0));
    }

    @Test
    void answerEvidenceManifestPreservesSelectedExcerptAndContentHash() throws Exception {
        String workspaceId = createWorkspace();
        completeSingleChunkUpload(workspaceId, "manifest-evidence.md", """
                ManifestAlphaEvidence requires an immutable evidence manifest for every answer run.
                The manifest must retain the selected excerpt and a content hash for later verification.
                """.getBytes(StandardCharsets.UTF_8));
        String conversationId = createConversation(workspaceId);

        JsonNode receipt = submit(conversationId, Map.of(
                "content", "What does ManifestAlphaEvidence require for an answer run?",
                "answer_mode", "QA",
                "client_request_id", "manifest-evidence-1"
        ), 200);

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/answer-runs/{runId}/evidence",
                        workspaceId, receipt.path("answer_run_id").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.run_id").value(receipt.path("answer_run_id").asText()))
                .andExpect(jsonPath("$.data.evidence.length()").value(org.hamcrest.Matchers.greaterThan(0)))
                .andExpect(jsonPath("$.data.evidence[0].excerpt",
                        org.hamcrest.Matchers.containsString("ManifestAlphaEvidence")))
                .andExpect(jsonPath("$.data.evidence[0].content_hash",
                        org.hamcrest.Matchers.matchesPattern("[0-9a-f]{64}")));
    }

    @Test
    void deletingSelectedSourceDowngradesReplayAndRedactsEvidenceBody() throws Exception {
        String workspaceId = createWorkspace();
        String sourceId = completeSingleChunkUpload(workspaceId, "deleted-manifest-evidence.md", """
                RedactedManifestEvidence requires an immutable evidence manifest for every answer run.
                The manifest must retain the selected excerpt and a content hash for later verification.
                Source deletion must remove the private excerpt from replay data.
                """.getBytes(StandardCharsets.UTF_8));
        String conversationId = createConversation(workspaceId);
        JsonNode receipt = submit(conversationId, Map.of(
                "content", "What does RedactedManifestEvidence require for an answer run?",
                "answer_mode", "QA",
                "client_request_id", "deleted-manifest-evidence-1",
                "source_scope_source_ids", java.util.List.of(sourceId)
        ), 200);
        String runId = receipt.path("answer_run_id").asText();

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/answer-runs/{runId}/evidence", workspaceId, runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.evidence[0].excerpt",
                        org.hamcrest.Matchers.containsString("RedactedManifestEvidence")));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                        "/api/v2/workspaces/{workspaceId}/sources/{sourceId}", workspaceId, sourceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DELETED"));

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/runs/ANSWER/{runId}/input-snapshot", workspaceId, runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.replay_availability").value("METADATA_ONLY"));
        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/answer-runs/{runId}/evidence", workspaceId, runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.evidence[0].source_id").value(sourceId))
                .andExpect(jsonPath("$.data.evidence[0].excerpt").value(""))
                .andExpect(jsonPath("$.data.evidence[0].content_hash")
                        .value("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"));
    }

    @Test
    void deepResearchSubmissionReturnsResearchRunBoundToConversationMessages() throws Exception {
        String conversationId = createConversation(createWorkspace());

        JsonNode receipt = submit(conversationId, Map.of(
                "content", "Research durable memory conflict resolution",
                "answer_mode", "DEEP_RESEARCH",
                "client_request_id", "research-turn-1"
        ), 200);

        assertThat(receipt.path("execution_kind").asText()).isEqualTo("RESEARCH");
        assertThat(receipt.path("submission_id").asText()).isNotBlank();
        assertThat(receipt.path("message_id").asText()).isNotBlank();
        assertThat(receipt.path("assistant_message_id").asText()).isNotBlank();
        assertThat(receipt.path("research_run_id").asText()).isNotBlank();
        assertThat(receipt.path("answer_run_id").isNull()).isTrue();
        assertThat(receipt.path("reused").asBoolean()).isFalse();
    }

    @Test
    void deepResearchSubmissionPersistsItsInitialInputSnapshot() throws Exception {
        String workspaceId = createWorkspace();
        String conversationId = createConversation(workspaceId);
        JsonNode receipt = submit(conversationId, Map.of(
                "content", "research snapshot initial question",
                "answer_mode", "DEEP_RESEARCH",
                "client_request_id", "research-snapshot-1"
        ), 200);

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/runs/RESEARCH/{runId}/input-snapshot",
                        workspaceId, receipt.path("research_run_id").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.execution_kind").value("RESEARCH"))
                .andExpect(jsonPath("$.data.run_id").value(receipt.path("research_run_id").asText()))
                .andExpect(jsonPath("$.data.query_message_id").value(receipt.path("message_id").asText()))
                .andExpect(jsonPath("$.data.assistant_message_id").value(receipt.path("assistant_message_id").asText()))
                .andExpect(jsonPath("$.data.requested_turn_mode").value("DEEP_RESEARCH"))
                .andExpect(jsonPath("$.data.retrieval_config.strategy").value("AUTO"))
                .andExpect(jsonPath("$.data.snapshot.segment_summary_refs.length()").value(0))
                .andExpect(jsonPath("$.data.snapshot.recent_message_refs.length()").value(1))
                .andExpect(jsonPath("$.data.snapshot.recent_message_refs[0].message_id")
                        .value(receipt.path("message_id").asText()));
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from research_run
                where id = ? and workspace_id = ? and retrieval_mode = 'WEB_ONLY'
                """, Integer.class, receipt.path("research_run_id").asText(), workspaceId)).isEqualTo(1);
    }

    @Test
    void deepResearchCoordinatorConsumesFrozenConversationContextInItsResearchBrief() throws Exception {
        String workspaceId = createWorkspace();
        String conversationId = createConversation(workspaceId);
        submit(conversationId, Map.of(
                "content", "We are comparing OpenSERP with hosted search providers",
                "answer_mode", "QA",
                "client_request_id", "research-brief-context-1"
        ), 200);
        JsonNode receipt = submit(conversationId, Map.of(
                "content", "Continue the research and focus on deployment tradeoffs",
                "answer_mode", "DEEP_RESEARCH",
                "client_request_id", "research-brief-context-2"
        ), 200);
        String runId = receipt.path("research_run_id").asText();

        String segmentId = Ids.newId();
        String revisionId = Ids.newId();
        jdbcTemplate.update("""
                insert into conversation_segment(
                    id, workspace_id, conversation_id, covered_start_seq, covered_end_seq
                ) values (?, ?, ?, 1, 2)
                """, segmentId, workspaceId, conversationId);
        jdbcTemplate.update("""
                insert into segment_summary_revision(
                    id, segment_id, revision_no, status, source_segment_version,
                    summary_text, content_hash, ready_at
                ) values (?, ?, 1, 'READY', 0, ?, ?, current_timestamp)
                """, revisionId, segmentId, "replacement summary promoted after submission", "f".repeat(64));

        assertThat(jdbcTemplate.queryForObject(
                "select agent_execution_mode from research_run where id = ?", String.class, runId))
                .isEqualTo("INCREMENTAL_V1");

        researchTaskCoordinator.planAndEnqueue(runId);
        String taskId = jdbcTemplate.queryForObject("""
                select id from research_agent_task where research_run_id = ? order by task_key limit 1
                """, String.class, runId);
        ResearchAgentTaskService.ClaimedTask claim = researchAgentTaskService.claimTask(
                new ResearchAgentTaskService.ClaimCommand(taskId, "research-brief-worker", 30));
        JsonNode snapshot = objectMapper.readTree(claim.taskSnapshotJson());

        assertThat(snapshot.path("query_policy").path("research_brief").path("entry_point").asText())
                .isEqualTo("CONVERSATION");
        assertThat(snapshot.path("query_policy").path("query").asText())
                .contains("OpenSERP with hosted search providers")
                .contains("focus on deployment tradeoffs")
                .doesNotContain("replacement summary promoted after submission");
        assertThat(snapshot.path("source_policy").toString())
                .doesNotContain("OpenSERP with hosted search providers");
    }

    @Test
    void staleHistoryHeadCannotAppendANewTurn() throws Exception {
        String conversationId = createConversation(createWorkspace());
        JsonNode first = submit(conversationId, Map.of(
                "content", "first turn",
                "answer_mode", "QA",
                "client_request_id", "head-cas-1"
        ), 200);
        String firstHead = first.path("assistant_message_id").asText();

        JsonNode second = submit(conversationId, Map.of(
                "content", "second turn",
                "answer_mode", "NOTE",
                "client_request_id", "head-cas-2",
                "expected_history_head_message_id", firstHead
        ), 200);
        assertThat(second.path("assistant_message_id").asText()).isNotEqualTo(firstHead);

        mockMvc.perform(post("/api/v2/conversations/{conversationId}/messages", conversationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "content", "stale append",
                                "answer_mode", "WIKI",
                                "client_request_id", "head-cas-3",
                                "expected_history_head_message_id", firstHead
                        ))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONVERSATION_HEAD_CONFLICT"));
    }

    @Test
    void conversationListAndMessageReloadRestoreAnswerAndResearchTurns() throws Exception {
        String workspaceId = createWorkspace();
        String conversationId = createConversation(workspaceId);
        JsonNode answer = submit(conversationId, Map.of(
                "content", "answer turn",
                "answer_mode", "QA",
                "client_request_id", "reload-answer"
        ), 200);
        JsonNode research = submit(conversationId, Map.of(
                "content", "research turn",
                "answer_mode", "DEEP_RESEARCH",
                "client_request_id", "reload-research",
                "expected_history_head_message_id", answer.path("assistant_message_id").asText()
        ), 200);

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/conversations", workspaceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].conversation_id").value(conversationId))
                .andExpect(jsonPath("$.data[0].active_head_message_id")
                        .value(research.path("assistant_message_id").asText()));

        mockMvc.perform(get(
                        "/api/v2/workspaces/{workspaceId}/conversations/{conversationId}/messages",
                        workspaceId, conversationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(4))
                .andExpect(jsonPath("$.data[0].role").value("USER"))
                .andExpect(jsonPath("$.data[0].requested_turn_mode").value("QA"))
                .andExpect(jsonPath("$.data[1].role").value("ASSISTANT"))
                .andExpect(jsonPath("$.data[2].requested_turn_mode").value("DEEP_RESEARCH"))
                .andExpect(jsonPath("$.data[3].message_id")
                        .value(research.path("assistant_message_id").asText()))
                .andExpect(jsonPath("$.data[3].context_status").value("PENDING"));
    }

    @Test
    void messageReloadProjectsFailedAnswerRunStatusAndError() throws Exception {
        String workspaceId = createWorkspace();
        String conversationId = createConversation(workspaceId);
        JsonNode answer = submit(conversationId, Map.of(
                "content", "failed answer reload",
                "answer_mode", "NOTE",
                "client_request_id", "failed-answer-reload"
        ), 200);
        String assistantMessageId = answer.path("assistant_message_id").asText();
        jdbcTemplate.update("""
                update answer_run
                set status = 'FAILED', error_code = 'PROVIDER_DISABLED',
                    error_message = 'Answer LLM is not configured'
                where answer_message_id = ?
                """, assistantMessageId);

        mockMvc.perform(get(
                        "/api/v2/workspaces/{workspaceId}/conversations/{conversationId}/messages",
                        workspaceId, conversationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[1].message_id").value(assistantMessageId))
                .andExpect(jsonPath("$.data[1].context_status").value("CURRENT"))
                .andExpect(jsonPath("$.data[1].answer_status").value("FAILED"))
                .andExpect(jsonPath("$.data[1].answer_error").value("Answer LLM is not configured"));
    }

    @Test
    void researchCompletionProjectsAReportCardIntoTheOriginalAssistantMessage() throws Exception {
        String workspaceId = createWorkspace();
        String conversationId = createConversation(workspaceId);
        JsonNode receipt = submit(conversationId, Map.of(
                "content", "research completion projection",
                "answer_mode", "DEEP_RESEARCH",
                "client_request_id", "research-completion-1"
        ), 200);
        String researchRunId = receipt.path("research_run_id").asText();
        finalizeResearchRun(researchRunId, "Verified conclusion", "completion-evidence",
                "external:durable-memory", "Durable Memory Research", "Verified conclusion source.");

        mockMvc.perform(get(
                        "/api/v2/workspaces/{workspaceId}/conversations/{conversationId}/messages",
                        workspaceId, conversationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[1].message_id")
                        .value(receipt.path("assistant_message_id").asText()))
                .andExpect(jsonPath("$.data[1].context_status").value("CURRENT"))
                .andExpect(jsonPath("$.data[1].content")
                        .value(org.hamcrest.Matchers.containsString("research completion projection")));
    }

    @Test
    void completedResearchReportQueuesTheNextActivePrefixSummaryBuild() throws Exception {
        String workspaceId = createWorkspace();
        String conversationId = createConversation(workspaceId);
        for (int index = 1; index <= 5; index++) {
            submit(conversationId, Map.of(
                    "content", "research-summary-prefix answer " + index,
                    "answer_mode", "QA",
                    "client_request_id", "research-summary-prefix-answer-" + index
            ), 200);
        }
        JsonNode receipt = submit(conversationId, Map.of(
                "content", "research completion must extend the active prefix",
                "answer_mode", "DEEP_RESEARCH",
                "client_request_id", "research-summary-prefix-research"
        ), 200);
        finalizeResearchRun(receipt.path("research_run_id").asText(), "Research prefix report",
                "prefix-evidence", "external:prefix", "Prefix source", "Prefix evidence quote.");

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/conversations/{conversationId}/segment-summary-builds",
                        workspaceId, conversationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[1].covered_start_seq").value(1))
                .andExpect(jsonPath("$.data[1].covered_end_seq").value(5))
                .andExpect(jsonPath("$.data[1].revision_status").value("BUILDING"))
                .andExpect(jsonPath("$.data[1].task_type").value("CONVERSATION_SUMMARY"));
    }

    @Test
    void deletingAReferencedMessageRedactsItsBodyAndDowngradesReplay() throws Exception {
        String workspaceId = createWorkspace();
        String conversationId = createConversation(workspaceId);
        JsonNode first = submit(conversationId, Map.of(
                "content", "delete this frozen message body",
                "answer_mode", "QA",
                "client_request_id", "delete-referenced-message-1"
        ), 200);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                        "/api/v2/workspaces/{workspaceId}/conversations/{conversationId}/messages/{messageId}",
                        workspaceId, conversationId, first.path("message_id").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.message_id").value(first.path("message_id").asText()))
                .andExpect(jsonPath("$.data.context_status").value("DELETED"));

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/runs/ANSWER/{runId}/input-snapshot",
                        workspaceId, first.path("answer_run_id").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.replay_availability").value("METADATA_ONLY"));
        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/conversations/{conversationId}/messages",
                        workspaceId, conversationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].context_status").value("DELETED"))
                .andExpect(jsonPath("$.data[0].content").value(""));

        JsonNode next = submit(conversationId, Map.of(
                "content", "more?",
                "answer_mode", "QA",
                "client_request_id", "delete-referenced-message-2"
        ), 200);
        MvcResult snapshot = mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/runs/ANSWER/{runId}/input-snapshot",
                        workspaceId, next.path("answer_run_id").asText()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode refs = objectMapper.readTree(snapshot.getResponse().getContentAsString())
                .path("data").path("snapshot").path("recent_message_refs");
        for (JsonNode ref : refs) {
            assertThat(ref.path("message_id").asText()).isNotEqualTo(first.path("message_id").asText());
        }
    }

    @Test
    void deletingAReadySummaryRedactsEarlierReplayAndExcludesItFromNewSnapshots() throws Exception {
        String workspaceId = createWorkspace();
        String conversationId = createConversation(workspaceId);
        for (int index = 1; index <= 5; index++) {
            submit(conversationId, Map.of("content", "delete-summary turn " + index, "answer_mode", "QA",
                    "client_request_id", "delete-summary-turn-" + index), 200);
        }
        MvcResult builds = mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/conversations/{conversationId}/segment-summary-builds", workspaceId, conversationId))
                .andExpect(status().isOk()).andReturn();
        JsonNode build = objectMapper.readTree(builds.getResponse().getContentAsString()).path("data").get(0);
        String segmentId = build.path("segment_id").asText();
        String revisionId = build.path("summary_revision_id").asText();
        mockMvc.perform(post("/internal/conversation-segments/{segmentId}/summary-revisions/{revisionId}/promote", segmentId, revisionId)
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(Map.of(
                                "summary_text", "erasable summary", "content_hash", "e".repeat(64)))))
                .andExpect(status().isOk());
        JsonNode selected = submit(conversationId, Map.of("content", "more?", "answer_mode", "QA",
                "client_request_id", "delete-summary-selected"), 200);
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                        "/internal/conversation-segments/{segmentId}/summary-revisions/{revisionId}", segmentId, revisionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DELETED"));
        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/runs/ANSWER/{runId}/input-snapshot", workspaceId, selected.path("answer_run_id").asText()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.replay_availability").value("METADATA_ONLY"));
        JsonNode next = submit(conversationId, Map.of("content", "more?", "answer_mode", "QA",
                "client_request_id", "delete-summary-next"), 200);
        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/runs/ANSWER/{runId}/input-snapshot", workspaceId, next.path("answer_run_id").asText()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.snapshot.segment_summary_refs.length()").value(0));
    }

    @Test
    void deletingAMessageCoveredByAReadySummaryBlanksDerivedTextAndDowngradesReplay() throws Exception {
        String workspaceId = createWorkspace();
        String conversationId = createConversation(workspaceId);
        JsonNode coveredMessage = null;
        for (int index = 1; index <= 5; index++) {
            JsonNode receipt = submit(conversationId, Map.of(
                    "content", "covered-delete turn " + index,
                    "answer_mode", "QA",
                    "client_request_id", "covered-delete-turn-" + index
            ), 200);
            if (index == 1) {
                coveredMessage = receipt;
            }
        }
        MvcResult builds = mockMvc.perform(get(
                        "/api/v2/workspaces/{workspaceId}/conversations/{conversationId}/segment-summary-builds",
                        workspaceId, conversationId))
                .andExpect(status().isOk()).andReturn();
        JsonNode build = objectMapper.readTree(builds.getResponse().getContentAsString()).path("data").get(0);
        String segmentId = build.path("segment_id").asText();
        String revisionId = build.path("summary_revision_id").asText();
        mockMvc.perform(post("/internal/conversation-segments/{segmentId}/summary-revisions/{revisionId}/promote",
                        segmentId, revisionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "summary_text", "summary derived from covered-delete turn 1",
                                "content_hash", "d".repeat(64)))))
                .andExpect(status().isOk());

        JsonNode selected = submit(conversationId, Map.of(
                "content", "more?",
                "answer_mode", "QA",
                "client_request_id", "covered-delete-selected"
        ), 200);
        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/runs/ANSWER/{runId}/input-snapshot",
                        workspaceId, selected.path("answer_run_id").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.replay_availability").value("FULL"))
                .andExpect(jsonPath("$.data.snapshot.segment_summary_refs[0].summary_revision_id").value(revisionId));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                        "/api/v2/workspaces/{workspaceId}/conversations/{conversationId}/messages/{messageId}",
                        workspaceId, conversationId, coveredMessage.path("message_id").asText()))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/runs/ANSWER/{runId}/input-snapshot",
                        workspaceId, selected.path("answer_run_id").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.replay_availability").value("METADATA_ONLY"));
        assertThat(jdbcTemplate.queryForObject(
                "select status from segment_summary_revision where id = ?", String.class, revisionId))
                .isEqualTo("STALE");
        assertThat(jdbcTemplate.queryForObject(
                "select summary_text from segment_summary_revision where id = ?", String.class, revisionId))
                .isEmpty();
    }

    @Test
    void shadowTopicProjectionReusesDisjointTopicAndDeletionRedactsItsDerivedRows() throws Exception {
        String workspaceId = createWorkspace();
        String conversationId = createConversation(workspaceId);
        String firstMessageId = "";
        String[] turns = {"解释缓存一致性。", "重点是写入顺序。", "改聊台南旅行。",
                "住两晚。", "回到缓存一致性，第二种写入顺序呢？"};
        for (int index = 0; index < turns.length; index++) {
            JsonNode receipt = submit(conversationId, Map.of(
                    "content", turns[index], "answer_mode", "QA",
                    "client_request_id", "topic-shadow-" + index), 200);
            if (index == 0) firstMessageId = receipt.path("message_id").asText();
        }
        TopicSegmenterV2.Projection first = topicProjectionV2Service.refresh(workspaceId, conversationId);
        TopicSegmenterV2.Projection replay = topicProjectionV2Service.refresh(workspaceId, conversationId);
        assertThat(first.segments()).hasSize(3).isEqualTo(replay.segments());
        assertThat(first.segments().get(0).topicId()).isEqualTo(first.segments().get(2).topicId());
        assertThat(first.segments().get(0).topicId()).isNotEqualTo(first.segments().get(1).topicId());
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from conversation_topic_segment_v2 where conversation_id = ?
                """, Integer.class, conversationId)).isEqualTo(3);
        String firstSegmentId = first.segments().get(0).segmentId();
        String revisionId = jdbcTemplate.queryForObject("""
                select id from conversation_topic_summary_revision_v2
                where segment_id = ? and status = 'BUILDING'
                """, String.class, firstSegmentId);
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from task_outbox where message_key = ? and status = 'READY'
                """, Integer.class, revisionId)).isEqualTo(1);
        String summaryText = jdbcTemplate.query("""
                select role, content from conversation_message
                where conversation_id = ? and message_seq between ? and ? order by message_seq
                """, (rs, rowNum) -> ConversationTopicSummaryV2Service.summarizeMessage(
                        rs.getString(1), rs.getString(2)), conversationId,
                first.segments().get(0).startSeq(),
                jdbcTemplate.queryForObject("""
                        select end_seq from conversation_topic_summary_revision_v2 where id = ?
                        """, Integer.class, revisionId)).stream()
                .filter(value -> !value.isBlank()).collect(java.util.stream.Collectors.joining("\n"));
        String summaryHash = java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256")
                        .digest(summaryText.getBytes(StandardCharsets.UTF_8)));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> topicSummaryV2Service.promote(
                firstSegmentId, revisionId,
                new PromoteSegmentSummaryRequest("Forged summary", summaryHash)))
                .isInstanceOf(com.noteweave.common.BusinessException.class);
        assertThat(jdbcTemplate.queryForObject("""
                select status from conversation_topic_summary_revision_v2 where id = ?
                """, String.class, revisionId)).isEqualTo("BUILDING");
        topicSummaryV2Service.promote(firstSegmentId, revisionId,
                new PromoteSegmentSummaryRequest(summaryText, summaryHash));
        assertThat(topicSummaryV2Service.ready(workspaceId, conversationId, 10))
                .extracting(ContextProjectionV2.TopicSummary::revisionId).contains(revisionId);
        ContextProjectionV2 frozenShadow = contextCompilerV2Service.compile(workspaceId,
                com.noteweave.security.CurrentUserProvider.LOCAL_USER_ID, conversationId,
                10, "What about the second write order?", "QA", 20_000);
        assertThat(frozenShadow.topicSummaries())
                .extracting(ContextProjectionV2.TopicSummary::revisionId).contains(revisionId);
        assertThat(frozenShadow.rawTail()).extracting(ContextProjectionV2.RawMessage::seq)
                .containsExactly(3, 4, 5, 6, 7, 8, 9, 10);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> contextCompilerV2Service.compile(
                workspaceId, "different-actor", conversationId, 10, "Follow up", "QA", 20_000))
                .isInstanceOf(com.noteweave.common.BusinessException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> contextCompilerV2Service.compile(
                workspaceId, com.noteweave.security.CurrentUserProvider.LOCAL_USER_ID,
                conversationId, 8, "Stale cutoff", "QA", 20_000))
                .isInstanceOf(com.noteweave.common.BusinessException.class);
        jdbcTemplate.update("""
                insert into conversation_constraint_v2(id, workspace_id, conversation_id,
                    source_message_id, kind, scope, constraint_text, valid_from_seq,
                    status, rule_version)
                values (?, ?, ?, ?, 'FORMAT', 'CONVERSATION', 'private derived text', 1,
                    'ACTIVE', 'topic-segmenter-v2-a1')
                """, Ids.newId(), workspaceId, conversationId, firstMessageId);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                        "/api/v2/workspaces/{workspaceId}/conversations/{conversationId}/messages/{messageId}",
                        workspaceId, conversationId, firstMessageId))
                .andExpect(status().isOk());

        assertThat(jdbcTemplate.queryForObject("""
                select decision_status from conversation_topic_segment_v2 where id = ?
                """, String.class, first.segments().get(0).segmentId())).isEqualTo("STALE");
        assertThat(jdbcTemplate.queryForObject("""
                select status from conversation_topic_summary_revision_v2 where id = ?
                """, String.class, revisionId)).isEqualTo("STALE");
        assertThat(jdbcTemplate.queryForObject("""
                select summary_text from conversation_topic_summary_revision_v2 where id = ?
                """, String.class, revisionId)).isEmpty();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> contextCompilerV2Service.compile(
                workspaceId, com.noteweave.security.CurrentUserProvider.LOCAL_USER_ID,
                conversationId, 10, "After deletion", "QA", 20_000))
                .isInstanceOf(com.noteweave.common.BusinessException.class);
        assertThat(jdbcTemplate.queryForObject("""
                select status from conversation_topic_v2 where id = ?
                """, String.class, first.segments().get(0).topicId())).isEqualTo("STALE");
        assertThat(jdbcTemplate.queryForObject("""
                select constraint_text from conversation_constraint_v2 where source_message_id = ?
                """, String.class, firstMessageId)).isEmpty();
    }

    @Test
    void shadowInputCutoffExcludesPendingAssistantAndRejectsLaterRecompile() throws Exception {
        String workspaceId = createWorkspace();
        String conversationId = createConversation(workspaceId);
        String queryId = Ids.newId();
        String placeholderId = Ids.newId();
        String query = "Explain the frozen input boundary";
        String queryHash = java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256")
                        .digest(query.getBytes(StandardCharsets.UTF_8)));
        String emptyHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
        jdbcTemplate.update("""
                insert into conversation_message(id, conversation_id, workspace_id, message_seq,
                    role, answer_mode, content, content_hash, context_status)
                values (?, ?, ?, 1, 'USER', 'QA', ?, ?, 'CURRENT')
                """, queryId, conversationId, workspaceId, query, queryHash);
        jdbcTemplate.update("""
                insert into conversation_message(id, conversation_id, workspace_id, message_seq,
                    role, answer_mode, content, content_hash, context_status, reply_to_message_id)
                values (?, ?, ?, 2, 'ASSISTANT', 'QA', '', ?, 'PENDING', ?)
                """, placeholderId, conversationId, workspaceId, emptyHash, queryId);

        TopicSegmenterV2.Projection frozen = topicProjectionV2Service.refreshForInputCutoff(
                workspaceId, conversationId, 1);
        assertThat(frozen.segments()).hasSize(1);
        assertThat(frozen.segments().get(0).endSeq()).isEqualTo(1);
        ContextProjectionV2 projection = contextCompilerV2Service.compile(workspaceId,
                com.noteweave.security.CurrentUserProvider.LOCAL_USER_ID, conversationId,
                1, query, "QA", 10_000);
        assertThat(projection.rawTail()).extracting(ContextProjectionV2.RawMessage::messageId)
                .containsExactly(queryId);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> topicProjectionV2Service.refresh(
                workspaceId, conversationId)).isInstanceOf(com.noteweave.common.BusinessException.class);

        String answer = "Frozen response";
        String answerHash = java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256")
                        .digest(answer.getBytes(StandardCharsets.UTF_8)));
        jdbcTemplate.update("""
                update conversation_message set content = ?, content_hash = ?, context_status = 'CURRENT'
                where id = ?
                """, answer, answerHash, placeholderId);
        assertThat(topicProjectionV2Service.refresh(workspaceId, conversationId)
                .segments().get(0).endSeq()).isEqualTo(2);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> contextCompilerV2Service.compile(
                workspaceId, com.noteweave.security.CurrentUserProvider.LOCAL_USER_ID,
                conversationId, 1, query, "QA", 10_000))
                .isInstanceOf(com.noteweave.common.BusinessException.class);
    }

    @Test
    void shadowConstraintProjectionPersistsUserCorrectionAndNeverPromotesAssistantText() throws Exception {
        String workspaceId = createWorkspace();
        String conversationId = createConversation(workspaceId);
        JsonNode first = submit(conversationId, Map.of(
                "content", "这份报告用英文。", "answer_mode", "QA",
                "client_request_id", "constraint-shadow-1"), 200);
        submit(conversationId, Map.of(
                "content", "先列三点。", "answer_mode", "QA",
                "client_request_id", "constraint-shadow-2"), 200);
        submit(conversationId, Map.of(
                "content", "更正：不要英文，改用中文。", "answer_mode", "QA",
                "client_request_id", "constraint-shadow-3"), 200);

        topicProjectionV2Service.refresh(workspaceId, conversationId);
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from conversation_constraint_v2 where conversation_id = ?
                """, Integer.class, conversationId)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("""
                select status from conversation_constraint_v2 where source_message_id = ?
                """, String.class, first.path("message_id").asText())).isEqualTo("REVOKED");
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from conversation_constraint_v2 c
                join conversation_message m on m.id = c.source_message_id
                where c.conversation_id = ? and m.role <> 'USER'
                """, Integer.class, conversationId)).isZero();
    }

    @Test
    void completedResearchExposesAnImmutableFinalEvidenceManifest() throws Exception {
        String workspaceId = createWorkspace();
        String conversationId = createConversation(workspaceId);
        JsonNode receipt = submit(conversationId, Map.of(
                "content", "research final evidence manifest",
                "answer_mode", "DEEP_RESEARCH",
                "client_request_id", "research-evidence-manifest-1"
        ), 200);
        String researchRunId = receipt.path("research_run_id").asText();
        finalizeResearchRun(researchRunId, "Final evidence report", "final-evidence-1",
                "external:durable-memory", "Durable Memory Research",
                "Final research evidence must be frozen with the report.");
        assertThat(researchFinalizationService.finalizeIncrementalRun(researchRunId).idempotentReplay()).isTrue();

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/research-runs/{researchRunId}/evidence",
                        workspaceId, researchRunId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.run_id").value(researchRunId))
                .andExpect(jsonPath("$.data.evidence.length()").value(1))
                .andExpect(jsonPath("$.data.evidence[0].evidence_id").value("final-evidence-1"))
                .andExpect(jsonPath("$.data.evidence[0].source_id").value("external:durable-memory"))
                .andExpect(jsonPath("$.data.evidence[0].excerpt",
                        org.hamcrest.Matchers.containsString("frozen with the report")))
                .andExpect(jsonPath("$.data.evidence[0].content_hash",
                        org.hamcrest.Matchers.matchesPattern("[0-9a-f]{64}")));
    }

    @Test
    void deletingResearchEvidenceSourceRedactsTheManifestAndDowngradesReplay() throws Exception {
        String workspaceId = createWorkspace();
        String sourceId = completeSingleChunkUpload(workspaceId, "research-evidence-delete.md", """
                ResearchDeleteEvidence must be removed from replay data when its source is deleted.
                """.getBytes(StandardCharsets.UTF_8));
        String conversationId = createConversation(workspaceId);
        JsonNode receipt = submit(conversationId, Map.of(
                "content", "research deletion propagation",
                "answer_mode", "DEEP_RESEARCH",
                "client_request_id", "research-evidence-delete-1"
        ), 200);
        String researchRunId = receipt.path("research_run_id").asText();
        finalizeResearchRun(researchRunId, "Research deletion report", "research-delete-evidence-1",
                sourceId, "Research deletion source",
                "ResearchDeleteEvidence must be removed from replay data.");

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                        "/api/v2/workspaces/{workspaceId}/sources/{sourceId}", workspaceId, sourceId))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/runs/RESEARCH/{runId}/input-snapshot",
                        workspaceId, researchRunId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.replay_availability").value("METADATA_ONLY"));
        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/research-runs/{researchRunId}/evidence",
                        workspaceId, researchRunId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.evidence[0].source_id").value(sourceId))
                .andExpect(jsonPath("$.data.evidence[0].excerpt").value(""))
                .andExpect(jsonPath("$.data.evidence[0].content_hash")
                        .value("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"));
    }

    @Test
    void genericWorkerCallbackCannotCompleteAResearchPlaceholder() throws Exception {
        String workspaceId = createWorkspace();
        String conversationId = createConversation(workspaceId);
        JsonNode receipt = submit(conversationId, Map.of(
                "content", "fenced research callback",
                "answer_mode", "DEEP_RESEARCH",
                "client_request_id", "research-fencing-1"
        ), 200);
        String researchRunId = receipt.path("research_run_id").asText();
        String taskId = jdbcTemplate.queryForObject(
                "select task_id from research_run where id = ?", String.class, researchRunId);

        mockMvc.perform(post("/internal/worker/tasks/{taskId}/complete", taskId)
                        .header("X-NoteWeave-Idempotency-Key", "stale-event")
                        .header("X-NoteWeave-Callback-Event-Id", "stale-event")
                        .header("X-NoteWeave-Attempt-No", "0")
                        .header("X-NoteWeave-Fencing-Token", "0")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "result_type", "RESEARCH_REPORT",
                                "result_title", "Stale Report",
                                "result_payload", Map.of("report_markdown", "# Stale Report"),
                                "trace_summary", "stale",
                                "citations", java.util.List.of()
                        ))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORKER_CALLBACK_TASK_TYPE_INVALID"));

        mockMvc.perform(get(
                        "/api/v2/workspaces/{workspaceId}/conversations/{conversationId}/messages",
                        workspaceId, conversationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[1].context_status").value("PENDING"));

        finalizeResearchRun(researchRunId, "Current report", "current-evidence",
                "external:current", "Current source", "Current verified evidence.");

        mockMvc.perform(get(
                        "/api/v2/workspaces/{workspaceId}/conversations/{conversationId}/messages",
                        workspaceId, conversationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[1].context_status").value("CURRENT"));
    }

    @Test
    void oneConversationCanSwitchTurnModeWithoutChangingHistoricalRuns() throws Exception {
        String workspaceId = createWorkspace();
        String conversationId = createConversation(workspaceId);
        String head = null;
        String[] modes = {"WIKI", "QA", "DEEP_RESEARCH", "NOTE"};
        for (int index = 0; index < modes.length; index++) {
            java.util.Map<String, Object> request = new java.util.LinkedHashMap<>();
            request.put("content", "mode turn " + modes[index]);
            request.put("answer_mode", modes[index]);
            request.put("client_request_id", "mode-switch-" + index);
            if (head != null) {
                request.put("expected_history_head_message_id", head);
            }
            JsonNode receipt = submit(conversationId, request, 200);
            String expectedKind = "DEEP_RESEARCH".equals(modes[index]) ? "RESEARCH" : "ANSWER";
            assertThat(receipt.path("execution_kind").asText()).isEqualTo(expectedKind);
            head = receipt.path("assistant_message_id").asText();
        }

        mockMvc.perform(get(
                        "/api/v2/workspaces/{workspaceId}/conversations/{conversationId}/messages",
                        workspaceId, conversationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(8))
                .andExpect(jsonPath("$.data[0].requested_turn_mode").value("WIKI"))
                .andExpect(jsonPath("$.data[2].requested_turn_mode").value("QA"))
                .andExpect(jsonPath("$.data[4].requested_turn_mode").value("DEEP_RESEARCH"))
                .andExpect(jsonPath("$.data[6].requested_turn_mode").value("NOTE"));
    }

    @Test
    void failedAnswerPreparationRemainsQueryableWithItsOriginalLedgerIds() throws Exception {
        String workspaceId = createWorkspace();
        String conversationId = createConversation(workspaceId);
        String clientRequestId = "failed-preparation-1";

        mockMvc.perform(post("/api/v2/conversations/{conversationId}/messages", conversationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "content", "invalid scoped note",
                                "answer_mode", "NOTE",
                                "client_request_id", clientRequestId,
                                "source_scope_source_ids", java.util.List.of(
                                        "00000000-0000-0000-0000-000000000001")
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ANSWER_SOURCE_SCOPE_UNSUPPORTED"));

        mockMvc.perform(get(
                        "/api/v2/workspaces/{workspaceId}/conversations/{conversationId}/turn-submissions/{clientRequestId}",
                        workspaceId, conversationId, clientRequestId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.execution_kind").value("ANSWER"))
                .andExpect(jsonPath("$.data.query_message_id").isNotEmpty())
                .andExpect(jsonPath("$.data.answer_message_id").isNotEmpty())
                .andExpect(jsonPath("$.data.answer_run_id").isNotEmpty())
                .andExpect(jsonPath("$.data.error_code").value("ANSWER_SOURCE_SCOPE_UNSUPPORTED"));
    }

    @Test
    void recoveryReusesFrozenPreparationAndOriginalLedgerIds() throws Exception {
        String workspaceId = createWorkspace();
        String conversationId = createConversation(workspaceId);
        String clientRequestId = "failed-recovery-1";
        Map<String, Object> request = Map.of(
                "content", "invalid scoped note recovery",
                "answer_mode", "NOTE",
                "client_request_id", clientRequestId,
                "source_scope_source_ids", java.util.List.of(
                        "00000000-0000-0000-0000-000000000002")
        );
        mockMvc.perform(post("/api/v2/conversations/{conversationId}/messages", conversationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());

        MvcResult beforeResult = mockMvc.perform(get(
                        "/api/v2/workspaces/{workspaceId}/conversations/{conversationId}/turn-submissions/{clientRequestId}",
                        workspaceId, conversationId, clientRequestId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.preparation_attempt").value(1))
                .andReturn();
        JsonNode before = objectMapper.readTree(beforeResult.getResponse().getContentAsString()).path("data");

        mockMvc.perform(post("/internal/recovery/turn-submissions/{submissionId}/prepare",
                        before.path("submission_id").asText()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ANSWER_SOURCE_SCOPE_UNSUPPORTED"));

        mockMvc.perform(get(
                        "/api/v2/workspaces/{workspaceId}/conversations/{conversationId}/turn-submissions/{clientRequestId}",
                        workspaceId, conversationId, clientRequestId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.preparation_attempt").value(2))
                .andExpect(jsonPath("$.data.query_message_id").value(before.path("query_message_id").asText()))
                .andExpect(jsonPath("$.data.answer_message_id").value(before.path("answer_message_id").asText()))
                .andExpect(jsonPath("$.data.answer_run_id").value(before.path("answer_run_id").asText()));
    }

    @Test
    void concurrentIdenticalSubmissionsReturnTheSingleWinnerReceipt() throws Exception {
        String conversationId = createConversation(createWorkspace());
        String payload = objectMapper.writeValueAsString(Map.of(
                "content", "concurrent idempotent turn",
                "answer_mode", "QA",
                "client_request_id", "concurrent-winner-1"
        ));
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(2);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        try {
            java.util.concurrent.Callable<MvcResult> call = () -> {
                ready.countDown();
                start.await();
                return mockMvc.perform(post("/api/v2/conversations/{conversationId}/messages", conversationId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(payload))
                        .andReturn();
            };
            java.util.concurrent.Future<MvcResult> firstFuture = executor.submit(call);
            java.util.concurrent.Future<MvcResult> secondFuture = executor.submit(call);
            ready.await();
            start.countDown();
            MvcResult firstResult = firstFuture.get(20, java.util.concurrent.TimeUnit.SECONDS);
            MvcResult secondResult = secondFuture.get(20, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(firstResult.getResponse().getStatus()).isEqualTo(200);
            assertThat(secondResult.getResponse().getStatus()).isEqualTo(200);
            JsonNode first = objectMapper.readTree(firstResult.getResponse().getContentAsString()).path("data");
            JsonNode second = objectMapper.readTree(secondResult.getResponse().getContentAsString()).path("data");
            assertThat(second.path("submission_id").asText())
                    .isEqualTo(first.path("submission_id").asText());
            assertThat(second.path("message_id").asText()).isEqualTo(first.path("message_id").asText());
            assertThat(second.path("answer_run_id").asText()).isEqualTo(first.path("answer_run_id").asText());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void recoveryRejectsPreparingSubmissionBeforeItsLeaseIsStale() throws Exception {
        JsonNode failed = createFailedAnswerSubmission("recovery-fresh-preparing-1");
        jdbcTemplate.update("""
                update turn_submission
                set status = 'PREPARING', updated_at = current_timestamp
                where id = ?
                """, failed.path("submission_id").asText());

        mockMvc.perform(post("/internal/recovery/turn-submissions/{submissionId}/prepare",
                        failed.path("submission_id").asText()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TURN_RECOVERY_NOT_STALE"));
    }

    @Test
    void concurrentRecoveryClaimsOneStalePreparationLease() throws Exception {
        JsonNode failed = createFailedAnswerSubmission("recovery-stale-lease-1");
        String submissionId = failed.path("submission_id").asText();
        jdbcTemplate.update("""
                update turn_submission
                set status = 'PREPARING', updated_at = dateadd('SECOND', -120, current_timestamp)
                where id = ?
                """, submissionId);

        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(2);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        try {
            java.util.concurrent.Callable<MvcResult> recover = () -> {
                ready.countDown();
                start.await();
                return mockMvc.perform(post("/internal/recovery/turn-submissions/{submissionId}/prepare", submissionId))
                        .andReturn();
            };
            java.util.concurrent.Future<MvcResult> first = executor.submit(recover);
            java.util.concurrent.Future<MvcResult> second = executor.submit(recover);
            ready.await();
            start.countDown();
            int firstStatus = first.get(20, java.util.concurrent.TimeUnit.SECONDS).getResponse().getStatus();
            int secondStatus = second.get(20, java.util.concurrent.TimeUnit.SECONDS).getResponse().getStatus();

            assertThat(java.util.List.of(firstStatus, secondStatus))
                    .containsExactlyInAnyOrder(400, 409);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void recoveryAfterProcessExitBetweenPrepareAndReadyKeepsTheOriginalLedger() throws Exception {
        String workspaceId = createWorkspace();
        String conversationId = createConversation(workspaceId);
        String clientRequestId = "recovery-after-prepare-1";
        org.mockito.Mockito.doThrow(new AssertionError("simulated process exit after transaction A"))
                .when(chatService)
                .compilePreparedAnswer(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any());
        try {
            mockMvc.perform(post("/api/v2/conversations/{conversationId}/messages", conversationId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "content", "recover after prepare",
                                    "answer_mode", "QA",
                                    "client_request_id", clientRequestId
                            ))))
                    .andExpect(status().isInternalServerError());
        } finally {
            org.mockito.Mockito.reset(chatService);
        }

        MvcResult beforeResult = mockMvc.perform(get(
                        "/api/v2/workspaces/{workspaceId}/conversations/{conversationId}/turn-submissions/{clientRequestId}",
                        workspaceId, conversationId, clientRequestId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PREPARING"))
                .andReturn();
        JsonNode before = objectMapper.readTree(beforeResult.getResponse().getContentAsString()).path("data");
        jdbcTemplate.update("""
                update turn_submission
                set updated_at = dateadd('SECOND', -120, current_timestamp)
                where id = ?
                """, before.path("submission_id").asText());

        mockMvc.perform(post("/internal/recovery/turn-submissions/{submissionId}/prepare",
                        before.path("submission_id").asText()))
                .andExpect(status().isOk());

        mockMvc.perform(get(
                        "/api/v2/workspaces/{workspaceId}/conversations/{conversationId}/turn-submissions/{clientRequestId}",
                        workspaceId, conversationId, clientRequestId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("READY"))
                .andExpect(jsonPath("$.data.preparation_attempt").value(2))
                .andExpect(jsonPath("$.data.query_message_id").value(before.path("query_message_id").asText()))
                .andExpect(jsonPath("$.data.answer_message_id").value(before.path("answer_message_id").asText()))
                .andExpect(jsonPath("$.data.answer_run_id").value(before.path("answer_run_id").asText()));
        mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/runs/ANSWER/{runId}/input-snapshot",
                        workspaceId, before.path("answer_run_id").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.query_message_id").value(before.path("query_message_id").asText()))
                .andExpect(jsonPath("$.data.history_head_message_id").doesNotExist());
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from conversation_message where conversation_id = ?", Integer.class, conversationId))
                .isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from answer_run where conversation_id = ?", Integer.class, conversationId))
                .isEqualTo(1);
    }

    private JsonNode createFailedAnswerSubmission(String clientRequestId) throws Exception {
        String workspaceId = createWorkspace();
        String conversationId = createConversation(workspaceId);
        mockMvc.perform(post("/api/v2/conversations/{conversationId}/messages", conversationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "content", "invalid scoped note",
                                "answer_mode", "NOTE",
                                "client_request_id", clientRequestId,
                                "source_scope_source_ids", java.util.List.of(
                                        "00000000-0000-0000-0000-000000000003")
                        ))))
                .andExpect(status().isBadRequest());
        MvcResult result = mockMvc.perform(get(
                        "/api/v2/workspaces/{workspaceId}/conversations/{conversationId}/turn-submissions/{clientRequestId}",
                        workspaceId, conversationId, clientRequestId))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
    }

    private void finalizeResearchRun(
            String researchRunId,
            String verifiedValue,
            String evidenceKey,
            String sourceId,
            String sourceTitle,
            String quote
    ) throws Exception {
        String parentTaskId = jdbcTemplate.queryForObject(
                "select task_id from research_run where id = ?", String.class, researchRunId);
        jdbcTemplate.update("""
                update research_run
                set status = 'RUNNING', agent_execution_mode = 'INCREMENTAL_V1'
                where id = ?
                """, researchRunId);
        jdbcTemplate.update("update task set task_status = 'RUNNING' where id = ?", parentTaskId);
        jdbcTemplate.update("update research_agent_task set status = 'SUBMITTED' where research_run_id = ?",
                researchRunId);
        jdbcTemplate.update("delete from research_cell_evidence where research_run_id = ?", researchRunId);
        jdbcTemplate.update("delete from research_cell_merge where research_run_id = ?", researchRunId);
        jdbcTemplate.update("delete from source_evidence where research_run_id = ?", researchRunId);
        jdbcTemplate.update("delete from research_cell where research_run_id = ?", researchRunId);
        jdbcTemplate.update("delete from research_row where research_run_id = ?", researchRunId);
        jdbcTemplate.update("delete from research_branch where research_run_id = ?", researchRunId);

        String suffix = Ids.newId();
        String rowId = Ids.newId();
        String cellId = Ids.newId();
        String sourceEvidenceId = Ids.newId();
        String cellKey = "contract:" + suffix;
        jdbcTemplate.update("""
                insert into research_row(id, research_run_id, row_key, row_status)
                values (?, ?, ?, 'CANDIDATE_READY')
                """, rowId, researchRunId, "row:" + suffix);
        jdbcTemplate.update("""
                insert into research_cell(
                    id, research_run_id, research_row_id, cell_key, column_key,
                    candidate_value, cell_status, evidence_refs_json, repair_count
                ) values (?, ?, ?, ?, 'finding', ?, 'VERIFIED', ?, 0)
                """, cellId, researchRunId, rowId, cellKey, verifiedValue,
                objectMapper.writeValueAsString(java.util.List.of(evidenceKey)));
        jdbcTemplate.update("""
                insert into source_evidence(
                    id, research_run_id, evidence_key, window_id, source_id, source_title,
                    quote_text, claim_text, snapshot_status
                ) values (?, ?, ?, ?, ?, ?, ?, ?, 'EXTERNAL_ARCHIVED')
                """, sourceEvidenceId, researchRunId, evidenceKey, "window:" + suffix,
                sourceId, sourceTitle, quote, verifiedValue);
        jdbcTemplate.update("""
                insert into research_cell_evidence(
                    id, research_run_id, research_cell_id, source_evidence_id, evidence_key
                ) values (?, ?, ?, ?, ?)
                """, Ids.newId(), researchRunId, cellId, sourceEvidenceId, evidenceKey);

        assertThat(researchFinalizationService.finalizeIncrementalRun(researchRunId).idempotentReplay()).isFalse();
    }

    @Test
    void nextPrefixSummaryIsBuiltIncrementallyOnTopOfTheReadyOne() throws Exception {
        String workspaceId = createWorkspace();
        String conversationId = createConversation(workspaceId);
        for (int index = 1; index <= 5; index++) {
            submit(conversationId, Map.of(
                    "content", "incremental-prefix turn " + index,
                    "answer_mode", "QA",
                    "client_request_id", "incremental-prefix-turn-" + index
            ), 200);
        }
        String firstRevisionId = jdbcTemplate.queryForObject("""
                select r.id from segment_summary_revision r
                join conversation_segment s on s.id = r.segment_id
                where s.conversation_id = ? and s.covered_end_seq = 3
                """, String.class, conversationId);
        String firstSegmentId = jdbcTemplate.queryForObject(
                "select segment_id from segment_summary_revision where id = ?", String.class, firstRevisionId);
        mockMvc.perform(post("/internal/conversation-segments/{segmentId}/summary-revisions/{revisionId}/promote",
                        firstSegmentId, firstRevisionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "summary_text", "first prefix summary",
                                "content_hash", "d".repeat(64),
                                "summary_method", "LLM_FULL"))))
                .andExpect(status().isOk());

        submit(conversationId, Map.of(
                "content", "incremental-prefix turn 6",
                "answer_mode", "QA",
                "client_request_id", "incremental-prefix-turn-6"
        ), 200);

        // 第二版前缀摘要以第一版为起点，只把第 4、5 条消息交给摘要任务
        Map<String, Object> second = jdbcTemplate.queryForMap("""
                select r.id, r.base_revision_id from segment_summary_revision r
                join conversation_segment s on s.id = r.segment_id
                where s.conversation_id = ? and s.covered_end_seq = 5
                """, conversationId);
        assertThat(second.get("base_revision_id")).isEqualTo(firstRevisionId);
        JsonNode payload = objectMapper.readTree(jdbcTemplate.queryForObject("""
                select payload_json from task_outbox where message_key = ?
                """, String.class, second.get("id")));
        assertThat(payload.path("base_summary_text").asText()).isEqualTo("first prefix summary");
        assertThat(payload.path("base_covered_end_seq").asInt()).isEqualTo(3);
        assertThat(payload.path("covered_start_seq").asInt()).isEqualTo(1);
        assertThat(payload.path("covered_end_seq").asInt()).isEqualTo(5);
        assertThat(payload.path("source_messages").findValuesAsText("message_seq"))
                .containsExactly("4", "5");
        assertThat(jdbcTemplate.queryForObject(
                "select summary_method from segment_summary_revision where id = ?", String.class, firstRevisionId))
                .isEqualTo("LLM_FULL");
    }

    @Test
    void topicSummaryIsExtendedIncrementallyWithinTheSameTopic() throws Exception {
        String workspaceId = createWorkspace();
        String conversationId = createConversation(workspaceId);
        submit(conversationId, Map.of("content", "缓存一致性有哪些常见方案",
                "answer_mode", "QA", "client_request_id", "incremental-topic-0"), 200);
        for (int index = 1; index <= 4; index++) {
            submit(conversationId, Map.of("content", "补充：缓存一致性第 " + index + " 点怎么做",
                    "answer_mode", "QA", "client_request_id", "incremental-topic-" + index), 200);
        }
        var projection = topicProjectionV2Service.refresh(workspaceId, conversationId);
        assertThat(projection.segments()).hasSize(1);
        String segmentId = projection.segments().get(0).segmentId();
        String firstRevisionId = jdbcTemplate.queryForObject("""
                select id from conversation_topic_summary_revision_v2
                where segment_id = ? and status = 'BUILDING'
                """, String.class, segmentId);
        String firstSummary = "- 用户在了解缓存一致性方案";
        topicSummaryV2Service.promote(segmentId, firstRevisionId,
                new PromoteSegmentSummaryRequest(firstSummary, sha256Hex(firstSummary), "LLM_FULL"));

        for (int index = 5; index <= 6; index++) {
            submit(conversationId, Map.of("content", "补充：缓存一致性第 " + index + " 点怎么做",
                    "answer_mode", "QA", "client_request_id", "incremental-topic-" + index), 200);
        }
        topicProjectionV2Service.refresh(workspaceId, conversationId);

        Map<String, Object> second = jdbcTemplate.queryForMap("""
                select id, start_seq, end_seq, base_revision_id from conversation_topic_summary_revision_v2
                where segment_id = ? and status = 'BUILDING'
                """, segmentId);
        assertThat(second.get("base_revision_id")).isEqualTo(firstRevisionId);
        assertThat(((Number) second.get("start_seq")).intValue()).isEqualTo(1);
        JsonNode payload = objectMapper.readTree(jdbcTemplate.queryForObject("""
                select payload_json from task_outbox where message_key = ?
                """, String.class, second.get("id")));
        assertThat(payload.path("base_summary_text").asText()).isEqualTo(firstSummary);
        int baseEnd = payload.path("base_covered_end_seq").asInt();
        assertThat(payload.path("source_messages").findValuesAsText("message_seq"))
                .isNotEmpty()
                .allSatisfy(seq -> assertThat(Integer.parseInt(seq)).isGreaterThan(baseEnd));
        assertThat(payload.path("covered_end_seq").asInt())
                .isEqualTo(((Number) second.get("end_seq")).intValue());

        // 增量生成的摘要不能按原文重算，但哈希不匹配时仍然拒绝晋升
        String secondId = (String) second.get("id");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> topicSummaryV2Service.promote(segmentId, secondId,
                        new PromoteSegmentSummaryRequest("forged", sha256Hex("different"), "LLM_INCREMENTAL")))
                .isInstanceOf(com.noteweave.common.BusinessException.class);
        String secondSummary = firstSummary + "\n- 补充了第 5、6 点";
        topicSummaryV2Service.promote(segmentId, secondId,
                new PromoteSegmentSummaryRequest(secondSummary, sha256Hex(secondSummary), "LLM_INCREMENTAL"));
        assertThat(jdbcTemplate.queryForObject("""
                select summary_method from conversation_topic_summary_revision_v2 where id = ?
                """, String.class, secondId)).isEqualTo("LLM_INCREMENTAL");
    }

    private static String sha256Hex(String value) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private JsonNode submit(String conversationId, Map<String, Object> request, int expectedStatus)
            throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v2/conversations/{conversationId}/messages", conversationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().is(expectedStatus))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
    }

    private String seedResearchSource(String workspaceId) {
        String suffix = java.util.UUID.randomUUID().toString();
        String fileObjectId = java.util.UUID.randomUUID().toString();
        String sourceId = java.util.UUID.randomUUID().toString();
        String snapshotId = java.util.UUID.randomUUID().toString();
        String chunkId = java.util.UUID.randomUUID().toString();
        String content = "Conversation Research source fixture " + suffix;
        String sha = suffix.replace("-", "") + suffix.replace("-", "");
        jdbcTemplate.update("""
                insert into file_object(id, workspace_id, object_key, sha256, file_size, mime_type)
                values (?, ?, ?, ?, ?, 'text/markdown')
                """, fileObjectId, workspaceId, "test/research/" + suffix, sha, content.length());
        jdbcTemplate.update("""
                insert into source(id, workspace_id, file_object_id, title, source_type,
                                   status, parse_status, index_status)
                values (?, ?, ?, ?, 'USER_UPLOAD', 'READY', 'PARSED', 'INDEXED')
                """, sourceId, workspaceId, fileObjectId, "research-fixture-" + suffix + ".md");
        jdbcTemplate.update("""
                insert into source_snapshot(id, source_id, file_object_id, version_no, object_key,
                                            sha256, parse_status, index_status)
                values (?, ?, ?, 1, ?, ?, 'PARSED', 'INDEXED')
                """, snapshotId, sourceId, fileObjectId, "test/research/" + suffix, sha);
        jdbcTemplate.update("""
                insert into source_chunk(id, workspace_id, source_id, source_snapshot_id,
                                         chunk_no, content, token_estimate)
                values (?, ?, ?, ?, 0, ?, 8)
                """, chunkId, workspaceId, sourceId, snapshotId, content);
        jdbcTemplate.update("""
                insert into source_window(id, source_chunk_id, window_no, content)
                values (?, ?, 0, ?)
                """, java.util.UUID.randomUUID().toString(), chunkId, content);
        return sourceId;
    }

    private String createWorkspace() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v2/workspaces")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "turn-module-contract",
                                "description", "conversation turn TDD"
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("workspace_id").asText();
    }

    private String createConversation(String workspaceId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v2/workspaces/{workspaceId}/conversations", workspaceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "title", "Turn contract",
                                "conversation_type", "WORKSPACE_CHAT"
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("conversation_id").asText();
    }

    private String completeSingleChunkUpload(String workspaceId, String fileName, byte[] content) throws Exception {
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
        String uploadId = objectMapper.readTree(init.getResponse().getContentAsString())
                .path("data").path("upload_id").asText();
        mockMvc.perform(put("/api/v2/uploads/{uploadId}/chunks/{chunkIndex}", uploadId, 0)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(content))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v2/uploads/{uploadId}/complete", uploadId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.parse_status", org.hamcrest.Matchers.anyOf(
                        org.hamcrest.Matchers.equalTo("PARSED"),
                        org.hamcrest.Matchers.equalTo("PARSING_QUEUED"))))
                .andExpect(jsonPath("$.data.index_status", org.hamcrest.Matchers.anyOf(
                        org.hamcrest.Matchers.equalTo("INDEXED"),
                        org.hamcrest.Matchers.equalTo("INDEX_QUEUED"),
                        org.hamcrest.Matchers.equalTo("DISABLED"))));
        MvcResult sources = mockMvc.perform(get("/api/v2/workspaces/{workspaceId}/sources", workspaceId))
                .andExpect(status().isOk())
                .andReturn();
        for (JsonNode source : objectMapper.readTree(sources.getResponse().getContentAsString()).path("data")) {
            if (fileName.equals(source.path("title").asText())) {
                String sourceId = source.path("source_id").asText();
                // These evidence contracts exercise the explicit MySQL fallback with a ready projection fixture.
                jdbcTemplate.update("update source set index_status = 'INDEXED' where id = ?", sourceId);
                jdbcTemplate.update("update source_snapshot set index_status = 'INDEXED' where source_id = ?", sourceId);
                jdbcTemplate.update("update source_chunk set projection_status = 'PROJECTED' where source_id = ?", sourceId);
                return sourceId;
            }
        }
        throw new AssertionError("Uploaded source was not listed: " + fileName);
    }
}
