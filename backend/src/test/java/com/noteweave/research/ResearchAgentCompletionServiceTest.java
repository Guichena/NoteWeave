package com.noteweave.research;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** MA4G-2/3 atomic commit, replay, rollback and concurrency red tests. */
@SpringBootTest
@ActiveProfiles("test")
class ResearchAgentCompletionServiceTest {

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ResearchAgentTaskService taskService;
    @Autowired private ResearchAgentCommandOutboxService outboxService;
    @Autowired private ResearchBudgetAndCheckpointService budgetService;
    @Autowired private ResearchAgentCompletionService completionService;
    @Autowired private ResearchAgentCompletionCanonicalizer canonicalizer;
    @Autowired private ResearchAgentLifecycleService lifecycleService;
    @Autowired private ResearchAgentEvidenceIngestionService evidenceIngestionService;
    @Autowired private ResearchAgentCandidateIngressService candidateIngressService;
    @Autowired private MeterRegistry meterRegistry;

    @MockBean private ResearchAgentCompletionFaultInjector faultInjector;

    @BeforeEach
    void resetFaults() {
        reset(faultInjector);
    }

    @Test
    void shouldAtomicallyCommitExecutionAggregateChildrenCellsBudgetTaskOutboxAndBindings() {
        Fixture fixture = fixture(1);
        ResearchAgentCompletionEnvelope envelope = envelope(fixture, true);
        ResearchAgentCompletionReceipt receipt = completionService.complete(fixture.taskId(), envelope);

        assertThat(receipt.idempotentReplay()).isFalse();
        assertThat(receipt.evidenceAppended()).isEqualTo(1);
        assertThat(receipt.candidateCount()).isEqualTo(1);
        assertThat(receipt.acceptedMerges()).singleElement().satisfies(merge -> {
            assertThat(merge.cellKey()).isEqualTo(fixture.cellKeys().get(0));
            assertThat(merge.fromVersion()).isZero();
            assertThat(merge.toVersion()).isEqualTo(1);
        });
        assertThat(count("research_agent_execution", "research_agent_task_id", fixture.taskId())).isEqualTo(1);
        assertThat(count("research_agent_completion", "research_agent_task_id", fixture.taskId())).isEqualTo(1);
        assertThat(count("source_evidence", "agent_completion_id", receipt.completionId())).isEqualTo(1);
        assertThat(count("research_agent_candidate", "agent_completion_id", receipt.completionId())).isEqualTo(1);
        assertThat(count("research_cell_merge", "agent_completion_id", receipt.completionId())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from source_evidence where agent_completion_id = ? and content_digest is not null",
                Integer.class, receipt.completionId())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from research_agent_candidate where agent_completion_id = ? and content_digest is not null and research_agent_execution_id = ?",
                Integer.class, receipt.completionId(), receipt.executionId())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from research_cell_merge where agent_completion_id = ? and content_digest is not null",
                Integer.class, receipt.completionId())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from research_cell_evidence rce
                join source_evidence se on se.id = rce.source_evidence_id
                where rce.research_run_id = ? and se.agent_completion_id = ?
                """, Integer.class, fixture.runId(), receipt.completionId())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForMap(
                "select cell_status, active_task_id, cell_version, candidate_value from research_cell where research_run_id = ? and cell_key = ?",
                fixture.runId(), fixture.cellKeys().get(0)))
                .containsEntry("cell_status", "VERIFIED")
                .containsEntry("cell_version", 1)
                .containsEntry("candidate_value", "value-0")
                .containsEntry("active_task_id", null);
        assertThat(jdbcTemplate.queryForObject(
                "select status from research_agent_task where id = ?", String.class, fixture.taskId()))
                .isEqualTo("SUBMITTED");
        assertThat(jdbcTemplate.queryForObject(
                "select status from research_agent_outbox where research_agent_task_id = ?", String.class, fixture.taskId()))
                .isEqualTo("CANCELLED");
        Map<String, Object> reservation = jdbcTemplate.queryForMap(
                "select state, reserved_json, consumed_json, released_json, agent_completion_id, settlement_key, finalized_at "
                        + "from research_budget_reservation where research_agent_task_id = ?", fixture.taskId());
        assertThat(reservation.get("state")).isEqualTo("SETTLED");
        assertThat(reservation.get("agent_completion_id")).isEqualTo(receipt.completionId());
        assertThat(reservation.get("settlement_key")).isEqualTo(fixture.executionKey());
        assertThat(reservation.get("finalized_at")).isNotNull();
        assertThat(String.valueOf(reservation.get("released_json"))).contains("\"llm_calls\":2");
        assertThat(receipt.budget().reserved()).allSatisfy((key, reserved) ->
                assertThat(receipt.budget().consumed().get(key) + receipt.budget().released().get(key))
                        .isEqualTo(reserved));
        Map<String, Object> completion = jdbcTemplate.queryForMap("""
                select envelope_json, envelope_size_bytes, envelope_digest, receipt_json, receipt_digest
                from research_agent_completion where id = ?
                """, receipt.completionId());
        String storedEnvelope = String.valueOf(completion.get("envelope_json"));
        assertThat(storedEnvelope).isEqualTo(canonicalizer.canonicalJson(envelope, true));
        assertThat(storedEnvelope).contains("\"envelope_digest\":\"" + envelope.envelopeDigest() + "\"");
        assertThat(completion.get("envelope_size_bytes"))
                .isEqualTo(storedEnvelope.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        assertThat(completion.get("envelope_digest")).isEqualTo(envelope.envelopeDigest());
        assertPersistedRowDigests(fixture, receipt, envelope);
    }

    @Test
    void shouldDurablyStageTheFirstQuorumCandidateWithoutMutatingTheCanonicalCell() {
        Fixture fixture = quorumFixture();
        ResearchAgentCompletionEnvelope envelope = envelope(fixture, true);

        ResearchAgentCompletionReceipt receipt = completionService.complete(fixture.taskId(), envelope);

        assertThat(receipt.outcome()).isEqualTo("QUORUM_PENDING");
        assertThat(receipt.acceptedMerges()).isEmpty();
        assertThat(receipt.rejectedMerges()).isEmpty();
        assertThat(count("research_agent_execution", "research_agent_task_id", fixture.taskId())).isEqualTo(1);
        assertThat(count("research_agent_completion", "research_agent_task_id", fixture.taskId())).isEqualTo(1);
        assertThat(count("research_agent_candidate", "agent_completion_id", receipt.completionId())).isEqualTo(1);
        assertThat(count("research_cell_merge", "agent_completion_id", receipt.completionId())).isZero();
        assertThat(jdbcTemplate.queryForMap("""
                select cell_status, cell_version, candidate_value, active_task_id
                from research_cell where research_run_id = ? and cell_key = ?
                """, fixture.runId(), fixture.cellKeys().get(0)))
                .containsEntry("cell_status", "CANDIDATE_READY")
                .containsEntry("cell_version", 0)
                .containsEntry("candidate_value", "old-0")
                .containsEntry("active_task_id", "quorum:" + fixture.runId());
        assertThat(jdbcTemplate.queryForObject(
                "select status from research_agent_task where id = ?", String.class, fixture.taskId()))
                .isEqualTo("SUBMITTED");
        assertThat(jdbcTemplate.queryForObject(
                "select state from research_budget_reservation where research_agent_task_id = ?",
                String.class, fixture.taskId())).isEqualTo("SETTLED");
    }

    @Test
    void shouldBlindMergeTwoIndependentSameValueCandidateSlotsExactlyOnce() {
        QuorumPair pair = quorumPair();

        ResearchAgentCompletionReceipt first = completionService.complete(
                pair.first().taskId(), envelopeForSource(pair.first(), "worker-a", "source-0", "Source 0", "trusted quote 0"));
        ResearchAgentCompletionReceipt second = completionService.complete(
                pair.second().taskId(), envelopeForSource(pair.second(), "worker-b", "source-1", "Source 1", "trusted quote 1"));

        assertThat(first.outcome()).isEqualTo("QUORUM_PENDING");
        assertThat(second.outcome()).isEqualTo("QUORUM_MERGED");
        assertThat(second.acceptedMerges()).containsExactly(new ResearchAgentCompletionReceipt.MergeReceipt(
                pair.first().cellKeys().get(0), 0, 1, "ACCEPTED", "QUORUM_VERIFIED_AND_VERSION_MATCHED"));
        assertThat(jdbcTemplate.queryForMap("""
                select cell_status, cell_version, candidate_value, active_task_id
                from research_cell where research_run_id = ? and cell_key = ?
                """, pair.first().runId(), pair.first().cellKeys().get(0)))
                .containsEntry("cell_status", "VERIFIED")
                .containsEntry("cell_version", 1)
                .containsEntry("candidate_value", "value-0")
                .containsEntry("active_task_id", null);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from research_cell_merge where research_run_id = ? and decision = 'ACCEPTED'",
                Integer.class, pair.first().runId())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select count(distinct research_agent_execution_id) from research_agent_candidate where quorum_group_key = ?",
                Integer.class, "quorum:" + pair.first().runId())).isEqualTo(2);
    }

    @Test
    void shouldFailClosedAndEmitARepairSignalForSameDomainPseudoQuorum() {
        QuorumPair pair = quorumPair("source-0", "Source 0", "prefix trusted quote 0 suffix");
        completionService.complete(
                pair.first().taskId(), envelopeForSource(pair.first(), "worker-a", "source-0", "Source 0", "trusted quote 0"));

        ResearchAgentCompletionReceipt receipt = completionService.complete(
                pair.second().taskId(), envelopeForSource(pair.second(), "worker-b", "source-0", "Source 0", "trusted quote 0"));

        assertThat(receipt.outcome()).isEqualTo("QUORUM_REPAIR_REQUIRED");
        assertThat(receipt.rejectedMerges()).containsExactly(new ResearchAgentCompletionReceipt.MergeReceipt(
                pair.first().cellKeys().get(0), 0, 0, "REJECTED", "QUORUM_SOURCE_DOMAIN_NOT_INDEPENDENT"));
        assertThat(jdbcTemplate.queryForMap("""
                select cell_version, candidate_value, active_task_id
                from research_cell where research_run_id = ? and cell_key = ?
                """, pair.first().runId(), pair.first().cellKeys().get(0)))
                .containsEntry("cell_version", 0)
                .containsEntry("candidate_value", "old-0")
                .containsEntry("active_task_id", null);
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from research_verifier_decision
                where research_run_id = ? and decision_type = 'QUORUM_REPAIR_REQUIRED'
                  and reason_code = 'QUORUM_SOURCE_DOMAIN_NOT_INDEPENDENT'
                """, Integer.class, pair.first().runId())).isEqualTo(1);
    }

    @Test
    void shouldFailClosedAndEmitARepairSignalWhenIndependentCandidatesDisagree() {
        QuorumPair pair = quorumPair();
        completionService.complete(pair.first().taskId(), envelopeForSource(
                pair.first(), "worker-a", "source-0", "Source 0", "trusted quote 0"));

        ResearchAgentCompletionReceipt receipt = completionService.complete(pair.second().taskId(),
                envelopeForSourceAndValue(pair.second(), "worker-b", "source-1", "Source 1",
                        "trusted quote 1", "conflicting-value"));

        assertThat(receipt.outcome()).isEqualTo("QUORUM_REPAIR_REQUIRED");
        assertThat(receipt.rejectedMerges()).containsExactly(new ResearchAgentCompletionReceipt.MergeReceipt(
                pair.first().cellKeys().get(0), 0, 0, "REJECTED", "QUORUM_VALUE_CONFLICT"));
        assertThat(jdbcTemplate.queryForMap("""
                select cell_version, candidate_value, active_task_id
                from research_cell where research_run_id = ? and cell_key = ?
                """, pair.first().runId(), pair.first().cellKeys().get(0)))
                .containsEntry("cell_version", 0)
                .containsEntry("candidate_value", "old-0")
                .containsEntry("active_task_id", null);
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from research_verifier_decision
                where research_run_id = ? and decision_type = 'QUORUM_REPAIR_REQUIRED'
                  and reason_code = 'QUORUM_VALUE_CONFLICT' and action_text = 'COUNTERFACTUAL_REPAIR'
                """, Integer.class, pair.first().runId())).isEqualTo(1);
    }

    @Test
    void shouldRollbackSecondQuorumCompletionAfterCellCasAndSucceedOnRetry() {
        QuorumPair pair = quorumPair();
        completionService.complete(pair.first().taskId(), envelopeForSource(
                pair.first(), "worker-a", "source-0", "Source 0", "trusted quote 0"));
        ResearchAgentCompletionEnvelope secondEnvelope = envelopeForSource(
                pair.second(), "worker-b", "source-1", "Source 1", "trusted quote 1");
        doAnswer(invocation -> {
            if (invocation.getArgument(0) == ResearchAgentCompletionFaultInjector.Stage.AFTER_FIRST_CELL_CAS) {
                throw new IllegalStateException("ma5q injected after quorum CAS");
            }
            return null;
        }).when(faultInjector).checkpoint(any(), anyInt());

        assertThatThrownBy(() -> completionService.complete(pair.second().taskId(), secondEnvelope))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("quorum CAS");

        assertThat(jdbcTemplate.queryForMap("""
                select cell_version, candidate_value, active_task_id
                from research_cell where research_run_id = ? and cell_key = ?
                """, pair.first().runId(), pair.first().cellKeys().get(0)))
                .containsEntry("cell_version", 0)
                .containsEntry("candidate_value", "old-0")
                .containsEntry("active_task_id", "quorum:" + pair.first().runId());
        assertThat(count("research_agent_completion", "research_agent_task_id", pair.second().taskId())).isZero();
        assertThat(count("research_agent_candidate", "task_id", pair.second().taskId())).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select state from research_budget_reservation where research_agent_task_id = ?",
                String.class, pair.second().taskId())).isEqualTo("RESERVED");
        assertThat(jdbcTemplate.queryForObject(
                "select status from research_agent_task where id = ?",
                String.class, pair.second().taskId())).isEqualTo("CLAIMED");

        reset(faultInjector);
        ResearchAgentCompletionReceipt retry = completionService.complete(pair.second().taskId(), secondEnvelope);
        assertThat(retry.outcome()).isEqualTo("QUORUM_MERGED");
        assertThat(jdbcTemplate.queryForObject(
                "select cell_version from research_cell where research_run_id = ? and cell_key = ?",
                Integer.class, pair.first().runId(), pair.first().cellKeys().get(0))).isEqualTo(1);
    }

    @Test
    void shouldAtomicallyCommitAnAuthoritativeCounterfactualRepairTask() {
        Fixture fixture = counterfactualRepairFixture();
        String decisionId = Ids.newId();
        jdbcTemplate.update("""
                insert into research_verifier_decision(
                    id, research_run_id, branch_id, decision_scope, decision_type, reason_code,
                    target_id, evidence_ids_json, action_text, decision_status, notes_json)
                values (?, ?, 'branch-quorum-2', 'CELL', 'QUORUM_REPAIR_REQUIRED',
                        'QUORUM_VALUE_CONFLICT', ?, '[]', 'COUNTERFACTUAL_REPAIR', 'OPEN', '{}')
                """, decisionId, fixture.runId(), fixture.cellKeys().get(0));

        ResearchAgentCompletionReceipt receipt = completionService.complete(
                fixture.taskId(), envelope(fixture, true));

        assertThat(receipt.outcome()).isEqualTo("COMMITTED");
        assertThat(receipt.acceptedMerges()).singleElement().satisfies(merge -> {
            assertThat(merge.cellKey()).isEqualTo(fixture.cellKeys().get(0));
            assertThat(merge.toVersion()).isEqualTo(1);
        });
        assertThat(jdbcTemplate.queryForMap("""
                select cell_status, cell_version, candidate_value, active_task_id
                from research_cell where research_run_id = ? and cell_key = ?
                """, fixture.runId(), fixture.cellKeys().get(0)))
                .containsEntry("cell_status", "VERIFIED")
                .containsEntry("cell_version", 1)
                .containsEntry("candidate_value", "value-0")
                .containsEntry("active_task_id", null);
        assertThat(jdbcTemplate.queryForObject(
                "select decision_status from research_verifier_decision where id = ?",
                String.class, decisionId)).isEqualTo("RESOLVED");
    }

    @Test
    void shouldMergeInEitherSlotOrderWhileKeepingTheEarlyPendingReceiptImmutable() {
        QuorumPair pair = quorumPair();
        ResearchAgentCompletionEnvelope slotTwo = envelopeForSource(
                pair.second(), "worker-b", "source-1", "Source 1", "trusted quote 1");
        ResearchAgentCompletionEnvelope slotOne = envelopeForSource(
                pair.first(), "worker-a", "source-0", "Source 0", "trusted quote 0");

        ResearchAgentCompletionReceipt early = completionService.complete(pair.second().taskId(), slotTwo);
        ResearchAgentCompletionReceipt merged = completionService.complete(pair.first().taskId(), slotOne);
        ResearchAgentCompletionReceipt replay = completionService.complete(pair.second().taskId(), slotTwo);

        assertThat(early.outcome()).isEqualTo("QUORUM_PENDING");
        assertThat(merged.outcome()).isEqualTo("QUORUM_MERGED");
        assertThat(replay.idempotentReplay()).isTrue();
        assertThat(replay.outcome()).isEqualTo("QUORUM_PENDING");
        assertThat(replay.completionId()).isEqualTo(early.completionId());
        assertThat(replay.receiptDigest()).isEqualTo(early.receiptDigest());
        assertThat(jdbcTemplate.queryForObject(
                "select cell_version from research_cell where research_run_id = ? and cell_key = ?",
                Integer.class, pair.first().runId(), pair.first().cellKeys().get(0))).isEqualTo(1);
    }

    @Test
    void shouldCommitEvidenceOnlyWithoutChangingCellAndReleaseBinding() {
        Fixture fixture = fixture(1);
        ResearchAgentCompletionEnvelope full = envelope(fixture, false);
        ResearchAgentCompletionEnvelope evidenceOnly = signed(new ResearchAgentCompletionEnvelope(
                full.schemaVersion(), full.taskId(), full.workerInstanceId(), full.leaseEpoch(), full.fencingToken(),
                full.executionKey(), full.taskSnapshotDigest(), "EVIDENCE_ONLY",
                Map.of("llm_calls", 0L, "search_calls", 1L, "fetch_calls", 1L, "read_calls", 1L, "extract_calls", 1L,
                        "evidence_cards", 1L, "candidates_submitted", 0L),
                full.telemetry(), full.traceDigest(), full.evidence(), List.of(), null));

        ResearchAgentCompletionReceipt receipt = completionService.complete(fixture.taskId(), evidenceOnly);

        assertThat(receipt.acceptedMerges()).isEmpty();
        assertThat(receipt.rejectedMerges()).isEmpty();
        assertThat(jdbcTemplate.queryForMap(
                "select cell_version, candidate_value, active_task_id from research_cell where research_run_id = ? and cell_key = ?",
                fixture.runId(), fixture.cellKeys().get(0)))
                .containsEntry("cell_version", 0)
                .containsEntry("candidate_value", "old-0")
                .containsEntry("active_task_id", null);
    }

    @Test
    void shouldPersistRejectedCandidateWithoutCellMutationOrCellEvidence() {
        Fixture fixture = fixture(1);
        ResearchAgentCompletionEnvelope full = envelope(fixture, true);
        ResearchAgentCompletionEnvelope.Evidence source = full.evidence().get(0);
        ResearchAgentCompletionEnvelope.Evidence weak = new ResearchAgentCompletionEnvelope.Evidence(
                source.evidenceKey(), source.windowId(), source.sourceId(), source.sourceTitle(),
                source.searchQuery(), source.readFocus(), source.quoteText(), source.claimText(),
                "WEAK_SUPPORT", source.supportScorePpm(), source.conflictScorePpm(), source.snapshotStatus());
        ResearchAgentCompletionEnvelope rejected = signed(new ResearchAgentCompletionEnvelope(
                full.schemaVersion(), full.taskId(), full.workerInstanceId(), full.leaseEpoch(), full.fencingToken(),
                full.executionKey(), full.taskSnapshotDigest(), full.terminationReason(), full.budgetUsage(),
                full.telemetry(), full.traceDigest(), List.of(weak), full.candidates(), null));

        ResearchAgentCompletionReceipt receipt = completionService.complete(fixture.taskId(), rejected);

        assertThat(receipt.acceptedMerges()).isEmpty();
        assertThat(receipt.rejectedMerges()).containsExactly(new ResearchAgentCompletionReceipt.MergeReceipt(
                fixture.cellKeys().get(0), 0, 0, "REJECTED", "NOT_ENOUGH_INFO"));
        assertThat(jdbcTemplate.queryForMap(
                "select cell_version, candidate_value, active_task_id from research_cell where research_run_id = ? and cell_key = ?",
                fixture.runId(), fixture.cellKeys().get(0)))
                .containsEntry("cell_version", 0)
                .containsEntry("candidate_value", "old-0")
                .containsEntry("active_task_id", null);
        assertThat(count("research_cell_evidence", "research_run_id", fixture.runId())).isZero();
        assertThat(receipt.budget().consumed()).containsEntry("candidate_merges_accepted", 0L)
                .containsEntry("candidate_merges_rejected", 1L);
    }

    @Test
    void shouldCommitNoSupportedCandidateWithEmptyChildrenAndReleaseBinding() {
        Fixture fixture = fixture(1);
        ResearchAgentCompletionEnvelope full = envelope(fixture, true);
        ResearchAgentCompletionEnvelope noResult = signed(new ResearchAgentCompletionEnvelope(
                full.schemaVersion(), full.taskId(), full.workerInstanceId(), full.leaseEpoch(), full.fencingToken(),
                full.executionKey(), full.taskSnapshotDigest(), "NO_SUPPORTED_CANDIDATE",
                Map.of("llm_calls", 0L, "search_calls", 0L, "fetch_calls", 0L, "read_calls", 0L,
                        "extract_calls", 0L, "evidence_cards", 0L, "candidates_submitted", 0L),
                Map.of("search_hits", 0L, "documents", 0L, "windows", 0L),
                full.traceDigest(), List.of(), List.of(), null));

        ResearchAgentCompletionReceipt receipt = completionService.complete(fixture.taskId(), noResult);

        assertThat(receipt.evidenceAppended()).isZero();
        assertThat(receipt.candidateCount()).isZero();
        assertThat(receipt.acceptedMerges()).isEmpty();
        assertThat(receipt.rejectedMerges()).isEmpty();
        assertThat(jdbcTemplate.queryForMap(
                "select cell_version, candidate_value, active_task_id from research_cell where research_run_id = ? and cell_key = ?",
                fixture.runId(), fixture.cellKeys().get(0)))
                .containsEntry("cell_version", 0)
                .containsEntry("candidate_value", "old-0")
                .containsEntry("active_task_id", null);
        assertThat(receipt.budget().consumed().values()).allMatch(value -> value == 0L);
    }

    @Test
    void shouldPublishCommitReplayConflictLatencyPayloadCellsAndReleasedBudgetMetrics() {
        double commits = counterValue("research.agent.completion.commit.total");
        double replays = counterValue("research.agent.completion.replay.total");
        double conflicts = counterValue("research.agent.completion.conflict.total", "type",
                "research_agent_completion_idempotency_conflict");
        double released = counterValue("research.agent.budget.released", "dimension", "llm_calls");
        long latency = timerCount("research.agent.completion.latency");
        long payloads = summaryCount("research.agent.completion.payload.bytes");
        long cells = summaryCount("research.agent.completion.cells");
        Fixture fixture = fixture(1);
        ResearchAgentCompletionEnvelope envelope = envelope(fixture, true);

        ResearchAgentCompletionReceipt first = completionService.complete(fixture.taskId(), envelope);
        completionService.complete(fixture.taskId(), envelope);
        ResearchAgentCompletionEnvelope changed = signed(new ResearchAgentCompletionEnvelope(
                envelope.schemaVersion(), envelope.taskId(), envelope.workerInstanceId(), envelope.leaseEpoch(),
                envelope.fencingToken(), envelope.executionKey(), envelope.taskSnapshotDigest(),
                envelope.terminationReason(), envelope.budgetUsage(), envelope.telemetry(),
                "sha256:" + "4".repeat(64), envelope.evidence(), envelope.candidates(), null));
        assertCode(() -> completionService.complete(fixture.taskId(), changed),
                "RESEARCH_AGENT_COMPLETION_IDEMPOTENCY_CONFLICT");

        assertThat(counterValue("research.agent.completion.commit.total") - commits).isEqualTo(1.0);
        assertThat(counterValue("research.agent.completion.replay.total") - replays).isEqualTo(1.0);
        assertThat(counterValue("research.agent.completion.conflict.total", "type",
                "research_agent_completion_idempotency_conflict") - conflicts).isEqualTo(1.0);
        assertThat(counterValue("research.agent.budget.released", "dimension", "llm_calls") - released)
                .isEqualTo(first.budget().released().get("llm_calls").doubleValue());
        assertThat(timerCount("research.agent.completion.latency") - latency).isEqualTo(3);
        assertThat(summaryCount("research.agent.completion.payload.bytes") - payloads).isEqualTo(3);
        assertThat(summaryCount("research.agent.completion.cells") - cells).isEqualTo(3);
    }

    @Test
    void shouldReturnExactReceiptAfterLeaseExpiryAndRunTerminalWithoutSecondWrite() {
        Fixture fixture = fixture(1);
        ResearchAgentCompletionEnvelope envelope = envelope(fixture, true);
        ResearchAgentCompletionReceipt first = completionService.complete(fixture.taskId(), envelope);
        jdbcTemplate.update("update research_run set status = 'FAILED' where id = ?", fixture.runId());
        jdbcTemplate.update("update research_agent_task set lease_expires_at = current_timestamp where id = ?", fixture.taskId());
        String beforeReplay = state(fixture);

        ResearchAgentCompletionReceipt replay = completionService.complete(fixture.taskId(), envelope);

        assertThat(replay.idempotentReplay()).isTrue();
        assertThat(replay.completionId()).isEqualTo(first.completionId());
        assertThat(replay.executionId()).isEqualTo(first.executionId());
        assertThat(replay.completionDigest()).isEqualTo(first.completionDigest());
        assertThat(replay.receiptDigest()).isEqualTo(first.receiptDigest());
        assertThat(state(fixture)).isEqualTo(beforeReplay);
    }

    @ParameterizedTest
    @ValueSource(strings = {"merge_unknown_field", "budget_missing_dimension", "budget_not_conserved",
            "receipt_consistent_downgrade", "rejected_version_increment"})
    @SuppressWarnings("unchecked")
    void shouldFailClosedForNestedReceiptCorruptionEvenWithMatchingRecomputedDigest(String corruption) throws Exception {
        Fixture fixture = fixture(1);
        ResearchAgentCompletionEnvelope envelope = envelope(fixture, true);
        ResearchAgentCompletionReceipt receipt = completionService.complete(fixture.taskId(), envelope);
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        Map<String, Object> payload = mapper.readValue(
                jdbcTemplate.queryForObject(
                        "select receipt_json from research_agent_completion where id = ?",
                        String.class, receipt.completionId()),
                new com.fasterxml.jackson.core.type.TypeReference<java.util.LinkedHashMap<String, Object>>() { });
        payload.remove("receipt_digest");

        Map<String, Object> budget = (Map<String, Object>) payload.get("budget");
        switch (corruption) {
            case "merge_unknown_field" -> {
                List<Map<String, Object>> accepted = (List<Map<String, Object>>) payload.get("accepted_merges");
                accepted.get(0).put("future_field", "must-not-be-ignored");
            }
            case "budget_missing_dimension" ->
                    ((Map<String, Object>) budget.get("reserved")).remove("llm_calls");
            case "budget_not_conserved" ->
                    ((Map<String, Object>) budget.get("consumed")).put("llm_calls", 1);
            case "receipt_consistent_downgrade" -> {
                payload.put("evidence_appended", 0);
                payload.put("candidate_count", 0);
                ((List<?>) payload.get("accepted_merges")).clear();
                ((List<?>) payload.get("rejected_merges")).clear();
                Map<String, Object> reserved = (Map<String, Object>) budget.get("reserved");
                Map<String, Object> consumed = (Map<String, Object>) budget.get("consumed");
                Map<String, Object> released = (Map<String, Object>) budget.get("released");
                for (String key : List.of("evidence_cards", "candidates_submitted", "evidence_appended",
                        "candidate_merges_accepted", "candidate_merges_rejected")) {
                    consumed.put(key, 0);
                    released.put(key, reserved.get(key));
                }
            }
            case "rejected_version_increment" -> {
                List<Map<String, Object>> accepted = (List<Map<String, Object>>) payload.get("accepted_merges");
                Map<String, Object> merge = accepted.remove(0);
                merge.put("decision", "REJECTED");
                merge.put("reason_code", "NOT_ENOUGH_INFO");
                ((List<Map<String, Object>>) payload.get("rejected_merges")).add(merge);
                Map<String, Object> reserved = (Map<String, Object>) budget.get("reserved");
                Map<String, Object> consumed = (Map<String, Object>) budget.get("consumed");
                Map<String, Object> released = (Map<String, Object>) budget.get("released");
                consumed.put("candidate_merges_accepted", 0);
                released.put("candidate_merges_accepted", reserved.get("candidate_merges_accepted"));
                consumed.put("candidate_merges_rejected", 1);
                released.put("candidate_merges_rejected",
                        ((Number) reserved.get("candidate_merges_rejected")).longValue() - 1);
            }
            default -> throw new AssertionError("unknown corruption fixture: " + corruption);
        }

        String recomputed = canonicalizer.domainSeparatedDigest(
                "research-agent-completion-receipt.v1", payload);
        payload.put("receipt_digest", recomputed);
        String corruptedCanonicalJson = canonicalizer.canonicalJsonValue(payload);
        jdbcTemplate.update("""
                update research_agent_completion
                set receipt_json = ?, receipt_digest = ?
                where id = ?
                """, corruptedCanonicalJson, recomputed, receipt.completionId());

        assertThatThrownBy(() -> completionService.complete(fixture.taskId(), envelope))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Committed research-agent receipt");
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldRejectReplayWhenReceiptBudgetDriftsFromPhysicalReservationButRemainsInternallyConserved() throws Exception {
        Fixture fixture = fixture(1);
        ResearchAgentCompletionEnvelope envelope = envelope(fixture, true);
        ResearchAgentCompletionReceipt receipt = completionService.complete(fixture.taskId(), envelope);
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        Map<String, Object> payload = mapper.readValue(
                jdbcTemplate.queryForObject(
                        "select receipt_json from research_agent_completion where id = ?",
                        String.class, receipt.completionId()),
                new com.fasterxml.jackson.core.type.TypeReference<java.util.LinkedHashMap<String, Object>>() { });
        payload.remove("receipt_digest");
        Map<String, Object> budget = (Map<String, Object>) payload.get("budget");
        Map<String, Object> reserved = (Map<String, Object>) budget.get("reserved");
        Map<String, Object> released = (Map<String, Object>) budget.get("released");
        reserved.put("llm_calls", ((Number) reserved.get("llm_calls")).longValue() + 1);
        released.put("llm_calls", ((Number) released.get("llm_calls")).longValue() + 1);
        String forgedDigest = canonicalizer.domainSeparatedDigest(
                "research-agent-completion-receipt.v1", payload);
        payload.put("receipt_digest", forgedDigest);
        jdbcTemplate.update("""
                update research_agent_completion set receipt_json = ?, receipt_digest = ? where id = ?
                """, canonicalizer.canonicalJsonValue(payload), forgedDigest, receipt.completionId());
        String beforeReplay = state(fixture);

        assertThatThrownBy(() -> completionService.complete(fixture.taskId(), envelope))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("budget reservation");
        assertThat(state(fixture)).isEqualTo(beforeReplay);
    }

    @Test
    void shouldRejectReplayWhenPhysicalReservationDriftsFromReceiptButRemainsConserved() throws Exception {
        Fixture fixture = fixture(1);
        ResearchAgentCompletionEnvelope envelope = envelope(fixture, true);
        completionService.complete(fixture.taskId(), envelope);
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        Map<String, Object> reservation = jdbcTemplate.queryForMap("""
                select reserved_json, released_json from research_budget_reservation
                where research_agent_task_id = ?
                """, fixture.taskId());
        Map<String, Object> reserved = mapper.readValue(
                String.valueOf(reservation.get("reserved_json")),
                new com.fasterxml.jackson.core.type.TypeReference<java.util.LinkedHashMap<String, Object>>() { });
        Map<String, Object> released = mapper.readValue(
                String.valueOf(reservation.get("released_json")),
                new com.fasterxml.jackson.core.type.TypeReference<java.util.LinkedHashMap<String, Object>>() { });
        reserved.put("llm_calls", ((Number) reserved.get("llm_calls")).longValue() + 1);
        released.put("llm_calls", ((Number) released.get("llm_calls")).longValue() + 1);
        jdbcTemplate.update("""
                update research_budget_reservation set reserved_json = ?, released_json = ?
                where research_agent_task_id = ?
                """, canonicalizer.canonicalJsonValue(reserved), canonicalizer.canonicalJsonValue(released),
                fixture.taskId());
        String beforeReplay = state(fixture);

        assertThatThrownBy(() -> completionService.complete(fixture.taskId(), envelope))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("budget reservation");
        assertThat(state(fixture)).isEqualTo(beforeReplay);
    }

    @Test
    void shouldRejectSameExecutionKeyWithChangedFullContentAndDifferentExecutionKeyAfterCommit() {
        Fixture fixture = fixture(1);
        ResearchAgentCompletionEnvelope original = envelope(fixture, true);
        completionService.complete(fixture.taskId(), original);
        ResearchAgentCompletionEnvelope.Evidence evidence = original.evidence().get(0);
        ResearchAgentCompletionEnvelope changed = signed(new ResearchAgentCompletionEnvelope(
                original.schemaVersion(), original.taskId(), original.workerInstanceId(), original.leaseEpoch(),
                original.fencingToken(), original.executionKey(), original.taskSnapshotDigest(), original.terminationReason(),
                original.budgetUsage(), original.telemetry(), original.traceDigest(),
                List.of(new ResearchAgentCompletionEnvelope.Evidence(
                        evidence.evidenceKey(), evidence.windowId(), evidence.sourceId(), evidence.sourceTitle(),
                        evidence.searchQuery(), evidence.readFocus(), evidence.quoteText(), "changed value", evidence.relationType(),
                        evidence.supportScorePpm(), evidence.conflictScorePpm(), evidence.snapshotStatus())),
                List.of(new ResearchAgentCompletionEnvelope.Candidate(
                        original.candidates().get(0).candidateKey(), original.candidates().get(0).cellKey(), 0,
                        "changed value", original.candidates().get(0).evidenceKeys(), 900_000)), null));
        ResearchAgentCompletionEnvelope otherExecution = signed(new ResearchAgentCompletionEnvelope(
                original.schemaVersion(), original.taskId(), original.workerInstanceId(), original.leaseEpoch(),
                original.fencingToken(), original.executionKey() + ":other", original.taskSnapshotDigest(),
                original.terminationReason(), original.budgetUsage(), original.telemetry(), original.traceDigest(),
                original.evidence(), original.candidates(), null));

        assertCode(() -> completionService.complete(fixture.taskId(), changed),
                "RESEARCH_AGENT_COMPLETION_IDEMPOTENCY_CONFLICT");
        assertCode(() -> completionService.complete(fixture.taskId(), otherExecution),
                "RESEARCH_AGENT_COMPLETION_ALREADY_COMMITTED");
    }

    @Test
    void shouldFailClosedForStaleLeaseSnapshotModeAndBudgetWithZeroWrites() {
        Fixture staleLease = fixture(1);
        ResearchAgentCompletionEnvelope wrongWorker = replaceIdentity(envelope(staleLease, true), "other-worker", null);
        String beforeLease = state(staleLease);
        assertCode(() -> completionService.complete(staleLease.taskId(), wrongWorker),
                "RESEARCH_AGENT_TASK_STALE_LEASE");
        assertThat(state(staleLease)).isEqualTo(beforeLease);

        Fixture staleSnapshot = fixture(1);
        ResearchAgentCompletionEnvelope wrongSnapshot = replaceIdentity(envelope(staleSnapshot, true), null,
                "sha256:" + "9".repeat(64));
        String beforeSnapshot = state(staleSnapshot);
        assertCode(() -> completionService.complete(staleSnapshot.taskId(), wrongSnapshot),
                "RESEARCH_AGENT_TASK_SNAPSHOT_STALE");
        assertThat(state(staleSnapshot)).isEqualTo(beforeSnapshot);

        Fixture wrongMode = fixture(1);
        jdbcTemplate.update("update research_run set agent_execution_mode = 'SEQUENTIAL_V1' where id = ?", wrongMode.runId());
        String beforeMode = state(wrongMode);
        assertCode(() -> completionService.complete(wrongMode.taskId(), envelope(wrongMode, true)),
                "RESEARCH_AGENT_RUN_MODE_INVALID");
        assertThat(state(wrongMode)).isEqualTo(beforeMode);

        Fixture exceeded = fixture(1);
        ResearchAgentCompletionEnvelope regular = envelope(exceeded, true);
        Map<String, Long> over = new java.util.LinkedHashMap<>(regular.budgetUsage());
        over.put("search_calls", 99L);
        ResearchAgentCompletionEnvelope overBudget = signed(new ResearchAgentCompletionEnvelope(
                regular.schemaVersion(), regular.taskId(), regular.workerInstanceId(), regular.leaseEpoch(), regular.fencingToken(),
                regular.executionKey(), regular.taskSnapshotDigest(), regular.terminationReason(), over, regular.telemetry(),
                regular.traceDigest(), regular.evidence(), regular.candidates(), null));
        String beforeBudget = state(exceeded);
        assertCode(() -> completionService.complete(exceeded.taskId(), overBudget),
                "RESEARCH_AGENT_COMPLETION_BUDGET_EXCEEDED");
        assertThat(state(exceeded)).isEqualTo(beforeBudget);
    }

    @Test
    void shouldRejectEveryLeaseRunAndCellSnapshotDriftWithZeroWrites() {
        Fixture epoch = fixture(1);
        assertZeroState(epoch, replaceLease(envelope(epoch, true), epoch.leaseEpoch() + 1, epoch.fencingToken()),
                "RESEARCH_AGENT_TASK_STALE_LEASE");
        Fixture fence = fixture(1);
        assertZeroState(fence, replaceLease(envelope(fence, true), fence.leaseEpoch(), fence.fencingToken() + 1),
                "RESEARCH_AGENT_TASK_STALE_LEASE");

        Fixture expiry = fixture(1);
        jdbcTemplate.update("""
                update research_agent_task
                set lease_expires_at = timestampadd(second, -1, current_timestamp)
                where id = ?
                """, expiry.taskId());
        assertZeroState(expiry, envelope(expiry, true), "RESEARCH_AGENT_TASK_STALE_LEASE");

        Fixture taskState = fixture(1);
        jdbcTemplate.update("update research_agent_task set status = 'RETRY_WAIT' where id = ?", taskState.taskId());
        assertZeroState(taskState, envelope(taskState, true), "RESEARCH_AGENT_TASK_STALE_LEASE");

        Fixture terminalRun = fixture(1);
        jdbcTemplate.update("update research_run set status = 'FAILED' where id = ?", terminalRun.runId());
        assertZeroState(terminalRun, envelope(terminalRun, true), "RESEARCH_AGENT_RUN_TERMINAL");

        Fixture plan = fixture(1);
        jdbcTemplate.update("update research_cell set plan_revision = plan_revision + 1 where research_run_id = ?",
                plan.runId());
        assertZeroState(plan, envelope(plan, true), "RESEARCH_AGENT_TASK_SNAPSHOT_STALE");

        Fixture entitySet = fixture(1);
        jdbcTemplate.update("update research_cell set entity_set_version = entity_set_version + 1 where research_run_id = ?",
                entitySet.runId());
        assertZeroState(entitySet, envelope(entitySet, true), "RESEARCH_AGENT_TASK_SNAPSHOT_STALE");

        Fixture version = fixture(1);
        jdbcTemplate.update("update research_cell set cell_version = cell_version + 1 where research_run_id = ?",
                version.runId());
        assertZeroState(version, envelope(version, true), "RESEARCH_AGENT_TASK_SNAPSHOT_STALE");
    }

    @Test
    void shouldRollbackEveryTableWhenFailureOccursAfterFirstCellCas() {
        Fixture fixture = fixture(2);
        String before = state(fixture);
        AtomicInteger hookCount = new AtomicInteger();
        doAnswer(invocation -> {
            if (invocation.getArgument(0) == ResearchAgentCompletionFaultInjector.Stage.AFTER_FIRST_CELL_CAS) {
                assertThat(invocation.getArgument(1, Integer.class)).isEqualTo(1);
                hookCount.incrementAndGet();
                throw new IllegalStateException("ma4g injected after first CAS");
            }
            return null;
        }).when(faultInjector).checkpoint(any(), anyInt());

        assertThatThrownBy(() -> completionService.complete(fixture.taskId(), envelope(fixture, true)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("first CAS");

        assertThat(hookCount).hasValue(1);
        assertThat(state(fixture)).isEqualTo(before);
    }

    @Test
    void shouldKeepCompletionSettlementAcrossRunCancelAndReplayReceipt() {
        Fixture fixture = fixture(1);
        ResearchAgentCompletionEnvelope envelope = envelope(fixture, true);
        ResearchAgentCompletionReceipt first = completionService.complete(fixture.taskId(), envelope);

        lifecycleService.cancelRun(fixture.runId(), "USER_CANCELLED_AFTER_COMPLETION");

        assertThat(jdbcTemplate.queryForObject(
                "select state from research_budget_reservation where research_agent_task_id = ?",
                String.class, fixture.taskId())).isEqualTo("SETTLED");
        assertThat(jdbcTemplate.queryForObject(
                "select agent_completion_id from research_budget_reservation where research_agent_task_id = ?",
                String.class, fixture.taskId())).isEqualTo(first.completionId());
        String reservationId = jdbcTemplate.queryForObject(
                "select id from research_budget_reservation where research_agent_task_id = ?",
                String.class, fixture.taskId());
        assertCode(() -> budgetService.release(reservationId),
                "RESEARCH_AGENT_ATOMIC_COMPLETION_REQUIRED");
        assertThat(jdbcTemplate.queryForObject(
                "select state from research_budget_reservation where id = ?", String.class, reservationId))
                .isEqualTo("SETTLED");
        assertThat(jdbcTemplate.queryForObject(
                "select status from research_agent_task where id = ?", String.class, fixture.taskId()))
                .isEqualTo("SUBMITTED");
        ResearchAgentCompletionReceipt replay = completionService.complete(fixture.taskId(), envelope);
        assertThat(replay.idempotentReplay()).isTrue();
        assertThat(replay.receiptDigest()).isEqualTo(first.receiptDigest());
    }

    @Test
    void shouldRejectUnknownBudgetDimensionEvenWhenZeroAndSnapshotBudgetDrift() {
        Fixture unknown = fixture(1);
        ResearchAgentCompletionEnvelope regular = envelope(unknown, true);
        Map<String, Long> badUsage = new java.util.LinkedHashMap<>(regular.budgetUsage());
        badUsage.put("future_calls", 0L);
        ResearchAgentCompletionEnvelope unknownUsage = signed(new ResearchAgentCompletionEnvelope(
                regular.schemaVersion(), regular.taskId(), regular.workerInstanceId(), regular.leaseEpoch(),
                regular.fencingToken(), regular.executionKey(), regular.taskSnapshotDigest(), regular.terminationReason(),
                badUsage, regular.telemetry(), regular.traceDigest(), regular.evidence(), regular.candidates(), null));
        assertCode(() -> completionService.complete(unknown.taskId(), unknownUsage),
                "RESEARCH_AGENT_COMPLETION_INVALID");

        Fixture drift = fixture(1);
        jdbcTemplate.update("""
                update research_budget_reservation
                set reserved_json = replace(reserved_json, '\"search_calls\":2', '\"search_calls\":3')
                where research_agent_task_id = ?
                """, drift.taskId());
        String before = state(drift);
        assertCode(() -> completionService.complete(drift.taskId(), envelope(drift, true)),
                "RESEARCH_AGENT_TASK_SNAPSHOT_STALE");
        assertThat(state(drift)).isEqualTo(before);
    }

    @Test
    void shouldPersistExactPpmAndRejectSpoofedServerSourceTitle() {
        Fixture exact = fixture(1);
        ResearchAgentCompletionEnvelope regular = envelope(exact, true);
        ResearchAgentCompletionEnvelope.Evidence source = regular.evidence().get(0);
        ResearchAgentCompletionEnvelope exactPpm = signed(new ResearchAgentCompletionEnvelope(
                regular.schemaVersion(), regular.taskId(), regular.workerInstanceId(), regular.leaseEpoch(),
                regular.fencingToken(), regular.executionKey(), regular.taskSnapshotDigest(), regular.terminationReason(),
                regular.budgetUsage(), regular.telemetry(), regular.traceDigest(),
                List.of(new ResearchAgentCompletionEnvelope.Evidence(
                        source.evidenceKey(), source.windowId(), source.sourceId(), source.sourceTitle(),
                        source.searchQuery(), source.readFocus(), source.quoteText(), source.claimText(),
                        source.relationType(), 900_001, 1, source.snapshotStatus())),
                List.of(new ResearchAgentCompletionEnvelope.Candidate(
                        regular.candidates().get(0).candidateKey(), regular.candidates().get(0).cellKey(), 0,
                        regular.candidates().get(0).candidateValue(), regular.candidates().get(0).evidenceKeys(),
                        900_001)), null));
        completionService.complete(exact.taskId(), exactPpm);
        assertThat(jdbcTemplate.queryForObject(
                "select support_score_ppm from source_evidence where research_run_id = ?",
                Integer.class, exact.runId())).isEqualTo(900_001);
        assertThat(jdbcTemplate.queryForObject(
                "select conflict_score_ppm from source_evidence where research_run_id = ?",
                Integer.class, exact.runId())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select confidence_score_ppm from research_agent_candidate where research_run_id = ?",
                Integer.class, exact.runId())).isEqualTo(900_001);
        assertThat(jdbcTemplate.queryForObject(
                "select confidence_score_ppm from research_cell where research_run_id = ? and cell_key = ?",
                Integer.class, exact.runId(), exact.cellKeys().get(0))).isEqualTo(900_001);

        Fixture spoofed = fixture(1);
        ResearchAgentCompletionEnvelope original = envelope(spoofed, true);
        ResearchAgentCompletionEnvelope.Evidence evidence = original.evidence().get(0);
        ResearchAgentCompletionEnvelope spoofedTitle = signed(new ResearchAgentCompletionEnvelope(
                original.schemaVersion(), original.taskId(), original.workerInstanceId(), original.leaseEpoch(),
                original.fencingToken(), original.executionKey(), original.taskSnapshotDigest(), original.terminationReason(),
                original.budgetUsage(), original.telemetry(), original.traceDigest(),
                List.of(new ResearchAgentCompletionEnvelope.Evidence(
                        evidence.evidenceKey(), evidence.windowId(), evidence.sourceId(), "Spoofed source title",
                        evidence.searchQuery(), evidence.readFocus(), evidence.quoteText(), evidence.claimText(),
                        evidence.relationType(), evidence.supportScorePpm(), evidence.conflictScorePpm(),
                        evidence.snapshotStatus())), original.candidates(), null));
        String before = state(spoofed);
        assertCode(() -> completionService.complete(spoofed.taskId(), spoofedTitle),
                "RESEARCH_AGENT_COMPLETION_EVIDENCE_UNGROUNDED");
        assertThat(state(spoofed)).isEqualTo(before);
    }

    @Test
    void shouldGroundAgainstNfcNormalizedTrustedTextButRejectCaseApproximation() {
        Fixture raw = fixture(1);
        String nfdContext = com.noteweave.common.Json.write(new com.fasterxml.jackson.databind.ObjectMapper(), Map.of(
                "provider_key", "research-fake",
                "source_policy", Map.of("source_scope", List.of(Map.of(
                        "source_id", "source-0", "source_title", "Cafe\u0301 Source",
                        "sample_text", "prefix trusted Cafe\u0301 quote suffix"))),
                "query_policy", Map.of("query", "ma4g question")));
        jdbcTemplate.update("update research_agent_task set execution_context_json = ? where id = ?",
                nfdContext, raw.taskId());
        ResearchAgentTaskService.ClaimedTask refreshed = taskService.claimTask(
                new ResearchAgentTaskService.ClaimCommand(raw.taskId(), "worker-a", 300));
        Fixture nfd = new Fixture(raw.runId(), raw.taskId(), raw.cellKeys(), refreshed.leaseEpoch(),
                refreshed.fencingToken(), refreshed.snapshotDigest(), raw.executionKey());
        ResearchAgentCompletionEnvelope base = envelope(nfd, true);
        ResearchAgentCompletionEnvelope.Evidence evidence = base.evidence().get(0);
        ResearchAgentCompletionEnvelope normalized = signed(new ResearchAgentCompletionEnvelope(
                base.schemaVersion(), base.taskId(), base.workerInstanceId(), base.leaseEpoch(), base.fencingToken(),
                base.executionKey(), base.taskSnapshotDigest(), base.terminationReason(), base.budgetUsage(),
                base.telemetry(), base.traceDigest(),
                List.of(new ResearchAgentCompletionEnvelope.Evidence(
                        evidence.evidenceKey(), evidence.windowId(), evidence.sourceId(), "Café Source",
                        evidence.searchQuery(), evidence.readFocus(), "trusted Café quote", evidence.claimText(),
                        evidence.relationType(), evidence.supportScorePpm(), evidence.conflictScorePpm(),
                        evidence.snapshotStatus())), base.candidates(), null));
        assertThat(completionService.complete(nfd.taskId(), normalized).evidenceAppended()).isEqualTo(1);

        Fixture mismatch = fixture(1);
        ResearchAgentCompletionEnvelope mismatchBase = envelope(mismatch, true);
        ResearchAgentCompletionEnvelope.Evidence mismatchEvidence = mismatchBase.evidence().get(0);
        ResearchAgentCompletionEnvelope wrongCase = signed(new ResearchAgentCompletionEnvelope(
                mismatchBase.schemaVersion(), mismatchBase.taskId(), mismatchBase.workerInstanceId(),
                mismatchBase.leaseEpoch(), mismatchBase.fencingToken(), mismatchBase.executionKey(),
                mismatchBase.taskSnapshotDigest(), mismatchBase.terminationReason(), mismatchBase.budgetUsage(),
                mismatchBase.telemetry(), mismatchBase.traceDigest(),
                List.of(new ResearchAgentCompletionEnvelope.Evidence(
                        mismatchEvidence.evidenceKey(), mismatchEvidence.windowId(), mismatchEvidence.sourceId(),
                        mismatchEvidence.sourceTitle(), mismatchEvidence.searchQuery(), mismatchEvidence.readFocus(),
                        "Trusted quote 0", mismatchEvidence.claimText(), mismatchEvidence.relationType(),
                        mismatchEvidence.supportScorePpm(), mismatchEvidence.conflictScorePpm(),
                        mismatchEvidence.snapshotStatus())), mismatchBase.candidates(), null));
        String before = state(mismatch);
        assertCode(() -> completionService.complete(mismatch.taskId(), wrongCase),
                "RESEARCH_AGENT_COMPLETION_EVIDENCE_UNGROUNDED");
        assertThat(state(mismatch)).isEqualTo(before);
    }

    @Test
    void shouldRequireAServerArchivedSnapshotBeforeAcceptingExternalEvidence() {
        Fixture fixture = fixture(1);
        ResearchAgentCompletionEnvelope base = envelope(fixture, true);
        ResearchAgentCompletionEnvelope.Evidence original = base.evidence().get(0);
        ResearchAgentCompletionEnvelope external = signed(new ResearchAgentCompletionEnvelope(
                base.schemaVersion(), base.taskId(), base.workerInstanceId(), base.leaseEpoch(),
                base.fencingToken(), base.executionKey(), base.taskSnapshotDigest(), base.terminationReason(),
                base.budgetUsage(), base.telemetry(), base.traceDigest(),
                List.of(new ResearchAgentCompletionEnvelope.Evidence(
                        original.evidenceKey(), "external-window-1", "web-source-1", "External source",
                        original.searchQuery(), original.readFocus(), "Archived external quote",
                        original.claimText(), original.relationType(), original.supportScorePpm(),
                        original.conflictScorePpm(), "EXTERNAL_ARCHIVED")),
                base.candidates(), null));

        String before = state(fixture);
        assertCode(() -> completionService.complete(fixture.taskId(), external),
                "RESEARCH_AGENT_COMPLETION_EXTERNAL_ARCHIVE_REQUIRED");
        assertThat(state(fixture)).isEqualTo(before);
    }

    @Test
    void shouldAtomicallyAcceptExternalEvidenceOnlyWhenItMatchesTheServerArchive() {
        Fixture fixture = fixture(1);
        String content = "Header. Archived external quote. Footer.";
        jdbcTemplate.update("""
                insert into research_external_snapshot(
                    id, research_run_id, research_agent_task_id, window_id, source_id, source_title,
                    source_url, source_domain, provider, adapter, snapshot_key, content_text,
                    content_sha256, archive_status)
                values (?, ?, ?, 'external-window-1', 'web-source-1', 'External source',
                        'https://example.com/research', 'example.com', 'search-provider', 'external_url',
                        'research/external/snapshot-1', ?, ?, 'ARCHIVED')
                """, Ids.newId(), fixture.runId(), fixture.taskId(), content, sha256Hex(content));
        ResearchAgentCompletionEnvelope base = envelope(fixture, true);
        ResearchAgentCompletionEnvelope.Evidence original = base.evidence().get(0);
        ResearchAgentCompletionEnvelope external = signed(new ResearchAgentCompletionEnvelope(
                base.schemaVersion(), base.taskId(), base.workerInstanceId(), base.leaseEpoch(),
                base.fencingToken(), base.executionKey(), base.taskSnapshotDigest(), base.terminationReason(),
                base.budgetUsage(), base.telemetry(), base.traceDigest(),
                List.of(new ResearchAgentCompletionEnvelope.Evidence(
                        original.evidenceKey(), "external-window-1", "web-source-1", "External source",
                        original.searchQuery(), original.readFocus(), "Archived external quote",
                        original.claimText(), original.relationType(), original.supportScorePpm(),
                        original.conflictScorePpm(), "EXTERNAL_ARCHIVED")),
                base.candidates(), null));

        ResearchAgentCompletionReceipt receipt = completionService.complete(fixture.taskId(), external);

        assertThat(receipt.acceptedMerges()).hasSize(1);
        assertThat(jdbcTemplate.queryForMap("""
                select source_url, provider, adapter, snapshot_status, snapshot_key
                from source_evidence where research_run_id = ? and evidence_key = ?
                """, fixture.runId(), original.evidenceKey()))
                .containsEntry("source_url", "https://example.com/research")
                .containsEntry("provider", "search-provider")
                .containsEntry("adapter", "external_url")
                .containsEntry("snapshot_status", "EXTERNAL_ARCHIVED")
                .containsEntry("snapshot_key", "research/external/snapshot-1");
    }

    @Test
    void shouldFailClosedAllLegacySplitWritesForSnapshotReadyDeepCell() {
        Fixture fixture = fixture(1);
        ResearchAgentCompletionEnvelope envelope = envelope(fixture, true);
        String before = state(fixture);
        ResearchAgentCompletionEnvelope.Evidence evidence = envelope.evidence().get(0);

        assertCode(() -> evidenceIngestionService.appendWorkspaceEvidence(
                        new ResearchAgentEvidenceIngestionService.EvidenceBatchCommand(
                                fixture.taskId(), "worker-a", fixture.leaseEpoch(), fixture.fencingToken(),
                                List.of(new ResearchAgentEvidenceIngestionService.WorkspaceEvidence(
                                        evidence.evidenceKey(), evidence.windowId(), evidence.sourceId(), evidence.sourceTitle(),
                                        evidence.searchQuery(), evidence.readFocus(), evidence.quoteText(), evidence.claimText(),
                                        evidence.relationType(), 0.9, 0.0, "WORKSPACE")))) ,
                "RESEARCH_AGENT_ATOMIC_COMPLETION_REQUIRED");
        ResearchAgentCompletionEnvelope.Candidate candidate = envelope.candidates().get(0);
        assertCode(() -> candidateIngressService.appendAndVerify(
                        new ResearchAgentCandidateIngressService.CandidateBatchCommand(
                                fixture.taskId(), "worker-a", fixture.leaseEpoch(), fixture.fencingToken(),
                                fixture.executionKey(), List.of(new ResearchAgentCandidateIngressService.CandidateProposal(
                                candidate.candidateKey(), candidate.candidateKey(), candidate.cellKey(),
                                candidate.baseCellVersion(), candidate.candidateValue(), candidate.evidenceKeys(), 0.9)))) ,
                "RESEARCH_AGENT_ATOMIC_COMPLETION_REQUIRED");
        assertCode(() -> taskService.submitExecution(new ResearchAgentTaskService.SubmitCommand(
                        fixture.taskId(), "worker-a", fixture.leaseEpoch(), fixture.fencingToken(),
                        fixture.executionKey(), "DONE", Map.of("search_calls", 1), envelope.traceDigest())),
                "RESEARCH_AGENT_ATOMIC_COMPLETION_REQUIRED");
        assertThat(state(fixture)).isEqualTo(before);
    }

    @Test
    void shouldRecoverReceiptAfterCommitButBeforeHttpResponseFailure() {
        Fixture fixture = fixture(1);
        ResearchAgentCompletionEnvelope envelope = envelope(fixture, true);
        doAnswer(invocation -> {
            if (invocation.getArgument(0) == ResearchAgentCompletionFaultInjector.Stage.AFTER_COMMIT_BEFORE_HTTP_RESPONSE) {
                throw new IllegalStateException("response lost");
            }
            return null;
        }).when(faultInjector).checkpoint(any(), anyInt());

        assertThatThrownBy(() -> completionService.complete(fixture.taskId(), envelope))
                .isInstanceOf(IllegalStateException.class).hasMessage("response lost");
        String committed = state(fixture);
        reset(faultInjector);

        ResearchAgentCompletionReceipt replay = completionService.complete(fixture.taskId(), envelope);

        assertThat(replay.idempotentReplay()).isTrue();
        assertThat(state(fixture)).isEqualTo(committed);
    }

    @ParameterizedTest
    @EnumSource(value = ResearchAgentCompletionFaultInjector.Stage.class, mode = EnumSource.Mode.EXCLUDE,
            names = {"AFTER_FIRST_CELL_CAS", "AFTER_COMMIT_BEFORE_HTTP_RESPONSE"})
    void shouldRollbackFullStateAtEveryTransactionalFaultStage(
            ResearchAgentCompletionFaultInjector.Stage stage
    ) {
        Fixture fixture = fixture(2);
        String before = state(fixture);
        AtomicInteger hits = new AtomicInteger();
        double rollbacks = counterValue("research.agent.completion.rollback.total", "stage",
                stage.name().toLowerCase(java.util.Locale.ROOT));
        doAnswer(invocation -> {
            if (invocation.getArgument(0) == stage) {
                hits.incrementAndGet();
                throw new IllegalStateException("injected:" + stage);
            }
            return null;
        }).when(faultInjector).checkpoint(any(), anyInt());

        assertThatThrownBy(() -> completionService.complete(fixture.taskId(), envelope(fixture, true)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("injected:" + stage);
        assertThat(hits).hasValue(1);
        assertThat(state(fixture)).isEqualTo(before);
        assertThat(counterValue("research.agent.completion.rollback.total", "stage",
                stage.name().toLowerCase(java.util.Locale.ROOT)) - rollbacks).isEqualTo(1.0);
    }

    @Test
    void shouldRejectHistoricalStableKeyConflictAndRollbackWholeEnvelope() {
        Fixture fixture = fixture(1);
        ResearchAgentCompletionEnvelope envelope = envelope(fixture, true);
        jdbcTemplate.update("""
                insert into source_evidence(id, research_run_id, evidence_key, source_id, quote_text, claim_text,
                    relation_type, snapshot_status)
                values (?, ?, ?, 'source-0', 'different trusted content', 'different', 'SUPPORTS', 'WORKSPACE')
                """, Ids.newId(), fixture.runId(), envelope.evidence().get(0).evidenceKey());
        String before = state(fixture);

        assertCode(() -> completionService.complete(fixture.taskId(), envelope),
                "RESEARCH_AGENT_COMPLETION_EVIDENCE_CONFLICT");
        assertThat(state(fixture)).isEqualTo(before);
    }

    @Test
    void shouldRejectHistoricalCandidateKeyInsteadOfBorrowingLegacyRowAsReplay() {
        Fixture fixture = fixture(1);
        ResearchAgentCompletionEnvelope envelope = envelope(fixture, true);
        ResearchAgentCompletionEnvelope.Candidate candidate = envelope.candidates().get(0);
        jdbcTemplate.update("""
                insert into research_agent_candidate(
                    id, research_run_id, task_id, execution_id, idempotency_key, cell_key,
                    base_cell_version, plan_revision, entity_set_version, lease_epoch, fencing_token,
                    candidate_value, evidence_ids_json, confidence_score, confidence_score_ppm)
                values (?, ?, ?, ?, ?, ?, ?, 1, 1, ?, ?, ?, ?, 0.9000, ?)
                """, Ids.newId(), fixture.runId(), fixture.taskId(), fixture.executionKey(),
                candidate.candidateKey(), candidate.cellKey(), candidate.baseCellVersion(),
                fixture.leaseEpoch(), fixture.fencingToken(), candidate.candidateValue(),
                "[\"" + candidate.evidenceKeys().get(0) + "\"]", candidate.confidencePpm());
        String before = state(fixture);

        assertCode(() -> completionService.complete(fixture.taskId(), envelope),
                "RESEARCH_AGENT_COMPLETION_CANDIDATE_CONFLICT");
        assertThat(state(fixture)).isEqualTo(before);
    }

    @Test
    void shouldRejectHistoricalMergeKeyInsteadOfBorrowingLegacyRowAsReplay() {
        Fixture fixture = fixture(1);
        ResearchAgentCompletionEnvelope envelope = envelope(fixture, true);
        ResearchAgentCompletionEnvelope.Candidate candidate = envelope.candidates().get(0);
        String historicalCandidateId = Ids.newId();
        jdbcTemplate.update("""
                insert into research_agent_candidate(
                    id, research_run_id, task_id, execution_id, idempotency_key, cell_key,
                    base_cell_version, plan_revision, entity_set_version, lease_epoch, fencing_token,
                    candidate_value, evidence_ids_json, confidence_score)
                values (?, ?, 'legacy-task', 'legacy-execution', ?, ?, 0, 1, 1, 1, 1,
                        'legacy-value', '[]', 0.1000)
                """, historicalCandidateId, fixture.runId(),
                "legacy-candidate-" + fixture.taskId().substring(0, 8), candidate.cellKey());
        String occupiedMergeKey = mergeKey(candidate.candidateKey());
        jdbcTemplate.update("""
                insert into research_cell_merge(
                    id, research_run_id, candidate_id, merge_key, cell_key,
                    expected_cell_version, result_cell_version, verdict, decision, reason_code,
                    accepted_evidence_ids_json)
                values (?, ?, ?, ?, ?, 0, 0, 'NOT_ENOUGH_INFO', 'REJECTED',
                        'LEGACY_CONFLICT', '[]')
                """, Ids.newId(), fixture.runId(), historicalCandidateId, occupiedMergeKey,
                candidate.cellKey());
        String before = state(fixture);

        assertCode(() -> completionService.complete(fixture.taskId(), envelope),
                "RESEARCH_AGENT_COMPLETION_MERGE_CONFLICT");
        assertThat(state(fixture)).isEqualTo(before);
    }

    @Test
    void shouldRejectOrphanExecutionWithSameKeyAsIdempotencyConflictWithoutMutation() {
        Fixture fixture = fixture(1);
        ResearchAgentCompletionEnvelope envelope = envelope(fixture, true);
        insertOrphanExecution(fixture, fixture.executionKey());
        String before = state(fixture);

        assertCode(() -> completionService.complete(fixture.taskId(), envelope),
                "RESEARCH_AGENT_COMPLETION_IDEMPOTENCY_CONFLICT");
        assertThat(state(fixture)).isEqualTo(before);
    }

    @Test
    void shouldRejectOrphanExecutionWithDifferentKeyAsAlreadyCommittedWithoutMutation() {
        Fixture fixture = fixture(1);
        ResearchAgentCompletionEnvelope envelope = envelope(fixture, true);
        insertOrphanExecution(fixture, "deep-cell:orphan:different");
        String before = state(fixture);

        assertCode(() -> completionService.complete(fixture.taskId(), envelope),
                "RESEARCH_AGENT_COMPLETION_ALREADY_COMMITTED");
        assertThat(state(fixture)).isEqualTo(before);
    }

    @Test
    void shouldStopLineageReuseAtEvidenceStableKeyGateBeforeCellEvidenceUniqueConstraint() {
        Fixture fixture = fixture(1);
        ResearchAgentCompletionEnvelope envelope = envelope(fixture, true);
        String evidenceId = Ids.newId();
        String cellId = jdbcTemplate.queryForObject("""
                select id from research_cell where research_run_id = ? and cell_key = ?
                """, String.class, fixture.runId(), fixture.cellKeys().get(0));
        String evidenceKey = envelope.evidence().get(0).evidenceKey();
        jdbcTemplate.update("""
                insert into source_evidence(
                    id, research_run_id, evidence_key, source_id, quote_text, claim_text,
                    relation_type, snapshot_status)
                values (?, ?, ?, 'source-0', 'legacy quote', 'legacy claim', 'SUPPORTS', 'WORKSPACE')
                """, evidenceId, fixture.runId(), evidenceKey);
        jdbcTemplate.update("""
                insert into research_cell_evidence(
                    id, research_run_id, research_cell_id, source_evidence_id, evidence_key)
                values (?, ?, ?, ?, ?)
                """, Ids.newId(), fixture.runId(), cellId, evidenceId, evidenceKey);
        String before = state(fixture);

        // The physical unique key is (cell, source). A legal MA4G attempt can only
        // reuse that source through its run-scoped evidence key, which is rejected
        // before source/candidate/CAS/lineage writes are reached.
        assertCode(() -> completionService.complete(fixture.taskId(), envelope),
                "RESEARCH_AGENT_COMPLETION_EVIDENCE_CONFLICT");
        assertThat(state(fixture)).isEqualTo(before);
        assertThat(count("research_cell_evidence", "research_run_id", fixture.runId())).isEqualTo(1);
    }

    @Test
    void shouldRejectCandidateKeyOwnedByAnotherAtomicCompletionInsteadOfTreatingItAsReplay() {
        Fixture first = fixture(1);
        ResearchAgentCompletionEnvelope firstEnvelope = envelope(first, true);
        completionService.complete(first.taskId(), firstEnvelope);
        Fixture second = fixtureInSameRun(first, "candidate-owner");
        ResearchAgentCompletionEnvelope secondEnvelope = envelope(second, true);
        ResearchAgentCompletionEnvelope.Candidate secondCandidate = secondEnvelope.candidates().get(0);
        ResearchAgentCompletionEnvelope conflicting = signed(new ResearchAgentCompletionEnvelope(
                secondEnvelope.schemaVersion(), secondEnvelope.taskId(), secondEnvelope.workerInstanceId(),
                secondEnvelope.leaseEpoch(), secondEnvelope.fencingToken(), secondEnvelope.executionKey(),
                secondEnvelope.taskSnapshotDigest(), secondEnvelope.terminationReason(), secondEnvelope.budgetUsage(),
                secondEnvelope.telemetry(), secondEnvelope.traceDigest(), secondEnvelope.evidence(),
                List.of(new ResearchAgentCompletionEnvelope.Candidate(
                        firstEnvelope.candidates().get(0).candidateKey(), secondCandidate.cellKey(),
                        secondCandidate.baseCellVersion(), secondCandidate.candidateValue(),
                        secondCandidate.evidenceKeys(), secondCandidate.confidencePpm())), null));
        String before = state(second);

        assertCode(() -> completionService.complete(second.taskId(), conflicting),
                "RESEARCH_AGENT_COMPLETION_CANDIDATE_CONFLICT");
        assertThat(state(second)).isEqualTo(before);
    }

    @Test
    void shouldRejectEvidenceKeyOwnedByAnotherCompletionInSameRun() {
        Fixture first = fixture(1);
        ResearchAgentCompletionEnvelope firstEnvelope = envelope(first, true);
        completionService.complete(first.taskId(), firstEnvelope);
        Fixture second = fixtureInSameRun(first, "second");
        ResearchAgentCompletionEnvelope secondEnvelope = envelope(second, true);
        String occupiedEvidenceKey = firstEnvelope.evidence().get(0).evidenceKey();
        ResearchAgentCompletionEnvelope.Evidence source = secondEnvelope.evidence().get(0);
        ResearchAgentCompletionEnvelope conflicting = signed(new ResearchAgentCompletionEnvelope(
                secondEnvelope.schemaVersion(), secondEnvelope.taskId(), secondEnvelope.workerInstanceId(),
                secondEnvelope.leaseEpoch(), secondEnvelope.fencingToken(), secondEnvelope.executionKey(),
                secondEnvelope.taskSnapshotDigest(), secondEnvelope.terminationReason(), secondEnvelope.budgetUsage(),
                secondEnvelope.telemetry(), secondEnvelope.traceDigest(),
                List.of(new ResearchAgentCompletionEnvelope.Evidence(
                        occupiedEvidenceKey, source.windowId(), source.sourceId(), source.sourceTitle(),
                        source.searchQuery(), source.readFocus(), source.quoteText(), source.claimText(),
                        source.relationType(), source.supportScorePpm(), source.conflictScorePpm(), source.snapshotStatus())),
                List.of(new ResearchAgentCompletionEnvelope.Candidate(
                        secondEnvelope.candidates().get(0).candidateKey(), secondEnvelope.candidates().get(0).cellKey(),
                        secondEnvelope.candidates().get(0).baseCellVersion(),
                        secondEnvelope.candidates().get(0).candidateValue(), List.of(occupiedEvidenceKey),
                        secondEnvelope.candidates().get(0).confidencePpm())), null));
        String before = state(second);

        assertCode(() -> completionService.complete(second.taskId(), conflicting),
                "RESEARCH_AGENT_COMPLETION_EVIDENCE_CONFLICT");
        assertThat(state(second)).isEqualTo(before);
    }

    @Test
    void shouldSerializeConcurrentIdenticalAndConflictingCompletionsToOneWinner() throws Exception {
        Fixture identicalFixture = fixture(1);
        ResearchAgentCompletionEnvelope identical = envelope(identicalFixture, true);
        List<Object> identicalResults = race(
                () -> completionService.complete(identicalFixture.taskId(), identical),
                () -> completionService.complete(identicalFixture.taskId(), identical));
        assertThat(identicalResults).allMatch(result -> result instanceof ResearchAgentCompletionReceipt);
        assertThat(identicalResults.stream().map(result -> ((ResearchAgentCompletionReceipt) result).completionId()).distinct())
                .hasSize(1);
        assertThat(count("research_agent_completion", "research_agent_task_id", identicalFixture.taskId())).isEqualTo(1);

        Fixture conflictFixture = fixture(1);
        ResearchAgentCompletionEnvelope first = envelope(conflictFixture, true);
        ResearchAgentCompletionEnvelope second = signed(new ResearchAgentCompletionEnvelope(
                first.schemaVersion(), first.taskId(), first.workerInstanceId(), first.leaseEpoch(), first.fencingToken(),
                first.executionKey(), first.taskSnapshotDigest(), first.terminationReason(), first.budgetUsage(), first.telemetry(),
                "sha256:" + "8".repeat(64), first.evidence(), first.candidates(), null));
        List<Object> conflicts = race(
                () -> completionService.complete(conflictFixture.taskId(), first),
                () -> completionService.complete(conflictFixture.taskId(), second));
        assertThat(conflicts.stream().filter(ResearchAgentCompletionReceipt.class::isInstance)).hasSize(1);
        assertThat(conflicts.stream().filter(BusinessException.class::isInstance)
                .map(BusinessException.class::cast).map(BusinessException::code))
                .containsExactly("RESEARCH_AGENT_COMPLETION_IDEMPOTENCY_CONFLICT");
        assertThat(count("research_agent_completion", "research_agent_task_id", conflictFixture.taskId())).isEqualTo(1);
    }

    @Test
    void shouldLinearizeCompletionAgainstCancelAndDirectBudgetReleaseWithoutDeadlock() throws Exception {
        Fixture cancelRace = fixture(1);
        ResearchAgentCompletionEnvelope cancelEnvelope = envelope(cancelRace, true);
        List<Object> cancelResults = race(
                () -> completionService.complete(cancelRace.taskId(), cancelEnvelope),
                () -> lifecycleService.cancelRun(cancelRace.runId(), "CONCURRENT_CANCEL"));
        assertThat(cancelResults).hasSize(2);
        int committed = count("research_agent_completion", "research_agent_task_id", cancelRace.taskId());
        String cancelBudget = jdbcTemplate.queryForObject(
                "select state from research_budget_reservation where research_agent_task_id = ?",
                String.class, cancelRace.taskId());
        if (committed == 1) {
            assertThat(cancelResults.get(0)).isInstanceOf(ResearchAgentCompletionReceipt.class);
            assertThat(cancelResults.get(1)).isEqualTo(new ResearchAgentLifecycleService.CancelReceipt(0, false));
            assertThat(cancelBudget).isEqualTo("SETTLED");
            assertThat(jdbcTemplate.queryForObject(
                    "select status from research_agent_task where id = ?", String.class, cancelRace.taskId()))
                    .isEqualTo("SUBMITTED");
        } else {
            assertThat(committed).isZero();
            assertThat(cancelResults.get(0)).isInstanceOfSatisfying(BusinessException.class,
                    exception -> assertThat(exception.code()).isEqualTo("RESEARCH_AGENT_RUN_TERMINAL"));
            assertThat(cancelResults.get(1)).isEqualTo(new ResearchAgentLifecycleService.CancelReceipt(1, false));
            assertThat(cancelBudget).isEqualTo("RELEASED");
            assertThat(jdbcTemplate.queryForObject(
                    "select status from research_agent_task where id = ?", String.class, cancelRace.taskId()))
                    .isEqualTo("CANCELLED");
        }

        Fixture releaseRace = fixture(1);
        ResearchAgentCompletionEnvelope releaseEnvelope = envelope(releaseRace, true);
        String reservationId = jdbcTemplate.queryForObject(
                "select id from research_budget_reservation where research_agent_task_id = ?",
                String.class, releaseRace.taskId());
        List<Object> releaseResults = race(
                () -> completionService.complete(releaseRace.taskId(), releaseEnvelope),
                () -> budgetService.release(reservationId));
        assertThat(releaseResults).hasSize(2);
        assertThat(releaseResults).anyMatch(ResearchAgentCompletionReceipt.class::isInstance);
        assertThat(releaseResults).anyMatch(item -> item instanceof BusinessException exception
                && "RESEARCH_AGENT_ATOMIC_COMPLETION_REQUIRED".equals(exception.code()));
        int releaseCommitted = count("research_agent_completion", "research_agent_task_id", releaseRace.taskId());
        String releaseState = jdbcTemplate.queryForObject(
                "select state from research_budget_reservation where id = ?", String.class, reservationId);
        assertThat(releaseCommitted).isEqualTo(1);
        assertThat(releaseState).isEqualTo("SETTLED");
    }

    private Fixture fixture(int cellCount) {
        String workspaceId = Ids.newId();
        String parentTaskId = Ids.newId();
        String runId = Ids.newId();
        jdbcTemplate.update("insert into workspace(id, owner_id, name, status) values (?, 'local-user', ?, 'ACTIVE')",
                workspaceId, "ma4g-" + runId);
        jdbcTemplate.update("""
                insert into task(id, workspace_id, task_type, task_status, target_type, target_id)
                values (?, ?, 'RESEARCH_RUN', 'RUNNING', 'RESEARCH_RUN', ?)
                """, parentTaskId, workspaceId, runId);
        jdbcTemplate.update("""
                insert into research_run(id, workspace_id, task_id, question, profile_key, source_scope_json,
                    status, agent_execution_mode)
                values (?, ?, ?, 'ma4g question', 'DEFAULT', '[]', 'RUNNING', 'INCREMENTAL_V1')
                """, runId, workspaceId, parentTaskId);
        String rowId = Ids.newId();
        jdbcTemplate.update("insert into research_row(id, research_run_id, row_key, row_status) values (?, ?, 'entity-1', 'CANDIDATE_READY')",
                rowId, runId);
        List<String> cellKeys = new ArrayList<>();
        List<ResearchAgentTaskService.TargetCellBinding> bindings = new ArrayList<>();
        List<Map<String, Object>> sources = new ArrayList<>();
        for (int index = 0; index < cellCount; index++) {
            String cellKey = "entity-1:field-" + index;
            cellKeys.add(cellKey);
            bindings.add(new ResearchAgentTaskService.TargetCellBinding(cellKey, 0));
            sources.add(Map.of("source_id", "source-" + index, "source_title", "Source " + index,
                    "sample_text", "prefix trusted quote " + index + " suffix"));
            jdbcTemplate.update("""
                    insert into research_cell(id, research_run_id, research_row_id, cell_key, column_key,
                        candidate_value, cell_status, repair_count, cell_version, plan_revision, entity_set_version)
                    values (?, ?, ?, ?, ?, ?, 'CANDIDATE_READY', 0, 0, 1, 1)
                    """, Ids.newId(), runId, rowId, cellKey, "field-" + index, "old-" + index);
        }
        Map<String, Long> reserved = Map.of(
                "llm_calls", 2L,
                "search_calls", 2L,
                "fetch_calls", 2L,
                "read_calls", 2L,
                "extract_calls", 2L,
                "evidence_cards", (long) cellCount + 2,
                "evidence_appended", (long) cellCount + 2,
                "candidates_submitted", (long) cellCount + 1,
                "candidate_merges_accepted", (long) cellCount,
                "candidate_merges_rejected", (long) cellCount);
        Map<String, Object> snapshotBudget = new java.util.LinkedHashMap<>();
        reserved.forEach(snapshotBudget::put);
        String taskId = taskService.createTask(new ResearchAgentTaskService.CreateTaskCommand(
                runId, "ma4g-task-" + runId, "ma4g-idem-" + runId, 1, "DEEP_CELL", "entity-1", "main",
                1, 1, cellKeys, snapshotBudget, bindings,
                new ResearchAgentTaskService.TaskExecutionContext("research-fake",
                        Map.of("source_scope", sources), Map.of("query", "ma4g question")))).taskId();
        outboxService.enqueue(taskId);
        budgetService.reserve(new ResearchBudgetAndCheckpointService.ReserveCommand(
                runId, taskId, "ma4g-budget-" + taskId, reserved));
        ResearchAgentTaskService.ClaimedTask claim = taskService.claimTask(
                new ResearchAgentTaskService.ClaimCommand(taskId, "worker-a", 300));
        return new Fixture(runId, taskId, cellKeys, claim.leaseEpoch(), claim.fencingToken(),
                claim.snapshotDigest(), "deep-cell:" + taskId + ":" + claim.leaseEpoch() + ":" + claim.fencingToken());
    }

    private Fixture quorumFixture() {
        Fixture fixture = fixture(1);
        String group = "quorum:" + fixture.runId();
        jdbcTemplate.update("""
                update research_agent_task
                set logical_task_key = ?, quorum_group_key = ?, candidate_quorum = 2, candidate_slot = 1,
                    snapshot_schema_version = 'research-agent-task-snapshot.v2', snapshot_digest = null
                where id = ?
                """, "deep-cell:" + fixture.runId(), group, fixture.taskId());
        jdbcTemplate.update("""
                update research_cell set high_risk = true, active_task_id = ?
                where research_run_id = ? and cell_key = ?
                """, group, fixture.runId(), fixture.cellKeys().get(0));
        ResearchAgentTaskService.ClaimedTask replay = taskService.claimTask(
                new ResearchAgentTaskService.ClaimCommand(fixture.taskId(), "worker-a", 300));
        return new Fixture(fixture.runId(), fixture.taskId(), fixture.cellKeys(), replay.leaseEpoch(),
                replay.fencingToken(), replay.snapshotDigest(), fixture.executionKey());
    }

    private Fixture counterfactualRepairFixture() {
        Fixture fixture = fixture(1);
        jdbcTemplate.update("""
                update research_agent_task
                set role = 'COUNTERFACTUAL', branch_id = 'branch-counterfactual',
                    logical_task_key = ?, quorum_group_key = null, candidate_quorum = 1, candidate_slot = 1,
                    snapshot_schema_version = 'research-agent-task-snapshot.v2', snapshot_digest = null
                where id = ?
                """, "counterfactual:" + fixture.runId(), fixture.taskId());
        ResearchAgentTaskService.ClaimedTask replay = taskService.claimTask(
                new ResearchAgentTaskService.ClaimCommand(fixture.taskId(), "worker-a", 300));
        return new Fixture(fixture.runId(), fixture.taskId(), fixture.cellKeys(), replay.leaseEpoch(),
                replay.fencingToken(), replay.snapshotDigest(), fixture.executionKey());
    }

    private QuorumPair quorumPair() {
        return quorumPair("source-1", "Source 1", "prefix trusted quote 1 suffix");
    }

    private QuorumPair quorumPair(String secondSourceId, String secondSourceTitle, String secondSample) {
        Fixture first = quorumFixture();
        String group = "quorum:" + first.runId();
        Map<String, Long> reserved = Map.of(
                "llm_calls", 2L, "search_calls", 2L, "fetch_calls", 2L, "read_calls", 2L,
                "extract_calls", 2L, "evidence_cards", 3L, "evidence_appended", 3L,
                "candidates_submitted", 2L, "candidate_merges_accepted", 1L,
                "candidate_merges_rejected", 1L);
        Map<String, Object> snapshotBudget = new java.util.LinkedHashMap<>();
        reserved.forEach(snapshotBudget::put);
        String secondTaskId = taskService.createTask(new ResearchAgentTaskService.CreateTaskCommand(
                first.runId(), "ma5q-task-2-" + first.runId(), "ma5q-idem-2-" + first.runId(),
                1, "COUNTERFACTUAL", "entity-1", "branch-quorum-2", 1, 1,
                first.cellKeys(), snapshotBudget,
                List.of(new ResearchAgentTaskService.TargetCellBinding(first.cellKeys().get(0), 0)),
                new ResearchAgentTaskService.TaskExecutionContext("research-fake",
                        Map.of("source_scope", List.of(Map.of(
                                "source_id", secondSourceId, "source_title", secondSourceTitle,
                                "sample_text", secondSample))),
                        Map.of("query", "ma4g question")))).taskId();
        jdbcTemplate.update("""
                update research_agent_task
                set logical_task_key = ?, quorum_group_key = ?, candidate_quorum = 2, candidate_slot = 2,
                    snapshot_schema_version = 'research-agent-task-snapshot.v2', snapshot_digest = null
                where id = ?
                """, "deep-cell:" + first.runId(), group, secondTaskId);
        outboxService.enqueue(secondTaskId);
        budgetService.reserve(new ResearchBudgetAndCheckpointService.ReserveCommand(
                first.runId(), secondTaskId, "ma5q-budget-" + secondTaskId, reserved));
        ResearchAgentTaskService.ClaimedTask claim = taskService.claimTask(
                new ResearchAgentTaskService.ClaimCommand(secondTaskId, "worker-b", 300));
        Fixture second = new Fixture(first.runId(), secondTaskId, first.cellKeys(), claim.leaseEpoch(),
                claim.fencingToken(), claim.snapshotDigest(),
                "deep-cell:" + secondTaskId + ":" + claim.leaseEpoch() + ":" + claim.fencingToken());
        return new QuorumPair(first, second);
    }

    private ResearchAgentCompletionEnvelope envelopeForSource(
            Fixture fixture, String worker, String sourceId, String sourceTitle, String quote
    ) {
        return envelopeForSourceAndValue(fixture, worker, sourceId, sourceTitle, quote, "value-0");
    }

    private ResearchAgentCompletionEnvelope envelopeForSourceAndValue(
            Fixture fixture, String worker, String sourceId, String sourceTitle, String quote, String candidateValue
    ) {
        ResearchAgentCompletionEnvelope base = envelope(fixture, true);
        ResearchAgentCompletionEnvelope.Evidence original = base.evidence().get(0);
        ResearchAgentCompletionEnvelope.Evidence evidence = new ResearchAgentCompletionEnvelope.Evidence(
                original.evidenceKey(), original.windowId(), sourceId, sourceTitle, original.searchQuery(),
                original.readFocus(), quote, candidateValue, "SUPPORTS", original.supportScorePpm(),
                original.conflictScorePpm(), original.snapshotStatus());
        ResearchAgentCompletionEnvelope.Candidate originalCandidate = base.candidates().get(0);
        ResearchAgentCompletionEnvelope.Candidate candidate = new ResearchAgentCompletionEnvelope.Candidate(
                originalCandidate.candidateKey(), originalCandidate.cellKey(), originalCandidate.baseCellVersion(),
                candidateValue, originalCandidate.evidenceKeys(), originalCandidate.confidencePpm());
        return signed(new ResearchAgentCompletionEnvelope(
                base.schemaVersion(), base.taskId(), worker, base.leaseEpoch(), base.fencingToken(),
                base.executionKey(), base.taskSnapshotDigest(), base.terminationReason(), base.budgetUsage(),
                base.telemetry(), base.traceDigest(), List.of(evidence), List.of(candidate), null));
    }

    private Fixture fixtureInSameRun(Fixture existing, String suffix) {
        String rowId = jdbcTemplate.queryForObject(
                "select id from research_row where research_run_id = ? order by id limit 1",
                String.class, existing.runId());
        String cellKey = "entity-1:field-" + suffix;
        jdbcTemplate.update("""
                insert into research_cell(id, research_run_id, research_row_id, cell_key, column_key,
                    candidate_value, cell_status, repair_count, cell_version, plan_revision, entity_set_version)
                values (?, ?, ?, ?, ?, ?, 'CANDIDATE_READY', 0, 0, 1, 1)
                """, Ids.newId(), existing.runId(), rowId, cellKey, "field-" + suffix, "old-0");
        Map<String, Long> reserved = Map.of(
                "llm_calls", 2L, "search_calls", 2L, "fetch_calls", 2L, "read_calls", 2L,
                "extract_calls", 2L, "evidence_cards", 3L, "evidence_appended", 3L,
                "candidates_submitted", 2L, "candidate_merges_accepted", 1L,
                "candidate_merges_rejected", 1L);
        Map<String, Object> snapshotBudget = new java.util.LinkedHashMap<>();
        reserved.forEach(snapshotBudget::put);
        String taskId = taskService.createTask(new ResearchAgentTaskService.CreateTaskCommand(
                existing.runId(), "ma4g-task-" + suffix + "-" + Ids.newId(),
                "ma4g-idem-" + suffix + "-" + Ids.newId(), 1, "DEEP_CELL", "entity-1", "main", 1, 1,
                List.of(cellKey), snapshotBudget,
                List.of(new ResearchAgentTaskService.TargetCellBinding(cellKey, 0)),
                new ResearchAgentTaskService.TaskExecutionContext("research-fake",
                        Map.of("source_scope", List.of(Map.of(
                                "source_id", "source-0", "source_title", "Source 0",
                                "sample_text", "prefix trusted quote 0 suffix"))),
                        Map.of("query", "ma4g question")))).taskId();
        outboxService.enqueue(taskId);
        budgetService.reserve(new ResearchBudgetAndCheckpointService.ReserveCommand(
                existing.runId(), taskId, "ma4g-budget-" + taskId, reserved));
        ResearchAgentTaskService.ClaimedTask claim = taskService.claimTask(
                new ResearchAgentTaskService.ClaimCommand(taskId, "worker-a", 300));
        return new Fixture(existing.runId(), taskId, List.of(cellKey), claim.leaseEpoch(), claim.fencingToken(),
                claim.snapshotDigest(), "deep-cell:" + taskId + ":" + claim.leaseEpoch() + ":" + claim.fencingToken());
    }

    private ResearchAgentCompletionEnvelope envelope(Fixture fixture, boolean candidates) {
        List<ResearchAgentCompletionEnvelope.Evidence> evidence = new ArrayList<>();
        List<ResearchAgentCompletionEnvelope.Candidate> proposals = new ArrayList<>();
        for (int index = 0; index < fixture.cellKeys().size(); index++) {
            String evidenceKey = "ev-" + fixture.taskId().substring(0, 8) + "-" + index;
            evidence.add(new ResearchAgentCompletionEnvelope.Evidence(
                    evidenceKey, "window-" + index, "source-" + index, "Source " + index,
                    "ma4g question", "field-" + index, "trusted quote " + index, "value-" + index,
                    "SUPPORTS", 900_000, 0, "WORKSPACE"));
            if (candidates) {
                proposals.add(new ResearchAgentCompletionEnvelope.Candidate(
                        "cand-" + fixture.taskId().substring(0, 8) + "-" + index,
                        fixture.cellKeys().get(index), 0, "value-" + index, List.of(evidenceKey), 900_000));
            }
        }
        Map<String, Long> usage = Map.of(
                "llm_calls", 0L, "search_calls", 1L, "fetch_calls", 1L, "read_calls", 1L, "extract_calls", 1L,
                "evidence_cards", (long) evidence.size(), "candidates_submitted", (long) proposals.size());
        return signed(new ResearchAgentCompletionEnvelope(
                "research-agent-completion.v1", fixture.taskId(), "worker-a", fixture.leaseEpoch(),
                fixture.fencingToken(), fixture.executionKey(), fixture.snapshotDigest(),
                candidates ? "CANDIDATES_PROPOSED" : "EVIDENCE_ONLY", usage,
                Map.of("search_hits", (long) evidence.size(), "documents", (long) evidence.size(),
                        "windows", (long) evidence.size()),
                "sha256:" + "3".repeat(64), evidence, proposals, null));
    }

    private ResearchAgentCompletionEnvelope signed(ResearchAgentCompletionEnvelope envelope) {
        return envelope.withEnvelopeDigest(canonicalizer.digest(envelope));
    }

    private ResearchAgentCompletionEnvelope replaceIdentity(
            ResearchAgentCompletionEnvelope original,
            String worker,
            String snapshot
    ) {
        return signed(new ResearchAgentCompletionEnvelope(
                original.schemaVersion(), original.taskId(), worker == null ? original.workerInstanceId() : worker,
                original.leaseEpoch(), original.fencingToken(), original.executionKey(),
                snapshot == null ? original.taskSnapshotDigest() : snapshot, original.terminationReason(),
                original.budgetUsage(), original.telemetry(), original.traceDigest(), original.evidence(),
                original.candidates(), null));
    }

    private ResearchAgentCompletionEnvelope replaceLease(
            ResearchAgentCompletionEnvelope original,
            int epoch,
            long fence
    ) {
        return signed(new ResearchAgentCompletionEnvelope(
                original.schemaVersion(), original.taskId(), original.workerInstanceId(), epoch, fence,
                original.executionKey(), original.taskSnapshotDigest(), original.terminationReason(),
                original.budgetUsage(), original.telemetry(), original.traceDigest(), original.evidence(),
                original.candidates(), null));
    }

    private void insertOrphanExecution(Fixture fixture, String executionKey) {
        jdbcTemplate.update("""
                insert into research_agent_execution(
                    id, research_agent_task_id, execution_key, lease_epoch, fencing_token,
                    worker_instance_id, status, termination_reason, usage_json, trace_digest)
                values (?, ?, ?, ?, ?, 'worker-a', 'SUBMITTED', 'ORPHAN_FIXTURE', '{}', ?)
                """, Ids.newId(), fixture.taskId(), executionKey, fixture.leaseEpoch(),
                fixture.fencingToken(), "sha256:" + "5".repeat(64));
    }

    private String mergeKey(String candidateKey) {
        return "merge:" + canonicalizer.domainSeparatedDigest(
                "research-agent-merge-key.v1", Map.of("candidate_key", candidateKey))
                .substring("sha256:".length());
    }

    private String sha256Hex(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private void assertZeroState(Fixture fixture, ResearchAgentCompletionEnvelope envelope, String code) {
        String before = state(fixture);
        assertCode(() -> completionService.complete(fixture.taskId(), envelope), code);
        assertThat(state(fixture)).isEqualTo(before);
    }

    private int count(String table, String column, String value) {
        return jdbcTemplate.queryForObject("select count(*) from " + table + " where " + column + " = ?",
                Integer.class, value);
    }

    private double counterValue(String name, String... tags) {
        io.micrometer.core.instrument.Counter counter = meterRegistry.find(name).tags(tags).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private long timerCount(String name) {
        io.micrometer.core.instrument.Timer timer = meterRegistry.find(name).timer();
        return timer == null ? 0L : timer.count();
    }

    private long summaryCount(String name) {
        io.micrometer.core.instrument.DistributionSummary summary = meterRegistry.find(name).summary();
        return summary == null ? 0L : summary.count();
    }

    private void assertPersistedRowDigests(
            Fixture fixture,
            ResearchAgentCompletionReceipt receipt,
            ResearchAgentCompletionEnvelope envelope
    ) {
        Map<String, Object> evidence = jdbcTemplate.queryForMap("""
                select id, research_run_id, agent_completion_id, evidence_key, window_id, source_id, source_title,
                       source_url, provider, adapter, search_query, read_focus, quote_text, claim_text, relation_type,
                       support_score, conflict_score, support_score_ppm, conflict_score_ppm,
                       snapshot_status, snapshot_key, content_digest
                from source_evidence where agent_completion_id = ?
                """, receipt.completionId());
        Map<String, Object> evidenceContent = new java.util.LinkedHashMap<>();
        copy(evidence, evidenceContent, "id", "research_run_id", "agent_completion_id", "evidence_key",
                "window_id", "source_id", "source_title", "source_url", "provider", "adapter", "search_query",
                "read_focus", "quote_text", "claim_text", "relation_type");
        evidenceContent.put("support_score", decimal(evidence.get("support_score")));
        evidenceContent.put("conflict_score", decimal(evidence.get("conflict_score")));
        copy(evidence, evidenceContent, "support_score_ppm", "conflict_score_ppm", "snapshot_status", "snapshot_key");
        assertThat(evidence.get("content_digest")).isEqualTo(canonicalizer.domainSeparatedDigest(
                "research-agent-source-evidence.v1", evidenceContent));

        Map<String, Object> candidate = jdbcTemplate.queryForMap("""
                select id, research_run_id, agent_completion_id, research_agent_execution_id, task_id, execution_id,
                       idempotency_key, cell_key, base_cell_version, plan_revision, entity_set_version, lease_epoch,
                       fencing_token, candidate_value, confidence_score, confidence_score_ppm, content_digest
                from research_agent_candidate where agent_completion_id = ?
                """, receipt.completionId());
        Map<String, Object> candidateContent = new java.util.LinkedHashMap<>();
        copy(candidate, candidateContent, "id", "research_run_id", "agent_completion_id",
                "research_agent_execution_id", "task_id", "execution_id", "idempotency_key", "cell_key",
                "base_cell_version", "plan_revision", "entity_set_version", "lease_epoch", "fencing_token",
                "candidate_value");
        candidateContent.put("evidence_ids", envelope.candidates().get(0).evidenceKeys().stream().sorted().toList());
        candidateContent.put("confidence_score", decimal(candidate.get("confidence_score")));
        candidateContent.put("confidence_score_ppm", candidate.get("confidence_score_ppm"));
        assertThat(candidate.get("content_digest")).isEqualTo(canonicalizer.domainSeparatedDigest(
                "research-agent-candidate.v1", candidateContent));

        Map<String, Object> merge = jdbcTemplate.queryForMap("""
                select id, research_run_id, agent_completion_id, candidate_id, merge_key, cell_key,
                       expected_cell_version, result_cell_version, verdict, decision, reason_code, content_digest
                from research_cell_merge where agent_completion_id = ?
                """, receipt.completionId());
        Map<String, Object> mergeContent = new java.util.LinkedHashMap<>();
        copy(merge, mergeContent, "id", "research_run_id", "agent_completion_id", "candidate_id", "merge_key",
                "cell_key", "expected_cell_version", "result_cell_version", "verdict", "decision", "reason_code");
        mergeContent.put("accepted_evidence_ids", envelope.candidates().get(0).evidenceKeys().stream().sorted().toList());
        assertThat(merge.get("content_digest")).isEqualTo(canonicalizer.domainSeparatedDigest(
                "research-agent-cell-merge.v1", mergeContent));

        Map<String, Object> relation = jdbcTemplate.queryForMap("""
                select id, research_run_id, agent_completion_id, research_cell_id, source_evidence_id,
                       evidence_key, content_digest
                from research_cell_evidence where agent_completion_id = ?
                """, receipt.completionId());
        Map<String, Object> relationContent = new java.util.LinkedHashMap<>();
        copy(relation, relationContent, "id", "research_run_id", "agent_completion_id");
        relationContent.put("candidate_id", candidate.get("id"));
        relationContent.put("merge_id", merge.get("id"));
        relationContent.put("merge_key", merge.get("merge_key"));
        copy(relation, relationContent, "research_cell_id", "source_evidence_id", "evidence_key");
        assertThat(relation.get("content_digest")).isEqualTo(canonicalizer.domainSeparatedDigest(
                "research-agent-cell-evidence.v1", relationContent));

        try {
            Map<String, Object> persistedReceipt = new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                    jdbcTemplate.queryForObject("select receipt_json from research_agent_completion where id = ?",
                            String.class, receipt.completionId()),
                    new com.fasterxml.jackson.core.type.TypeReference<java.util.LinkedHashMap<String, Object>>() { });
            Object embedded = persistedReceipt.remove("receipt_digest");
            assertThat(embedded).isEqualTo(receipt.receiptDigest());
            assertThat(persistedReceipt).doesNotContainKey("idempotent_replay");
            assertThat(receipt.receiptDigest()).isEqualTo(canonicalizer.domainSeparatedDigest(
                    "research-agent-completion-receipt.v1", persistedReceipt));
        } catch (java.io.IOException exception) {
            throw new AssertionError(exception);
        }
    }

    private void copy(Map<String, Object> source, Map<String, Object> target, String... keys) {
        for (String key : keys) target.put(key, source.get(key));
    }

    private String decimal(Object value) {
        return ((java.math.BigDecimal) value).toPlainString();
    }

    private String state(Fixture fixture) {
        return "run=" + jdbcTemplate.queryForList(
                        "select * from research_run where id = ? order by id", fixture.runId())
                + "|task=" + jdbcTemplate.queryForList(
                        "select * from research_agent_task where id = ? order by id", fixture.taskId())
                + "|execution=" + jdbcTemplate.queryForList(
                        "select * from research_agent_execution where research_agent_task_id = ? order by execution_key, id",
                        fixture.taskId())
                + "|completion=" + jdbcTemplate.queryForList(
                        "select * from research_agent_completion where research_agent_task_id = ? order by completion_key, id",
                        fixture.taskId())
                + "|evidence=" + jdbcTemplate.queryForList(
                        "select * from source_evidence where research_run_id = ? order by evidence_key, id",
                        fixture.runId())
                + "|candidate=" + jdbcTemplate.queryForList(
                        "select * from research_agent_candidate where research_run_id = ? order by idempotency_key, id",
                        fixture.runId())
                + "|merge=" + jdbcTemplate.queryForList(
                        "select * from research_cell_merge where research_run_id = ? order by merge_key, id",
                        fixture.runId())
                + "|cellEvidence=" + jdbcTemplate.queryForList(
                        "select * from research_cell_evidence where research_run_id = ? order by evidence_key, id",
                        fixture.runId())
                + "|cells=" + jdbcTemplate.queryForList(
                        "select * from research_cell where research_run_id = ? order by cell_key, id",
                        fixture.runId())
                + "|budget=" + jdbcTemplate.queryForList(
                        "select * from research_budget_reservation where research_agent_task_id = ? order by id",
                        fixture.taskId())
                + "|outbox=" + jdbcTemplate.queryForList(
                        "select * from research_agent_outbox where research_agent_task_id = ? order by id",
                        fixture.taskId());
    }

    private void assertCode(Runnable operation, String code) {
        assertThatThrownBy(operation::run)
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).code())
                .isEqualTo(code);
    }

    private List<Object> race(java.util.concurrent.Callable<Object> left,
                              java.util.concurrent.Callable<Object> right) throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            Future<Object> first = executor.submit(() -> invokeAfter(gate, left));
            Future<Object> second = executor.submit(() -> invokeAfter(gate, right));
            gate.countDown();
            return List.of(first.get(10, java.util.concurrent.TimeUnit.SECONDS),
                    second.get(10, java.util.concurrent.TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    private Object invokeAfter(CountDownLatch gate, java.util.concurrent.Callable<Object> operation) throws Exception {
        gate.await();
        try {
            return operation.call();
        } catch (BusinessException exception) {
            return exception;
        }
    }

    private record Fixture(
            String runId,
            String taskId,
            List<String> cellKeys,
            int leaseEpoch,
            long fencingToken,
            String snapshotDigest,
            String executionKey
    ) { }
    private record QuorumPair(Fixture first, Fixture second) { }
}
