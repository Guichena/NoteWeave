package com.noteweave.answer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.noteweave.common.BusinessException;
import com.noteweave.conversation.ConversationMessageDeletionService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AnswerContentDeletionContractTest {
    private static final String SECRET = "不应在删除后出现的回答原文";
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired AnswerRunService runs;
    @Autowired AnswerGenerationGateway gateway;
    @Autowired ConversationMessageDeletionService deletion;
    @Autowired AnswerGenerationOrchestrator orchestrator;
    @Autowired SessionEventMux runEvents;
    @Autowired ConversationEventMux conversationEvents;
    private String workspace;
    private String conversation;
    private String query;
    private String answer;
    private String request;
    private String run;

    @BeforeEach
    void seedCompletedAnswerAndCachedEvents() {
        workspace = id(); conversation = id(); query = id(); answer = id(); request = id(); run = id();
        jdbc.update("insert into workspace(id, owner_id, name, status) values (?, 'local-user', '删除回归', 'ACTIVE')", workspace);
        jdbc.update("insert into workspace_member(id, workspace_id, user_id, role) values (?, ?, 'local-user', 'OWNER')", id(), workspace);
        jdbc.update("insert into conversation(id, workspace_id, title, conversation_type, status) values (?, ?, '删除回归', 'QA', 'ACTIVE')", conversation, workspace);
        jdbc.update("insert into conversation_message(id, conversation_id, workspace_id, message_seq, role, content) values (?, ?, ?, 1, 'USER', '问题')", query, conversation, workspace);
        jdbc.update("insert into conversation_message(id, conversation_id, workspace_id, message_seq, role, content, assistant_request_id) values (?, ?, ?, 2, 'ASSISTANT', ?, ?)", answer, conversation, workspace, SECRET, request);
        jdbc.update("""
                insert into answer_run(id, workspace_id, conversation_id, mode, status, query_message_id,
                    answer_message_id, assistant_request_id, created_by, updated_by, event_seq)
                values (?, ?, ?, 'QA', 'COMPLETED', ?, ?, ?, 'local-user', 'local-user', 2)
                """, run, workspace, conversation, query, answer, request);
        jdbc.update("insert into message_revision(id, workspace_id, answer_run_id, message_id, revision_no, status, content, created_by) values (?, ?, ?, ?, 1, 'FINAL', ?, 'local-user')", id(), workspace, run, answer, SECRET);
        jdbc.update("""
                insert into run_input_snapshot(id, workspace_id, execution_kind, answer_run_id, conversation_id,
                    query_message_id, assistant_message_id, requested_turn_mode, conversation_cutoff_seq,
                    retrieval_config_json, snapshot_json, compiler_version, token_budget_json)
                values (?, ?, 'ANSWER', ?, ?, ?, ?, 'QA', 2, '{}', '{}', 'test', '{}')
                """, id(), workspace, run, conversation, query, answer);
        jdbc.update("insert into answer_event(id, workspace_id, answer_run_id, seq, event_type, payload_json) values (?, ?, ?, 1, 'answer.delta', ?)", id(), workspace, run, "{\"text\":\"" + SECRET + "\"}");
        AnswerLiveEvent delta = runEvents.publishAt(run, 1, "answer.delta", SECRET);
        conversationEvents.publish(conversation, run, delta);
        AnswerLiveEvent terminal = runEvents.publishAt(run, 2, "answer.completed", answer);
        conversationEvents.publish(conversation, run, terminal);
    }

    @Test
    void deletingCompletedAnswerRedactsEveryReadPathIncludingCachedStreams() throws Exception {
        assertThat(runs.get(workspace, run).content()).isEqualTo(SECRET);
        mvc.perform(delete(messagePath(answer))).andExpect(status().isOk());
        assertRedactedReadPaths();
        assertThat(jdbc.queryForObject("select content from message_revision where answer_run_id = ?", String.class, run)).isEmpty();
        assertThat(jdbc.queryForObject("select payload_json from answer_event where answer_run_id = ?", String.class, run)).doesNotContain(SECRET);
    }

    @Test
    void deletionBeforeGenerationCompletionCannotRestoreEitherBody() {
        makeGenerating();
        deletion.delete(workspace, conversation, answer);
        gateway.persistContent(workspace, answer, SECRET + "晚到结果");
        assertThat(runs.complete(workspace, run, "owner", SECRET, 20))
                .isEqualTo(AnswerRunService.CompletionOutcome.CANCELLED);
        assertThat(jdbc.queryForObject("select content from conversation_message where id = ?", String.class, answer)).isEmpty();
        assertThat(jdbc.queryForObject("select content from message_revision where answer_run_id = ?", String.class, run)).isEmpty();
    }

    @Test
    void completionBeforeDeletionStillErasesFinalRevision() {
        makeGenerating();
        assertThat(runs.complete(workspace, run, "owner", SECRET, 20))
                .isEqualTo(AnswerRunService.CompletionOutcome.COMPLETED);
        deletion.delete(workspace, conversation, answer);
        assertThat(runs.get(workspace, run).content()).isEmpty();
        assertThat(jdbc.queryForObject("select content from message_revision where answer_run_id = ?", String.class, run)).isEmpty();
    }

    @Test
    void deletingQuestionAlsoBlocksDerivedAnswerAndLateWrites() {
        deletion.delete(workspace, conversation, query);
        gateway.persistContent(workspace, answer, SECRET + "晚到结果");
        assertThat(runs.get(workspace, run).content()).isEmpty();
        assertThat(jdbc.queryForObject("select content from message_revision where answer_run_id = ?", String.class, run)).isEmpty();
        assertThat(jdbc.queryForObject("select content from conversation_message where id = ?", String.class, answer))
                .doesNotContain("晚到结果");
    }

    @Test
    void sourceDeletionRedactsAnswerAndEvidenceWithoutDeletingTheMessage() throws Exception {
        String source = id(); String file = id();
        jdbc.update("insert into file_object(id, workspace_id, object_key, sha256, file_size, mime_type) values (?, ?, ?, ?, 1, 'text/plain')", file, workspace, "test/" + file, file.replace("-", "").repeat(2));
        jdbc.update("insert into source(id, workspace_id, file_object_id, title, source_type, status, parse_status, index_status) values (?, ?, ?, '依据', 'USER_UPLOAD', 'READY', 'PARSED', 'INDEXED')", source, workspace, file);
        jdbc.update("""
                insert into answer_run_evidence_manifest(id, workspace_id, answer_run_id, rank_no,
                    evidence_id, evidence_kind, source_id, excerpt, content_hash, character_cost)
                values (?, ?, ?, 1, 'test-evidence', 'PASSAGE', ?, ?, ?, 10)
                """, id(), workspace, run, source, SECRET, "a".repeat(64));
        assertThat(runs.evidenceManifest(workspace, run).evidence()).hasSize(1);
        mvc.perform(delete("/api/v2/workspaces/" + workspace + "/sources/" + source)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select context_status from conversation_message where id = ?", String.class, answer)).isNotEqualTo("DELETED");
        assertThat(runs.evidenceManifest(workspace, run).evidence()).hasSize(1)
                .allSatisfy(evidence -> assertThat(evidence.excerpt()).isEmpty());
        assertRedactedReadPaths();
    }

    @Test
    void storedFullSnapshotDoesNotOverrideAnUnreadableSourceReference() {
        jdbc.update("""
                insert into answer_run_evidence_manifest(id, workspace_id, answer_run_id, rank_no,
                    evidence_id, evidence_kind, source_id, excerpt, content_hash, character_cost)
                values (?, ?, ?, 1, 'missing-evidence', 'PASSAGE', ?, ?, ?, 10)
                """, id(), workspace, run, id(), SECRET, "a".repeat(64));
        assertThat(runs.get(workspace, run).content()).isEmpty();
        assertThat(runs.eventsAfter(workspace, run, 0)).allSatisfy(event -> assertThat(event.payloadJson()).isEmpty());
    }

    private void assertRedactedReadPaths() throws Exception {
        assertThat(runs.get(workspace, run).content()).isEmpty();
        assertThat(runs.listConversationRuns(workspace, conversation, 20)).allSatisfy(row -> assertThat(row.content()).isEmpty());
        assertThat(runs.eventsAfter(workspace, run, 0)).allSatisfy(event -> assertThat(event.payloadJson()).doesNotContain(SECRET));
        assertThatThrownBy(() -> gateway.load(request)).isInstanceOfSatisfying(BusinessException.class,
                failure -> assertThat(failure.code()).isEqualTo("ANSWER_CONTENT_REDACTED"));
        List<AnswerLiveEvent> cached = new ArrayList<>();
        orchestrator.followRun(workspace, run, 0, cached::add);
        assertThat(cached).isNotEmpty().allSatisfy(event -> assertThat(event.data()).doesNotContain(SECRET));
        assertThat(mvc.perform(get("/api/v2/workspaces/" + workspace + "/conversations/" + conversation + "/messages"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).doesNotContain(SECRET);
        assertThat(stream("/api/v2/workspaces/" + workspace + "/answer-runs/" + run + "/events")).doesNotContain(SECRET);
        assertThat(stream("/api/v2/workspaces/" + workspace + "/conversations/" + conversation + "/events?close_on_terminal=true")).doesNotContain(SECRET);
    }

    private String stream(String path) throws Exception {
        MvcResult result = mvc.perform(get(path)).andReturn();
        result.getAsyncResult(5_000);
        return mvc.perform(asyncDispatch(result)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private void makeGenerating() {
        jdbc.update("update answer_run set status = 'GENERATING', stream_owner = 'owner' where id = ?", run);
        jdbc.update("update message_revision set status = 'STREAMING' where answer_run_id = ?", run);
    }

    private String messagePath(String message) {
        return "/api/v2/workspaces/" + workspace + "/conversations/" + conversation + "/messages/" + message;
    }

    private static String id() { return UUID.randomUUID().toString(); }
}
