package com.noteweave.research;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import com.noteweave.common.Json;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.sql.Timestamp;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** The sole REQUIRED transaction that commits every MA4G completion effect. */
@Service
class ResearchAgentCompletionCommitter {
    private static final String RECEIPT_SCHEMA = "research-agent-completion-receipt.v1";
    private static final String RECEIPT_DIGEST_DOMAIN = "research-agent-completion-receipt.v1";
    private static final String EVIDENCE_DIGEST_DOMAIN = "research-agent-source-evidence.v1";
    private static final String CANDIDATE_DIGEST_DOMAIN = "research-agent-candidate.v1";
    private static final String MERGE_DIGEST_DOMAIN = "research-agent-cell-merge.v1";
    private static final String CELL_EVIDENCE_DIGEST_DOMAIN = "research-agent-cell-evidence.v1";
    private static final String SNAPSHOT_SCHEMA = "research-agent-task-snapshot.v1";
    private static final String SNAPSHOT_SCHEMA_V2 = "research-agent-task-snapshot.v2";
    private static final String BLIND_DIGEST_DOMAIN = "research-agent-blind-candidate.v1";
    private static final Set<String> TERMINAL_RUN = Set.of("COMPLETED", "FAILED", "CANCELLED");
    private static final Set<String> SERVER_DERIVED_USAGE = Set.of(
            "evidence_appended", "candidate_merges_accepted", "candidate_merges_rejected"
    );
    private static final Set<String> COMPLETE_BUDGET_KEYS;
    static {
        Set<String> keys = new HashSet<>(ResearchAgentCompletionCanonicalizer.WORKER_USAGE_KEYS);
        keys.addAll(SERVER_DERIVED_USAGE);
        COMPLETE_BUDGET_KEYS = Set.copyOf(keys);
    }

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ResearchAgentCompletionCanonicalizer canonicalizer;
    private final ResearchAgentTaskSnapshotCanonicalizer snapshotCanonicalizer;
    private final ResearchAgentCompletionReplayRepository replayRepository;
    private final ObjectProvider<ResearchAgentCompletionFaultInjector> faultInjectors;
    private final ResearchAgentCompletionMetrics completionMetrics;

    ResearchAgentCompletionCommitter(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            ResearchAgentCompletionCanonicalizer canonicalizer,
            ResearchAgentTaskSnapshotCanonicalizer snapshotCanonicalizer,
            ResearchAgentCompletionReplayRepository replayRepository,
            ObjectProvider<ResearchAgentCompletionFaultInjector> faultInjectors,
            ResearchAgentCompletionMetrics completionMetrics
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.canonicalizer = canonicalizer;
        this.snapshotCanonicalizer = snapshotCanonicalizer;
        this.replayRepository = replayRepository;
        this.faultInjectors = faultInjectors;
        this.completionMetrics = completionMetrics;
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public ResearchAgentCompletionReceipt commit(
            ResearchAgentCompletionCanonicalizer.ValidatedEnvelope validated
    ) {
        ResearchAgentCompletionEnvelope envelope = validated.envelope();

        // Unlocked lookup only discovers the parent lock key; authority is re-read below.
        String discoveredRunId = jdbcTemplate.query(
                "select research_run_id from research_agent_task where id = ?",
                rs -> rs.next() ? rs.getString(1) : null, envelope.taskId());
        if (discoveredRunId == null) {
            throw new BusinessException("RESEARCH_AGENT_TASK_NOT_FOUND", "Research agent task does not exist");
        }

        // Global lock order: run -> task -> completion/execution -> reservation -> cells -> child keys.
        RunRow run = lockRun(discoveredRunId);
        TaskRow task = lockTask(envelope.taskId(), discoveredRunId);
        ResearchAgentCompletionReplayRepository.CompletionRow committed =
                replayRepository.findByTask(task.id(), true);
        if (committed != null) return replayRepository.resolve(committed, validated);
        lockAndRejectOrphanExecution(task.id(), envelope.executionKey());
        ReservationRow reservation = lockReservation(task.id());
        List<TargetBinding> targets = targetBindings(task.targetBindingsJson());
        List<CellRow> cells = lockCells(task, targets);
        lockAndRejectChildKeyConflicts(run.id(), envelope);

        validateAuthority(run, task, reservation, targets, cells, envelope);
        Map<String, TrustedSource> trustedSources = trustedSources(task.executionContextJson());
        Map<String, TrustedEvidenceSource> trustedEvidence = validateEvidenceAuthority(
                run.id(), task.id(), envelope.evidence(), trustedSources);
        checkpoint(ResearchAgentCompletionFaultInjector.Stage.AFTER_LOCKS, 0);

        String executionId = Ids.newId();
        String completionId = Ids.newId();
        MergeOutcome plannedOutcome = planMergeOutcome(task, envelope, cells);
        BudgetLedger ledger = calculateBudget(
                reservation, envelope.budgetUsage(), envelope.evidence().size(),
                plannedOutcome.accepted().size(), plannedOutcome.rejected().size());
        ResearchAgentCompletionReceipt unsignedReceipt = buildReceipt(
                completionId, executionId, task, validated.digest(), envelope.evidence().size(),
                envelope.candidates().size(), plannedOutcome, ledger);
        Map<String, Object> receiptPayload = receiptPayload(unsignedReceipt, false);
        String receiptDigest = canonicalizer.domainSeparatedDigest(RECEIPT_DIGEST_DOMAIN, receiptPayload);
        ResearchAgentCompletionReceipt finalReceipt = new ResearchAgentCompletionReceipt(
                RECEIPT_SCHEMA, unsignedReceipt.completionId(), unsignedReceipt.executionId(), unsignedReceipt.taskId(),
                unsignedReceipt.completionDigest(), receiptDigest, false, unsignedReceipt.outcome(), unsignedReceipt.evidenceAppended(),
                unsignedReceipt.candidateCount(), unsignedReceipt.acceptedMerges(), unsignedReceipt.rejectedMerges(),
                unsignedReceipt.budget());
        String receiptJson = canonicalizer.canonicalJsonValue(receiptPayload(finalReceipt, true));

        jdbcTemplate.update("""
                insert into research_agent_execution(
                    id, research_agent_task_id, execution_key, lease_epoch, fencing_token,
                    worker_instance_id, status, termination_reason, usage_json, trace_digest
                ) values (?, ?, ?, ?, ?, ?, 'SUBMITTED', ?, ?, ?)
                """, executionId, task.id(), envelope.executionKey(), envelope.leaseEpoch(), envelope.fencingToken(),
                envelope.workerInstanceId(), envelope.terminationReason(),
                Json.write(objectMapper, ledger.consumed()), envelope.traceDigest());
        checkpoint(ResearchAgentCompletionFaultInjector.Stage.AFTER_EXECUTION_ANCHOR, 0);

        jdbcTemplate.update("""
                insert into research_agent_completion(
                    id, research_agent_task_id, execution_id, completion_key, schema_version,
                    envelope_digest, envelope_json, envelope_size_bytes, snapshot_digest,
                    worker_instance_id, lease_epoch, fencing_token, receipt_json, receipt_digest
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, completionId, task.id(), executionId, envelope.executionKey(), envelope.schemaVersion(),
                validated.digest(), validated.canonicalJson(),
                validated.canonicalJson().getBytes(StandardCharsets.UTF_8).length,
                envelope.taskSnapshotDigest(), envelope.workerInstanceId(), envelope.leaseEpoch(),
                envelope.fencingToken(), receiptJson, receiptDigest);
        checkpoint(ResearchAgentCompletionFaultInjector.Stage.AFTER_COMPLETION_ANCHOR, 0);

        Map<String, EvidencePersisted> evidenceByKey = appendEvidence(
                run.id(), completionId, envelope.evidence(), trustedEvidence);
        List<CandidatePersisted> candidates = appendCandidates(
                run.id(), task, completionId, executionId, envelope.candidates(), evidenceByKey);
        MergeOutcome mergeOutcome = task.candidateQuorum() == 2
                ? applyQuorumVerdictAndCas(run.id(), task, completionId, cells, plannedOutcome)
                : applyVerdictsAndCas(run.id(), task, completionId, candidates, cells, evidenceByKey);
        if (!plannedOutcome.equals(mergeOutcome)) {
            throw new IllegalStateException("Persisted merge outcome differs from the locked completion plan");
        }
        resolveCounterfactualRepairDecisions(run.id(), task, mergeOutcome);

        persistBudget(reservation, completionId, envelope.executionKey(), ledger);
        checkpoint(ResearchAgentCompletionFaultInjector.Stage.AFTER_BUDGET_FINALIZE, 0);

        int terminal = jdbcTemplate.update("""
                update research_agent_task
                set status = 'SUBMITTED', terminal_at = current_timestamp, terminal_reason = ?,
                    updated_at = current_timestamp
                where id = ? and status in ('CLAIMED', 'RUNNING')
                  and worker_instance_id = ? and lease_epoch = ? and fencing_token = ?
                  and lease_expires_at > current_timestamp
                """, envelope.terminationReason(), task.id(), envelope.workerInstanceId(),
                envelope.leaseEpoch(), envelope.fencingToken());
        if (terminal != 1) throw staleLease();
        checkpoint(ResearchAgentCompletionFaultInjector.Stage.AFTER_TASK_TERMINAL_UPDATE, 0);

        jdbcTemplate.update("""
                update research_agent_outbox set status = 'CANCELLED', updated_at = current_timestamp
                where research_agent_task_id = ? and status = 'READY'
                """, task.id());
        if (task.candidateQuorum() == 1 || !mergeOutcome.accepted().isEmpty() || !mergeOutcome.rejected().isEmpty()) {
            String bindingOwner = task.candidateQuorum() == 2 ? task.quorumGroupKey() : task.id();
            int releasedBindings = jdbcTemplate.update("""
                    update research_cell set active_task_id = null, updated_at = current_timestamp
                    where research_run_id = ? and active_task_id = ?
                    """, run.id(), bindingOwner);
            if (releasedBindings != targets.size()) {
                throw new BusinessException("RESEARCH_AGENT_TASK_SNAPSHOT_STALE",
                        "Not every task target binding could be released");
            }
        }

        return finalReceipt;
    }

    private RunRow lockRun(String runId) {
        RunRow row = jdbcTemplate.query("""
                select id, workspace_id, status, agent_execution_mode from research_run where id = ? for update
                """, rs -> rs.next() ? new RunRow(
                rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)) : null, runId);
        if (row == null) throw new BusinessException("RESEARCH_AGENT_RUN_NOT_FOUND", "Research run does not exist");
        return row;
    }

    private TaskRow lockTask(String taskId, String runId) {
        TaskRow row = jdbcTemplate.query("""
                select id, research_run_id, status, role, entity_id, branch_id, plan_revision, entity_set_version,
                       target_bindings_json, budget_json, execution_context_json,
                       snapshot_schema_version, snapshot_digest, worker_instance_id,
                       lease_epoch, fencing_token, lease_expires_at, logical_task_key, quorum_group_key,
                       candidate_quorum, candidate_slot,
                       case when lease_expires_at > current_timestamp then true else false end as lease_valid
                from research_agent_task where id = ? and research_run_id = ? for update
                """, rs -> rs.next() ? new TaskRow(
                rs.getString("id"), rs.getString("research_run_id"), rs.getString("status"), rs.getString("role"),
                rs.getString("entity_id"), rs.getString("branch_id"),
                rs.getInt("plan_revision"), rs.getInt("entity_set_version"), rs.getString("target_bindings_json"),
                rs.getString("budget_json"), rs.getString("execution_context_json"),
                rs.getString("snapshot_schema_version"), rs.getString("snapshot_digest"),
                 rs.getString("worker_instance_id"), rs.getInt("lease_epoch"), rs.getLong("fencing_token"),
                 rs.getString("logical_task_key"), rs.getString("quorum_group_key"),
                 rs.getInt("candidate_quorum"), rs.getInt("candidate_slot"),
                rs.getBoolean("lease_valid")) : null,
                taskId, runId);
        if (row == null) throw new BusinessException("RESEARCH_AGENT_TASK_NOT_FOUND", "Research agent task does not exist for run");
        return row;
    }

    private void lockAndRejectOrphanExecution(String taskId, String requestedKey) {
        List<String> keys = jdbcTemplate.query("""
                select execution_key from research_agent_execution
                where research_agent_task_id = ? order by execution_key for update
                """, (rs, rowNum) -> rs.getString(1), taskId);
        if (keys.isEmpty()) return;
        if (keys.contains(requestedKey)) {
            throw new BusinessException("RESEARCH_AGENT_COMPLETION_IDEMPOTENCY_CONFLICT",
                    "Execution key exists without a matching committed completion");
        }
        throw new BusinessException("RESEARCH_AGENT_COMPLETION_ALREADY_COMMITTED",
                "Research agent task already has another execution");
    }

    private ReservationRow lockReservation(String taskId) {
        ReservationRow row = jdbcTemplate.query("""
                select id, research_run_id, research_agent_task_id, reserved_json, consumed_json,
                       released_json, state, agent_completion_id, settlement_key
                from research_budget_reservation where research_agent_task_id = ? for update
                """, rs -> rs.next() ? new ReservationRow(
                rs.getString("id"), rs.getString("research_run_id"), rs.getString("research_agent_task_id"),
                readLongMap(rs.getString("reserved_json"), "RESEARCH_AGENT_COMPLETION_BUDGET_INVALID"),
                readLongMap(rs.getString("consumed_json"), "RESEARCH_AGENT_COMPLETION_BUDGET_INVALID"),
                readLongMap(rs.getString("released_json"), "RESEARCH_AGENT_COMPLETION_BUDGET_INVALID"),
                rs.getString("state"), rs.getString("agent_completion_id"), rs.getString("settlement_key")) : null,
                taskId);
        if (row == null) {
            throw new BusinessException("RESEARCH_AGENT_COMPLETION_BUDGET_EXCEEDED",
                    "Atomic completion requires its coordinator reservation");
        }
        return row;
    }

    private List<CellRow> lockCells(TaskRow task, List<TargetBinding> targets) {
        List<String> keys = targets.stream().map(TargetBinding::cellKey).distinct().toList();
        String placeholders = String.join(",", Collections.nCopies(keys.size(), "?"));
        List<Object> parameters = new ArrayList<>();
        parameters.add(task.runId());
        parameters.addAll(keys);
        List<CellRow> result = jdbcTemplate.query("""
                select id, cell_key, cell_status, cell_version, plan_revision, entity_set_version,
                       active_task_id, lease_epoch, fencing_token
                from research_cell where research_run_id = ? and cell_key in (""" + placeholders + ") "
                        + "order by cast(cell_key as binary), id for update",
                (rs, rowNum) -> new CellRow(
                        rs.getString("id"), rs.getString("cell_key"), rs.getString("cell_status"),
                        rs.getInt("cell_version"), rs.getInt("plan_revision"), rs.getInt("entity_set_version"),
                        rs.getString("active_task_id"), rs.getInt("lease_epoch"), rs.getLong("fencing_token")),
                parameters.toArray());
        if (result.size() != keys.size()) throw snapshotStale("Target cell does not exist");
        return List.copyOf(result);
    }

    private void lockAndRejectChildKeyConflicts(String runId, ResearchAgentCompletionEnvelope envelope) {
        List<String> evidenceKeys = envelope.evidence().stream()
                .map(ResearchAgentCompletionEnvelope.Evidence::evidenceKey).sorted().toList();
        if (lockKeyCount("source_evidence", "evidence_key", runId, evidenceKeys) > 0) {
            throw new BusinessException("RESEARCH_AGENT_COMPLETION_EVIDENCE_CONFLICT",
                    "Evidence stable key is already owned by legacy data or another completion");
        }
        List<String> candidateKeys = envelope.candidates().stream()
                .map(ResearchAgentCompletionEnvelope.Candidate::candidateKey).sorted().toList();
        if (lockKeyCount("research_agent_candidate", "idempotency_key", runId, candidateKeys) > 0) {
            throw new BusinessException("RESEARCH_AGENT_COMPLETION_CANDIDATE_CONFLICT",
                    "Candidate stable key is already owned by legacy data or another completion");
        }
        List<String> mergeKeys = candidateKeys.stream().map(this::mergeKey).sorted().toList();
        if (lockKeyCount("research_cell_merge", "merge_key", runId, mergeKeys) > 0) {
            throw new BusinessException("RESEARCH_AGENT_COMPLETION_MERGE_CONFLICT",
                    "Merge stable key is already owned by legacy data or another completion");
        }
    }

    private int lockKeyCount(String table, String keyColumn, String runId, List<String> keys) {
        if (keys.isEmpty()) return 0;
        String placeholders = String.join(",", Collections.nCopies(keys.size(), "?"));
        List<Object> parameters = new ArrayList<>();
        parameters.add(runId);
        parameters.addAll(keys);
        return jdbcTemplate.query(
                "select " + keyColumn + " from " + table + " where research_run_id = ? and "
                        + keyColumn + " in (" + placeholders + ") order by " + keyColumn + " for update",
                rs -> {
                    int count = 0;
                    while (rs.next()) count++;
                    return count;
                }, parameters.toArray());
    }

    private void validateAuthority(
            RunRow run,
            TaskRow task,
            ReservationRow reservation,
            List<TargetBinding> targets,
            List<CellRow> cells,
            ResearchAgentCompletionEnvelope envelope
    ) {
        if (!"INCREMENTAL_V1".equals(run.executionMode())) {
            throw new BusinessException("RESEARCH_AGENT_RUN_MODE_INVALID",
                    "Atomic completion requires INCREMENTAL_V1");
        }
        if (TERMINAL_RUN.contains(run.status())) {
            throw new BusinessException("RESEARCH_AGENT_RUN_TERMINAL", "Research run is terminal");
        }
        boolean v1 = SNAPSHOT_SCHEMA.equals(task.snapshotSchemaVersion()) && "DEEP_CELL".equals(task.role());
        boolean v2 = SNAPSHOT_SCHEMA_V2.equals(task.snapshotSchemaVersion())
                && ("DEEP_CELL".equals(task.role())
                || (task.candidateQuorum() == 2 && "COUNTERFACTUAL".equals(task.role()))
                || isCounterfactualRepairTask(task));
        if (!v1 && !v2) {
            throw new BusinessException("RESEARCH_AGENT_COMPLETION_INVALID",
                    "Task is not an authoritative atomic research task");
        }
        if (!Set.of("CLAIMED", "RUNNING").contains(task.status())
                || !envelope.workerInstanceId().equals(task.workerInstanceId())
                || envelope.leaseEpoch() != task.leaseEpoch()
                || envelope.fencingToken() != task.fencingToken()
                || !task.leaseValid()) {
            throw staleLease();
        }
        if (!envelope.taskSnapshotDigest().equals(task.snapshotDigest())) {
            throw snapshotStale("Task snapshot digest is stale");
        }
        ResearchAgentTaskSnapshotCanonicalizer.CanonicalSnapshot canonicalSnapshot = snapshotCanonicalizer.canonicalize(
                new ResearchAgentTaskSnapshotCanonicalizer.SnapshotInput(
                        task.snapshotSchemaVersion(), task.id(), task.runId(), run.workspaceId(), task.role(), task.entityId(), task.branchId(),
                        task.planRevision(), task.entitySetVersion(), task.leaseEpoch(), task.fencingToken(),
                        task.targetBindingsJson(), task.budgetJson(), task.executionContextJson(),
                        task.logicalTaskKey(), task.quorumGroupKey(), task.candidateQuorum(), task.candidateSlot(),
                        task.candidateQuorum() == 2));
        if (!canonicalSnapshot.digest().equals(task.snapshotDigest())
                || !canonicalSnapshot.digest().equals(envelope.taskSnapshotDigest())) {
            throw snapshotStale("Server-persisted task snapshot context has drifted");
        }
        if (!"RESERVED".equals(reservation.state()) || reservation.agentCompletionId() != null
                || !run.id().equals(reservation.runId()) || !task.id().equals(reservation.taskId())) {
            throw new BusinessException("RESEARCH_AGENT_COMPLETION_BUDGET_EXCEEDED",
                    "Budget reservation is not available for this completion");
        }
        Map<String, Long> snapshotBudget = readLongMap(task.budgetJson(), "RESEARCH_AGENT_TASK_SNAPSHOT_STALE");
        if (!snapshotBudget.equals(reservation.reserved())) {
            throw snapshotStale("Task budget snapshot differs from its reservation");
        }
        Map<String, Integer> expected = new HashMap<>();
        targets.forEach(target -> expected.put(target.cellKey(), target.expectedVersion()));
        if (expected.size() != targets.size() || cells.size() != targets.size()) {
            throw snapshotStale("Task target bindings are duplicated or incomplete");
        }
        Set<String> candidateCells = new HashSet<>();
        for (ResearchAgentCompletionEnvelope.Candidate candidate : envelope.candidates()) {
            if (!candidateCells.add(candidate.cellKey()) || !expected.containsKey(candidate.cellKey())
                    || expected.get(candidate.cellKey()) != candidate.baseCellVersion()) {
                throw snapshotStale("Candidate is outside the task target snapshot");
            }
        }
        for (CellRow cell : cells) {
            int expectedVersion = expected.get(cell.cellKey());
            String bindingOwner = task.candidateQuorum() == 2 ? task.quorumGroupKey() : task.id();
            if (cell.cellVersion() != expectedVersion || cell.planRevision() != task.planRevision()
                    || cell.entitySetVersion() != task.entitySetVersion()
                    || !bindingOwner.equals(cell.activeTaskId())
                    || (task.candidateQuorum() == 1 && (cell.leaseEpoch() != task.leaseEpoch()
                    || cell.fencingToken() != task.fencingToken())) || "FROZEN".equals(cell.status())
                    || "VERIFIED".equals(cell.status())) {
                throw snapshotStale("Canonical target cell no longer matches the task snapshot");
            }
        }
    }

    private Map<String, TrustedSource> trustedSources(String contextJson) {
        try {
            Map<String, Object> context = objectMapper.readValue(contextJson, new TypeReference<>() { });
            Object policy = context.get("source_policy");
            if (!(policy instanceof Map<?, ?> sourcePolicy)
                    || !(sourcePolicy.get("source_scope") instanceof List<?> sourceScope)) {
                throw invalidEvidence("Task has no trusted workspace source scope");
            }
            Map<String, TrustedSource> result = new HashMap<>();
            for (Object raw : sourceScope) {
                if (!(raw instanceof Map<?, ?> source)) continue;
                String id = nfc(stringValue(source.get("source_id")));
                String title = nfc(stringValue(source.containsKey("source_title")
                        ? source.get("source_title") : source.get("title")));
                String sample = nfc(stringValue(source.get("sample_text")));
                if (!id.isBlank() && !title.isBlank() && !sample.isBlank()) {
                    if (result.putIfAbsent(id, new TrustedSource(id, title, sample)) != null) {
                        throw invalidEvidence("Trusted source ids collide after NFC normalization");
                    }
                }
            }
            if (result.isEmpty()) throw invalidEvidence("Task has no trusted workspace source sample");
            return Map.copyOf(result);
        } catch (JsonProcessingException exception) {
            throw invalidEvidence("Task trusted source scope is invalid");
        }
    }

    private Map<String, TrustedEvidenceSource> validateEvidenceAuthority(
            String runId,
            String taskId,
            List<ResearchAgentCompletionEnvelope.Evidence> evidence,
            Map<String, TrustedSource> trustedSources
    ) {
        Map<String, TrustedEvidenceSource> result = new HashMap<>();
        for (ResearchAgentCompletionEnvelope.Evidence item : evidence) {
            if ("EXTERNAL_ARCHIVED".equals(item.snapshotStatus())) {
                ExternalArchive archive = loadArchivedExternalSnapshot(runId, taskId, item);
                if (!archive.sourceTitle().equals(item.sourceTitle())
                        || !archive.contentText().contains(item.quoteText())
                        || !sha256Hex(archive.contentText()).equalsIgnoreCase(archive.contentSha256())) {
                    throw invalidEvidence("External archived evidence does not match its server-persisted snapshot");
                }
                result.put(item.evidenceKey(), new TrustedEvidenceSource(
                        archive.sourceId(), archive.sourceTitle(), archive.sourceUrl(), archive.provider(),
                        archive.adapter(), "EXTERNAL_ARCHIVED", archive.snapshotKey()));
                continue;
            }
            TrustedSource source = trustedSources.get(item.sourceId());
            if (source == null || !source.title().equals(item.sourceTitle())
                    || !source.sampleText().contains(item.quoteText())) {
                throw new BusinessException("RESEARCH_AGENT_COMPLETION_EVIDENCE_UNGROUNDED",
                        "Completion evidence is not grounded in the server-persisted source scope");
            }
            result.put(item.evidenceKey(), new TrustedEvidenceSource(
                    source.id(), source.title(), null, "workspace", "workspace", "WORKSPACE", null));
        }
        return Map.copyOf(result);
    }

    private ExternalArchive loadArchivedExternalSnapshot(
            String runId,
            String taskId,
            ResearchAgentCompletionEnvelope.Evidence item
    ) {
        ExternalArchive archive = jdbcTemplate.query("""
                select source_id, source_title, source_url, provider, adapter, snapshot_key,
                       content_text, content_sha256, archive_status
                from research_external_snapshot
                where research_run_id = ? and research_agent_task_id = ?
                  and window_id = ? and source_id = ?
                for update
                """, rs -> rs.next() ? new ExternalArchive(
                rs.getString("source_id"), rs.getString("source_title"), rs.getString("source_url"),
                rs.getString("provider"), rs.getString("adapter"), rs.getString("snapshot_key"),
                rs.getString("content_text"), rs.getString("content_sha256"), rs.getString("archive_status")) : null,
                runId, taskId, item.windowId(), item.sourceId());
        if (archive == null || !"ARCHIVED".equals(archive.archiveStatus())) {
            throw new BusinessException("RESEARCH_AGENT_COMPLETION_EXTERNAL_ARCHIVE_REQUIRED",
                    "External completion evidence requires a server-persisted archived snapshot");
        }
        return archive;
    }

    private String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private Map<String, EvidencePersisted> appendEvidence(
            String runId,
            String completionId,
            List<ResearchAgentCompletionEnvelope.Evidence> evidence,
            Map<String, TrustedEvidenceSource> trustedEvidence
    ) {
        Map<String, EvidencePersisted> result = new HashMap<>();
        int ordinal = 0;
        for (ResearchAgentCompletionEnvelope.Evidence item : evidence.stream()
                .sorted(Comparator.comparing(ResearchAgentCompletionEnvelope.Evidence::evidenceKey)).toList()) {
            TrustedEvidenceSource authority = trustedEvidence.get(item.evidenceKey());
            if (authority == null) throw new IllegalStateException("Validated evidence authority is missing");
            if (!authority.sourceId().equals(item.sourceId())) {
                throw new IllegalStateException("Validated evidence source identity is inconsistent");
            }
            String evidenceId = Ids.newId();
            BigDecimal legacySupport = legacyScore(item.supportScorePpm());
            BigDecimal legacyConflict = legacyScore(item.conflictScorePpm());
            Map<String, Object> content = rowContent();
            content.put("id", evidenceId);
            content.put("research_run_id", runId);
            content.put("agent_completion_id", completionId);
            content.put("evidence_key", item.evidenceKey());
            content.put("window_id", item.windowId());
            content.put("source_id", item.sourceId());
            content.put("source_title", authority.sourceTitle());
            content.put("source_url", authority.sourceUrl());
            content.put("provider", authority.provider());
            content.put("adapter", authority.adapter());
            content.put("search_query", item.searchQuery());
            content.put("read_focus", item.readFocus());
            content.put("quote_text", item.quoteText());
            content.put("claim_text", item.claimText());
            content.put("relation_type", item.relationType());
            content.put("support_score", legacySupport.toPlainString());
            content.put("conflict_score", legacyConflict.toPlainString());
            content.put("support_score_ppm", item.supportScorePpm());
            content.put("conflict_score_ppm", item.conflictScorePpm());
            content.put("snapshot_status", authority.snapshotStatus());
            content.put("snapshot_key", authority.snapshotKey());
            String contentDigest = canonicalizer.domainSeparatedDigest(EVIDENCE_DIGEST_DOMAIN, content);
            try {
                jdbcTemplate.update("""
                        insert into source_evidence(
                            id, research_run_id, evidence_key, window_id, source_id, source_title,
                            source_url, provider, adapter, search_query, read_focus, quote_text, claim_text,
                            relation_type, support_score, conflict_score, snapshot_status, snapshot_key,
                            agent_completion_id, content_digest, support_score_ppm, conflict_score_ppm
                        ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, evidenceId, runId, item.evidenceKey(), item.windowId(), item.sourceId(),
                        authority.sourceTitle(), authority.sourceUrl(), authority.provider(), authority.adapter(),
                        item.searchQuery(), item.readFocus(),
                        item.quoteText(), item.claimText(), item.relationType(), legacySupport, legacyConflict,
                        authority.snapshotStatus(), authority.snapshotKey(), completionId, contentDigest,
                        item.supportScorePpm(), item.conflictScorePpm());
            } catch (DataIntegrityViolationException exception) {
                throw new BusinessException("RESEARCH_AGENT_COMPLETION_EVIDENCE_CONFLICT",
                        "Evidence stable key raced with different content");
            }
            result.put(item.evidenceKey(), new EvidencePersisted(
                    evidenceId, item.evidenceKey(), item.sourceId(), item.claimText(), item.relationType(), contentDigest));
            checkpoint(ResearchAgentCompletionFaultInjector.Stage.AFTER_NTH_EVIDENCE, ++ordinal);
        }
        return Map.copyOf(result);
    }

    private List<CandidatePersisted> appendCandidates(
            String runId,
            TaskRow task,
            String completionId,
            String executionId,
            List<ResearchAgentCompletionEnvelope.Candidate> candidates,
            Map<String, EvidencePersisted> evidenceByKey
    ) {
        List<CandidatePersisted> result = new ArrayList<>();
        int ordinal = 0;
        for (ResearchAgentCompletionEnvelope.Candidate item : candidates.stream()
                .sorted(Comparator.comparing(ResearchAgentCompletionEnvelope.Candidate::candidateKey)).toList()) {
            String candidateId = Ids.newId();
            List<String> evidenceKeys = item.evidenceKeys().stream().sorted().toList();
            List<EvidencePersisted> boundEvidence = evidenceKeys.stream().map(evidenceByKey::get).toList();
            List<String> sourceDomains = boundEvidence.stream().map(EvidencePersisted::sourceId).distinct().sorted().toList();
            BigDecimal legacyConfidence = legacyScore(item.confidencePpm());
            Map<String, Object> content = rowContent();
            content.put("id", candidateId);
            content.put("research_run_id", runId);
            content.put("agent_completion_id", completionId);
            content.put("research_agent_execution_id", executionId);
            content.put("task_id", task.id());
            content.put("execution_id", executionId);
            content.put("idempotency_key", item.candidateKey());
            content.put("cell_key", item.cellKey());
            content.put("base_cell_version", item.baseCellVersion());
            content.put("plan_revision", task.planRevision());
            content.put("entity_set_version", task.entitySetVersion());
            content.put("lease_epoch", task.leaseEpoch());
            content.put("fencing_token", task.fencingToken());
            content.put("candidate_value", item.candidateValue());
            content.put("evidence_ids", evidenceKeys);
            content.put("confidence_score", legacyConfidence.toPlainString());
            content.put("confidence_score_ppm", item.confidencePpm());
            String blindDigest = null;
            if (task.candidateQuorum() == 2) {
                Map<String, Object> blindContent = rowContent();
                blindContent.put("cell_key", item.cellKey());
                blindContent.put("base_cell_version", item.baseCellVersion());
                blindContent.put("candidate_value", nfc(item.candidateValue()));
                blindContent.put("evidence_ids", evidenceKeys);
                blindContent.put("source_domains", sourceDomains);
                blindDigest = canonicalizer.domainSeparatedDigest(BLIND_DIGEST_DOMAIN, blindContent);
                content.put("quorum_group_key", task.quorumGroupKey());
                content.put("candidate_slot", task.candidateSlot());
                content.put("source_domains", sourceDomains);
                content.put("blind_digest", blindDigest);
            }
            String contentDigest = canonicalizer.domainSeparatedDigest(CANDIDATE_DIGEST_DOMAIN, content);
            try {
                jdbcTemplate.update("""
                        insert into research_agent_candidate(
                            id, research_run_id, task_id, execution_id, idempotency_key, cell_key,
                            base_cell_version, plan_revision, entity_set_version, lease_epoch, fencing_token,
                            candidate_value, evidence_ids_json, confidence_score,
                            agent_completion_id, content_digest, research_agent_execution_id, confidence_score_ppm,
                            quorum_group_key, candidate_slot, source_domains_json, blind_digest
                        ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, candidateId, runId, task.id(), executionId, item.candidateKey(), item.cellKey(),
                        item.baseCellVersion(), task.planRevision(), task.entitySetVersion(), task.leaseEpoch(),
                        task.fencingToken(), item.candidateValue(), Json.write(objectMapper, evidenceKeys),
                        legacyConfidence, completionId, contentDigest, executionId, item.confidencePpm(),
                        task.quorumGroupKey(), task.candidateSlot(),
                        task.candidateQuorum() == 2 ? Json.write(objectMapper, sourceDomains) : null, blindDigest);
            } catch (DataIntegrityViolationException exception) {
                throw new BusinessException("RESEARCH_AGENT_COMPLETION_CANDIDATE_CONFLICT",
                        "Candidate stable key raced with different content");
            }
            result.add(new CandidatePersisted(candidateId, item, boundEvidence, contentDigest));
            checkpoint(ResearchAgentCompletionFaultInjector.Stage.AFTER_NTH_CANDIDATE, ++ordinal);
        }
        return List.copyOf(result);
    }

    private MergeOutcome planMergeOutcome(
            TaskRow task,
            ResearchAgentCompletionEnvelope envelope,
            List<CellRow> cells
    ) {
        if (task.candidateQuorum() == 2) return planQuorumOutcome(task, envelope);
        Map<String, ResearchAgentCompletionEnvelope.Evidence> evidenceByKey = new HashMap<>();
        envelope.evidence().forEach(item -> evidenceByKey.put(item.evidenceKey(), item));
        Set<String> lockedCells = new HashSet<>();
        cells.forEach(cell -> lockedCells.add(cell.cellKey()));
        List<ResearchAgentCompletionReceipt.MergeReceipt> accepted = new ArrayList<>();
        List<ResearchAgentCompletionReceipt.MergeReceipt> rejected = new ArrayList<>();
        for (ResearchAgentCompletionEnvelope.Candidate candidate : envelope.candidates().stream()
                .sorted(Comparator.comparing(
                        ResearchAgentCompletionEnvelope.Candidate::cellKey, ResearchAgentBinaryOrder.UTF8)).toList()) {
            if (!lockedCells.contains(candidate.cellKey())) {
                throw snapshotStale("Candidate target is not locked by its task snapshot");
            }
            boolean supported = candidate.evidenceKeys().stream().map(evidenceByKey::get).allMatch(evidence ->
                    evidence != null && "SUPPORTS".equals(evidence.relationType())
                            && candidate.candidateValue().equals(evidence.claimText()));
            ResearchAgentCompletionReceipt.MergeReceipt receipt =
                    new ResearchAgentCompletionReceipt.MergeReceipt(
                            candidate.cellKey(), candidate.baseCellVersion(),
                            supported ? candidate.baseCellVersion() + 1 : candidate.baseCellVersion(),
                            supported ? "ACCEPTED" : "REJECTED",
                            supported ? "VERIFIED_AND_VERSION_MATCHED" : "NOT_ENOUGH_INFO");
            if (supported) accepted.add(receipt); else rejected.add(receipt);
        }
        return new MergeOutcome(List.copyOf(accepted), List.copyOf(rejected));
    }

    private MergeOutcome planQuorumOutcome(TaskRow task, ResearchAgentCompletionEnvelope envelope) {
        List<QuorumCandidate> existing = loadQuorumCandidates(task);
        if (existing.isEmpty()) return new MergeOutcome(List.of(), List.of());
        if (existing.size() != 1 || envelope.candidates().size() != 1) {
            throw new BusinessException("RESEARCH_AGENT_COMPLETION_QUORUM_CONFLICT", "Quorum group has an invalid candidate cardinality");
        }
        ResearchAgentCompletionEnvelope.Candidate candidate = envelope.candidates().get(0);
        Map<String, ResearchAgentCompletionEnvelope.Evidence> evidence = new HashMap<>();
        envelope.evidence().forEach(item -> evidence.put(item.evidenceKey(), item));
        List<String> domains = candidate.evidenceKeys().stream().map(evidence::get)
                .filter(java.util.Objects::nonNull).map(ResearchAgentCompletionEnvelope.Evidence::sourceId)
                .distinct().sorted().toList();
        boolean supported = candidate.evidenceKeys().stream().map(evidence::get).allMatch(item ->
                item != null && "SUPPORTS".equals(item.relationType())
                        && candidate.candidateValue().equals(item.claimText()));
        QuorumCandidate current = new QuorumCandidate(
                null, task.id(), null, candidate.candidateKey(), candidate.cellKey(), candidate.baseCellVersion(),
                candidate.candidateValue(), candidate.confidencePpm(), domains,
                candidate.evidenceKeys().stream().sorted().toList(), null, task.candidateSlot(), supported);
        return quorumOutcome(List.of(existing.get(0), current));
    }

    private MergeOutcome quorumOutcome(List<QuorumCandidate> candidates) {
        if (candidates.size() != 2) return new MergeOutcome(List.of(), List.of());
        QuorumCandidate first = candidates.get(0);
        QuorumCandidate second = candidates.get(1);
        String reason = null;
        if (first.slot() == second.slot() || first.taskId().equals(second.taskId())) {
            reason = "QUORUM_EXECUTION_NOT_INDEPENDENT";
        } else if (!first.cellKey().equals(second.cellKey()) || first.baseCellVersion() != second.baseCellVersion()) {
            reason = "QUORUM_TARGET_CONFLICT";
        } else if (!nfc(first.value()).equals(nfc(second.value()))) {
            reason = "QUORUM_VALUE_CONFLICT";
        } else if (!first.supported() || !second.supported()
                || first.domains().isEmpty() || second.domains().isEmpty()) {
            reason = "QUORUM_PROVENANCE_MISSING";
        } else if (!Collections.disjoint(first.domains(), second.domains())) {
            reason = "QUORUM_SOURCE_DOMAIN_NOT_INDEPENDENT";
        }
        ResearchAgentCompletionReceipt.MergeReceipt receipt = new ResearchAgentCompletionReceipt.MergeReceipt(
                first.cellKey(), first.baseCellVersion(), reason == null ? first.baseCellVersion() + 1 : first.baseCellVersion(),
                reason == null ? "ACCEPTED" : "REJECTED",
                reason == null ? "QUORUM_VERIFIED_AND_VERSION_MATCHED" : reason);
        return reason == null ? new MergeOutcome(List.of(receipt), List.of()) : new MergeOutcome(List.of(), List.of(receipt));
    }

    private List<QuorumCandidate> loadQuorumCandidates(TaskRow task) {
        List<QuorumCandidate> rows = jdbcTemplate.query("""
                select id, task_id, research_agent_execution_id, idempotency_key, cell_key, base_cell_version,
                       candidate_value, confidence_score_ppm, source_domains_json, evidence_ids_json,
                       blind_digest, candidate_slot
                from research_agent_candidate
                where research_run_id = ? and quorum_group_key = ?
                order by candidate_slot for update
                """, (rs, rowNum) -> {
            List<String> evidenceKeys = readStringList(rs.getString("evidence_ids_json"));
            return new QuorumCandidate(
                    rs.getString("id"), rs.getString("task_id"), rs.getString("research_agent_execution_id"),
                    rs.getString("idempotency_key"), rs.getString("cell_key"), rs.getInt("base_cell_version"),
                    rs.getString("candidate_value"), rs.getInt("confidence_score_ppm"),
                    readStringList(rs.getString("source_domains_json")), evidenceKeys,
                    rs.getString("blind_digest"), rs.getInt("candidate_slot"), false);
        }, task.runId(), task.quorumGroupKey());
        return rows.stream().map(item -> new QuorumCandidate(
                item.id(), item.taskId(), item.executionId(), item.candidateKey(), item.cellKey(),
                item.baseCellVersion(), item.value(), item.confidencePpm(), item.domains(), item.evidenceKeys(),
                item.blindDigest(), item.slot(), persistedEvidenceSupports(task.runId(), item.evidenceKeys(), item.value())))
                .toList();
    }

    private boolean persistedEvidenceSupports(String runId, List<String> keys, String value) {
        if (keys.isEmpty()) return false;
        String placeholders = String.join(",", Collections.nCopies(keys.size(), "?"));
        List<Object> parameters = new ArrayList<>();
        parameters.add(runId);
        parameters.addAll(keys);
        List<Boolean> support = jdbcTemplate.query("""
                select relation_type, claim_text from source_evidence
                where research_run_id = ? and evidence_key in (""" + placeholders + ") order by evidence_key",
                (rs, rowNum) -> "SUPPORTS".equals(rs.getString(1)) && value.equals(rs.getString(2)),
                parameters.toArray());
        return support.size() == keys.size() && support.stream().allMatch(Boolean::booleanValue);
    }

    private MergeOutcome applyQuorumVerdictAndCas(
            String runId, TaskRow task, String completionId, List<CellRow> cells, MergeOutcome planned
    ) {
        if (planned.accepted().isEmpty() && planned.rejected().isEmpty()) return planned;
        List<QuorumCandidate> candidates = loadQuorumCandidates(task);
        MergeOutcome durable = quorumOutcome(candidates);
        if (!planned.equals(durable)) throw new IllegalStateException("Durable quorum decision differs from its locked plan");
        QuorumCandidate chosen = candidates.stream().sorted(Comparator
                .comparing((QuorumCandidate item) -> item.blindDigest() == null ? "" : item.blindDigest())
                .thenComparing(QuorumCandidate::id)).findFirst().orElseThrow();
        CellRow cell = cells.stream().filter(item -> item.cellKey().equals(chosen.cellKey())).findFirst().orElseThrow();
        ResearchAgentCompletionReceipt.MergeReceipt decision = !durable.accepted().isEmpty()
                ? durable.accepted().get(0) : durable.rejected().get(0);
        List<String> evidenceKeys = durable.accepted().isEmpty() ? List.of() : candidates.stream()
                .flatMap(item -> item.evidenceKeys().stream()).distinct().sorted().toList();
        String mergeKey = quorumMergeKey(task.quorumGroupKey());
        if (!durable.accepted().isEmpty()) {
            int confidence = candidates.stream().mapToInt(QuorumCandidate::confidencePpm).min().orElse(0);
            int updated = jdbcTemplate.update("""
                    update research_cell
                    set candidate_value = ?, cell_status = 'VERIFIED', confidence_score = ?,
                        confidence_score_ppm = ?, evidence_refs_json = ?,
                        last_verifier_decision = 'MERGE_GATE:QUORUM_VERIFIED_AND_VERSION_MATCHED',
                        cell_version = cell_version + 1, last_merge_id = ?, updated_at = current_timestamp
                    where id = ? and research_run_id = ? and cell_key = ? and cell_status <> 'FROZEN'
                      and cell_version = ? and plan_revision = ? and entity_set_version = ?
                      and active_task_id = ?
                    """, chosen.value(), legacyScore(confidence), confidence, Json.write(objectMapper, evidenceKeys),
                    mergeKey, cell.id(), runId, cell.cellKey(), chosen.baseCellVersion(), task.planRevision(),
                    task.entitySetVersion(), task.quorumGroupKey());
            if (updated != 1) throw snapshotStale("Canonical cell quorum CAS lost its group fence");
            checkpoint(ResearchAgentCompletionFaultInjector.Stage.AFTER_FIRST_CELL_CAS, 1);
        }
        String mergeId = Ids.newId();
        Map<String, Object> mergeContent = rowContent();
        mergeContent.put("id", mergeId); mergeContent.put("research_run_id", runId);
        mergeContent.put("agent_completion_id", completionId); mergeContent.put("candidate_id", chosen.id());
        mergeContent.put("merge_key", mergeKey); mergeContent.put("cell_key", chosen.cellKey());
        mergeContent.put("expected_cell_version", chosen.baseCellVersion());
        mergeContent.put("result_cell_version", decision.toVersion());
        mergeContent.put("verdict", durable.accepted().isEmpty() ? "NOT_ENOUGH_INFO" : "SUPPORTS");
        mergeContent.put("decision", decision.decision()); mergeContent.put("reason_code", decision.reasonCode());
        mergeContent.put("accepted_evidence_ids", evidenceKeys);
        String mergeDigest = canonicalizer.domainSeparatedDigest(MERGE_DIGEST_DOMAIN, mergeContent);
        jdbcTemplate.update("""
                insert into research_cell_merge(
                    id, research_run_id, candidate_id, merge_key, cell_key, expected_cell_version,
                    result_cell_version, verdict, decision, reason_code, accepted_evidence_ids_json,
                    agent_completion_id, content_digest)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, mergeId, runId, chosen.id(), mergeKey, chosen.cellKey(), chosen.baseCellVersion(),
                decision.toVersion(), durable.accepted().isEmpty() ? "NOT_ENOUGH_INFO" : "SUPPORTS",
                decision.decision(), decision.reasonCode(), Json.write(objectMapper, evidenceKeys), completionId, mergeDigest);
        if (!durable.accepted().isEmpty()) {
            appendCellEvidence(runId, completionId, cell, chosen.id(), mergeId, mergeKey,
                    loadPersistedEvidence(runId, evidenceKeys));
        } else {
            Map<String, Object> notes = rowContent();
            notes.put("quorum_group_key", task.quorumGroupKey());
            notes.put("completion_id", completionId);
            notes.put("candidate_slots", candidates.stream().map(QuorumCandidate::slot).sorted().toList());
            jdbcTemplate.update("""
                    insert into research_verifier_decision(
                        id, research_run_id, branch_id, decision_scope, decision_type, reason_code,
                        target_id, evidence_ids_json, action_text, decision_status, notes_json)
                    values (?, ?, ?, 'CELL', 'QUORUM_REPAIR_REQUIRED', ?, ?, ?,
                            'COUNTERFACTUAL_REPAIR', 'OPEN', ?)
                    """, Ids.newId(), runId, task.branchId(), decision.reasonCode(), chosen.cellKey(),
                    Json.write(objectMapper, candidates.stream().flatMap(item -> item.evidenceKeys().stream())
                            .distinct().sorted().toList()), Json.write(objectMapper, notes));
        }
        return durable;
    }

    private List<EvidencePersisted> loadPersistedEvidence(String runId, List<String> keys) {
        if (keys.isEmpty()) return List.of();
        String placeholders = String.join(",", Collections.nCopies(keys.size(), "?"));
        List<Object> parameters = new ArrayList<>(); parameters.add(runId); parameters.addAll(keys);
        return jdbcTemplate.query("""
                select id, evidence_key, source_id, claim_text, relation_type, content_digest
                from source_evidence where research_run_id = ? and evidence_key in (""" + placeholders + ") order by evidence_key",
                (rs, rowNum) -> new EvidencePersisted(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6)), parameters.toArray());
    }

    private MergeOutcome applyVerdictsAndCas(
            String runId,
            TaskRow task,
            String completionId,
            List<CandidatePersisted> candidates,
            List<CellRow> cells,
            Map<String, EvidencePersisted> evidenceByKey
    ) {
        Map<String, CellRow> cellsByKey = new HashMap<>();
        cells.forEach(cell -> cellsByKey.put(cell.cellKey(), cell));
        List<ResearchAgentCompletionReceipt.MergeReceipt> accepted = new ArrayList<>();
        List<ResearchAgentCompletionReceipt.MergeReceipt> rejected = new ArrayList<>();
        int acceptedOrdinal = 0;
        for (CandidatePersisted persisted : candidates.stream()
                .sorted(Comparator.comparing(item -> item.envelope().cellKey(), ResearchAgentBinaryOrder.UTF8)).toList()) {
            ResearchAgentCompletionEnvelope.Candidate candidate = persisted.envelope();
            CellRow cell = cellsByKey.get(candidate.cellKey());
            boolean supported = persisted.evidence().stream().allMatch(evidence ->
                    "SUPPORTS".equals(evidence.relationType())
                            && candidate.candidateValue().equals(evidence.claimText()));
            String decision = supported ? "ACCEPTED" : "REJECTED";
            String reason = supported ? "VERIFIED_AND_VERSION_MATCHED" : "NOT_ENOUGH_INFO";
            String verdict = supported ? "SUPPORTS" : "NOT_ENOUGH_INFO";
            int resultVersion = candidate.baseCellVersion();
            List<String> acceptedEvidenceKeys = supported
                    ? persisted.evidence().stream().map(EvidencePersisted::evidenceKey).sorted().toList()
                    : List.of();

            if (supported) {
                int updated = jdbcTemplate.update("""
                        update research_cell
                        set candidate_value = ?, cell_status = 'VERIFIED', confidence_score = ?,
                            confidence_score_ppm = ?, evidence_refs_json = ?,
                            last_verifier_decision = 'MERGE_GATE:VERIFIED_AND_VERSION_MATCHED',
                            cell_version = cell_version + 1, last_merge_id = ?, updated_at = current_timestamp
                        where id = ? and research_run_id = ? and cell_key = ? and cell_status <> 'FROZEN'
                          and cell_version = ? and plan_revision = ? and entity_set_version = ?
                          and active_task_id = ? and lease_epoch = ? and fencing_token = ?
                        """, candidate.candidateValue(), legacyScore(candidate.confidencePpm()),
                        candidate.confidencePpm(), Json.write(objectMapper, acceptedEvidenceKeys),
                        mergeKey(candidate.candidateKey()), cell.id(), runId, cell.cellKey(),
                        candidate.baseCellVersion(), task.planRevision(), task.entitySetVersion(), task.id(),
                        task.leaseEpoch(), task.fencingToken());
                if (updated != 1) throw snapshotStale("Canonical cell CAS lost its snapshot fence");
                resultVersion++;
                checkpoint(ResearchAgentCompletionFaultInjector.Stage.AFTER_FIRST_CELL_CAS, ++acceptedOrdinal);
            }

            String mergeId = Ids.newId();
            String mergeKey = mergeKey(candidate.candidateKey());
            Map<String, Object> mergeContent = rowContent();
            mergeContent.put("id", mergeId);
            mergeContent.put("research_run_id", runId);
            mergeContent.put("agent_completion_id", completionId);
            mergeContent.put("candidate_id", persisted.id());
            mergeContent.put("merge_key", mergeKey);
            mergeContent.put("cell_key", candidate.cellKey());
            mergeContent.put("expected_cell_version", candidate.baseCellVersion());
            mergeContent.put("result_cell_version", resultVersion);
            mergeContent.put("verdict", verdict);
            mergeContent.put("decision", decision);
            mergeContent.put("reason_code", reason);
            mergeContent.put("accepted_evidence_ids", acceptedEvidenceKeys);
            String mergeDigest = canonicalizer.domainSeparatedDigest(MERGE_DIGEST_DOMAIN, mergeContent);
            try {
                jdbcTemplate.update("""
                        insert into research_cell_merge(
                            id, research_run_id, candidate_id, merge_key, cell_key,
                            expected_cell_version, result_cell_version, verdict, decision, reason_code,
                            accepted_evidence_ids_json, agent_completion_id, content_digest
                        ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, mergeId, runId, persisted.id(), mergeKey, candidate.cellKey(),
                        candidate.baseCellVersion(), resultVersion, verdict, decision, reason,
                        Json.write(objectMapper, acceptedEvidenceKeys), completionId, mergeDigest);
            } catch (DataIntegrityViolationException exception) {
                throw new BusinessException("RESEARCH_AGENT_COMPLETION_MERGE_CONFLICT",
                        "Merge stable key raced with different content");
            }

            ResearchAgentCompletionReceipt.MergeReceipt mergeReceipt =
                    new ResearchAgentCompletionReceipt.MergeReceipt(
                            candidate.cellKey(), candidate.baseCellVersion(), resultVersion, decision, reason);
            if (supported) {
                appendCellEvidence(runId, completionId, cell, persisted.id(), mergeId, mergeKey, persisted.evidence());
                accepted.add(mergeReceipt);
            } else {
                rejected.add(mergeReceipt);
            }
        }
        return new MergeOutcome(List.copyOf(accepted), List.copyOf(rejected));
    }

    private void appendCellEvidence(
            String runId,
            String completionId,
            CellRow cell,
            String candidateId,
            String mergeId,
            String mergeKey,
            List<EvidencePersisted> evidence
    ) {
        int ordinal = 0;
        for (EvidencePersisted item : evidence.stream()
                .sorted(Comparator.comparing(EvidencePersisted::evidenceKey)).toList()) {
            String relationId = Ids.newId();
            Map<String, Object> content = rowContent();
            content.put("id", relationId);
            content.put("research_run_id", runId);
            content.put("agent_completion_id", completionId);
            content.put("candidate_id", candidateId);
            content.put("merge_id", mergeId);
            content.put("merge_key", mergeKey);
            content.put("research_cell_id", cell.id());
            content.put("source_evidence_id", item.id());
            content.put("evidence_key", item.evidenceKey());
            String digest = canonicalizer.domainSeparatedDigest(CELL_EVIDENCE_DIGEST_DOMAIN, content);
            try {
                jdbcTemplate.update("""
                        insert into research_cell_evidence(
                            id, research_run_id, research_cell_id, source_evidence_id, evidence_key,
                            agent_completion_id, content_digest
                        ) values (?, ?, ?, ?, ?, ?, ?)
                        """, relationId, runId, cell.id(), item.id(), item.evidenceKey(), completionId, digest);
            } catch (DataIntegrityViolationException exception) {
                throw new BusinessException("RESEARCH_AGENT_COMPLETION_MERGE_CONFLICT",
                        "Accepted evidence lineage raced with different content");
            }
            checkpoint(ResearchAgentCompletionFaultInjector.Stage.AFTER_NTH_CELL_EVIDENCE, ++ordinal);
        }
    }

    private BudgetLedger calculateBudget(
            ReservationRow reservation,
            Map<String, Long> workerUsage,
            int evidenceAppended,
            int acceptedMerges,
            int rejectedMerges
    ) {
        if (!reservation.reserved().keySet().equals(COMPLETE_BUDGET_KEYS)
                || !reservation.consumed().keySet().equals(COMPLETE_BUDGET_KEYS)
                || !reservation.released().keySet().equals(COMPLETE_BUDGET_KEYS)
                || reservation.consumed().values().stream().anyMatch(value -> value != 0L)
                || reservation.released().values().stream().anyMatch(value -> value != 0L)) {
            throw new BusinessException("RESEARCH_AGENT_COMPLETION_BUDGET_EXCEEDED",
                    "DEEP_CELL reservation must have exactly ten clean budget dimensions");
        }
        Map<String, Long> derived = Map.of(
                "evidence_appended", (long) evidenceAppended,
                "candidate_merges_accepted", (long) acceptedMerges,
                "candidate_merges_rejected", (long) rejectedMerges);
        Map<String, Long> consumed = new TreeMap<>();
        Map<String, Long> released = new TreeMap<>();
        for (Map.Entry<String, Long> reserved : reservation.reserved().entrySet()) {
            long used = workerUsage.containsKey(reserved.getKey())
                    ? workerUsage.get(reserved.getKey()) : derived.getOrDefault(reserved.getKey(), 0L);
            if (used < 0 || used > reserved.getValue()) {
                throw new BusinessException("RESEARCH_AGENT_COMPLETION_BUDGET_EXCEEDED",
                        "Completion usage exceeds its reservation");
            }
            consumed.put(reserved.getKey(), used);
            released.put(reserved.getKey(), reserved.getValue() - used);
        }
        if (!workerUsage.keySet().equals(ResearchAgentCompletionCanonicalizer.WORKER_USAGE_KEYS)) {
            throw new BusinessException("RESEARCH_AGENT_COMPLETION_BUDGET_EXCEEDED",
                    "Completion usage contains an unreserved dimension");
        }
        return new BudgetLedger(new TreeMap<>(reservation.reserved()), Map.copyOf(consumed), Map.copyOf(released));
    }

    private void persistBudget(
            ReservationRow reservation,
            String completionId,
            String settlementKey,
            BudgetLedger ledger
    ) {
        int updated = jdbcTemplate.update("""
                update research_budget_reservation
                set consumed_json = ?, released_json = ?, state = 'SETTLED', settled_at = current_timestamp,
                    updated_at = current_timestamp, agent_completion_id = ?, settlement_key = ?,
                    finalized_at = current_timestamp
                where id = ? and state = 'RESERVED' and agent_completion_id is null
                """, Json.write(objectMapper, ledger.consumed()), Json.write(objectMapper, ledger.released()), completionId,
                settlementKey, reservation.id());
        if (updated != 1) {
            throw new BusinessException("RESEARCH_AGENT_COMPLETION_BUDGET_EXCEEDED",
                    "Budget reservation could not be settled exactly once");
        }
    }

    private ResearchAgentCompletionReceipt buildReceipt(
            String completionId,
            String executionId,
            TaskRow task,
            String completionDigest,
            int evidenceCount,
            int candidateCount,
            MergeOutcome outcome,
            BudgetLedger ledger
    ) {
        return new ResearchAgentCompletionReceipt(
                RECEIPT_SCHEMA, completionId, executionId, task.id(), completionDigest, null, false,
                outcomeFor(task, outcome, candidateCount),
                evidenceCount, candidateCount,
                outcome.accepted().stream().sorted(Comparator.comparing(
                        ResearchAgentCompletionReceipt.MergeReceipt::cellKey, ResearchAgentBinaryOrder.UTF8)).toList(),
                outcome.rejected().stream().sorted(Comparator.comparing(
                        ResearchAgentCompletionReceipt.MergeReceipt::cellKey, ResearchAgentBinaryOrder.UTF8)).toList(),
                new ResearchAgentCompletionReceipt.BudgetReceipt(
                        "SETTLED", ledger.reserved(), ledger.consumed(), ledger.released())
        );
    }

    private String outcomeFor(TaskRow task, MergeOutcome outcome, int candidateCount) {
        if (task.candidateQuorum() == 1) return "COMMITTED";
        if (candidateCount > 0 && outcome.accepted().isEmpty() && outcome.rejected().isEmpty()) return "QUORUM_PENDING";
        return outcome.accepted().isEmpty() ? "QUORUM_REPAIR_REQUIRED" : "QUORUM_MERGED";
    }

    private Map<String, Object> receiptPayload(ResearchAgentCompletionReceipt receipt, boolean includeDigest) {
        Map<String, Object> payload = rowContent();
        payload.put("schema_version", receipt.schemaVersion());
        payload.put("completion_id", receipt.completionId());
        payload.put("execution_id", receipt.executionId());
        payload.put("task_id", receipt.taskId());
        payload.put("completion_digest", receipt.completionDigest());
        payload.put("outcome", receipt.outcome());
        payload.put("evidence_appended", receipt.evidenceAppended());
        payload.put("candidate_count", receipt.candidateCount());
        payload.put("accepted_merges", receipt.acceptedMerges().stream().map(this::mergeReceiptPayload).toList());
        payload.put("rejected_merges", receipt.rejectedMerges().stream().map(this::mergeReceiptPayload).toList());
        Map<String, Object> budget = rowContent();
        budget.put("state", receipt.budget().state());
        budget.put("reserved", receipt.budget().reserved());
        budget.put("consumed", receipt.budget().consumed());
        budget.put("released", receipt.budget().released());
        payload.put("budget", budget);
        if (includeDigest) payload.put("receipt_digest", receipt.receiptDigest());
        return payload;
    }

    private Map<String, Object> mergeReceiptPayload(ResearchAgentCompletionReceipt.MergeReceipt receipt) {
        Map<String, Object> result = rowContent();
        result.put("cell_key", receipt.cellKey());
        result.put("from_version", receipt.fromVersion());
        result.put("to_version", receipt.toVersion());
        result.put("decision", receipt.decision());
        result.put("reason_code", receipt.reasonCode());
        return result;
    }

    private List<TargetBinding> targetBindings(String json) {
        try {
            List<Map<String, Object>> values = objectMapper.readValue(json, new TypeReference<>() { });
            if (values == null || values.isEmpty() || values.size() > 3) {
                throw snapshotStale("Task target snapshot must contain one to three cells");
            }
            List<TargetBinding> result = new ArrayList<>();
            for (Map<String, Object> value : values) {
                if (!value.keySet().equals(Set.of("cell_id", "expected_version"))) {
                    throw snapshotStale("Task target snapshot contains unknown fields");
                }
                Object cell = value.get("cell_id");
                Object version = value.get("expected_version");
                if (!(cell instanceof String key)
                        || !(version instanceof Byte || version instanceof Short
                        || version instanceof Integer || version instanceof Long || version instanceof java.math.BigInteger)) {
                    throw snapshotStale("Task target snapshot is malformed");
                }
                long numericVersion;
                try {
                    numericVersion = version instanceof java.math.BigInteger integer
                            ? integer.longValueExact() : ((Number) version).longValue();
                    canonicalizer.validateServerCellKey(key);
                } catch (ArithmeticException | BusinessException exception) {
                    throw snapshotStale("Task target snapshot is malformed");
                }
                if (numericVersion < 0 || numericVersion >= Integer.MAX_VALUE) {
                    throw snapshotStale("Task target version is out of range");
                }
                result.add(new TargetBinding(key, (int) numericVersion));
            }
            return List.copyOf(result);
        } catch (JsonProcessingException exception) {
            throw snapshotStale("Task target snapshot is malformed");
        }
    }

    private Map<String, Long> readLongMap(String json, String errorCode) {
        try {
            Map<String, Object> raw = objectMapper.readValue(json, new TypeReference<>() { });
            if (raw == null || raw.isEmpty()) throw new BusinessException(errorCode, "Budget JSON is empty");
            Map<String, Long> result = new TreeMap<>();
            for (Map.Entry<String, Object> entry : raw.entrySet()) {
                if (!(entry.getValue() instanceof Byte || entry.getValue() instanceof Short
                        || entry.getValue() instanceof Integer || entry.getValue() instanceof Long
                        || entry.getValue() instanceof java.math.BigInteger)) {
                    throw new BusinessException(errorCode, "Budget JSON contains a non-number");
                }
                long value;
                try {
                    value = entry.getValue() instanceof java.math.BigInteger integer
                            ? integer.longValueExact() : ((Number) entry.getValue()).longValue();
                } catch (ArithmeticException exception) {
                    throw new BusinessException(errorCode, "Budget JSON integer is out of range");
                }
                if (value < 0) throw new BusinessException(errorCode, "Budget JSON contains a negative value");
                result.put(entry.getKey(), value);
            }
            return Map.copyOf(result);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(errorCode, "Budget JSON is malformed");
        }
    }

    private List<String> readStringList(String json) {
        try {
            List<String> values = objectMapper.readValue(json, new TypeReference<>() { });
            if (values == null || values.stream().anyMatch(value -> value == null || value.isBlank())) {
                throw new IllegalArgumentException("invalid string list");
            }
            return values.stream().distinct().sorted().toList();
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new BusinessException("RESEARCH_AGENT_COMPLETION_QUORUM_CONFLICT", "Quorum candidate provenance is invalid");
        }
    }

    private BigDecimal legacyScore(int ppm) {
        return BigDecimal.valueOf(ppm, 6).setScale(4, RoundingMode.HALF_UP);
    }

    private String mergeKey(String candidateKey) {
        String digest = canonicalizer.domainSeparatedDigest(
                "research-agent-merge-key.v1", Map.of("candidate_key", candidateKey));
        return "merge:" + digest.substring("sha256:".length());
    }

    private String quorumMergeKey(String quorumGroupKey) {
        String digest = canonicalizer.domainSeparatedDigest(
                "research-agent-quorum-merge-key.v1", Map.of("quorum_group_key", quorumGroupKey));
        return "quorum-merge:" + digest.substring("sha256:".length());
    }

    private Map<String, Object> rowContent() {
        return new LinkedHashMap<>();
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String nfc(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFC);
    }

    private boolean isCounterfactualRepairTask(TaskRow task) {
        return "COUNTERFACTUAL".equals(task.role())
                && task.candidateQuorum() == 1
                && task.candidateSlot() == 1
                && task.quorumGroupKey() == null
                && "branch-counterfactual".equals(task.branchId())
                && task.logicalTaskKey() != null
                && task.logicalTaskKey().startsWith("counterfactual:");
    }

    private void resolveCounterfactualRepairDecisions(String runId, TaskRow task, MergeOutcome outcome) {
        if (!isCounterfactualRepairTask(task) || outcome.accepted().isEmpty()) return;
        for (ResearchAgentCompletionReceipt.MergeReceipt accepted : outcome.accepted()) {
            jdbcTemplate.update("""
                    update research_verifier_decision
                    set decision_status = 'RESOLVED'
                    where research_run_id = ? and target_id = ?
                      and decision_type = 'QUORUM_REPAIR_REQUIRED'
                      and decision_status = 'OPEN'
                    """, runId, accepted.cellKey());
        }
    }

    private void checkpoint(ResearchAgentCompletionFaultInjector.Stage stage, int ordinal) {
        completionMetrics.markTransactionStage(stage);
        faultInjectors.orderedStream().forEach(injector -> injector.checkpoint(stage, ordinal));
    }

    private BusinessException staleLease() {
        return new BusinessException("RESEARCH_AGENT_TASK_STALE_LEASE",
                "Completion lease is stale or owned by another worker");
    }

    private BusinessException snapshotStale(String message) {
        return new BusinessException("RESEARCH_AGENT_TASK_SNAPSHOT_STALE", message);
    }

    private BusinessException invalidEvidence(String message) {
        return new BusinessException("RESEARCH_AGENT_COMPLETION_EVIDENCE_UNGROUNDED", message);
    }

    private record RunRow(String id, String workspaceId, String status, String executionMode) { }

    private record TaskRow(
            String id,
            String runId,
            String status,
            String role,
            String entityId,
            String branchId,
            int planRevision,
            int entitySetVersion,
            String targetBindingsJson,
            String budgetJson,
            String executionContextJson,
            String snapshotSchemaVersion,
            String snapshotDigest,
            String workerInstanceId,
            int leaseEpoch,
            long fencingToken,
            String logicalTaskKey,
            String quorumGroupKey,
            int candidateQuorum,
            int candidateSlot,
            boolean leaseValid
    ) { }

    private record ReservationRow(
            String id,
            String runId,
            String taskId,
            Map<String, Long> reserved,
            Map<String, Long> consumed,
            Map<String, Long> released,
            String state,
            String agentCompletionId,
            String settlementKey
    ) { }

    private record TargetBinding(String cellKey, int expectedVersion) { }

    private record CellRow(
            String id,
            String cellKey,
            String status,
            int cellVersion,
            int planRevision,
            int entitySetVersion,
            String activeTaskId,
            int leaseEpoch,
            long fencingToken
    ) { }

    private record TrustedSource(String id, String title, String sampleText) { }

    private record TrustedEvidenceSource(
            String sourceId,
            String sourceTitle,
            String sourceUrl,
            String provider,
            String adapter,
            String snapshotStatus,
            String snapshotKey
    ) { }

    private record ExternalArchive(
            String sourceId,
            String sourceTitle,
            String sourceUrl,
            String provider,
            String adapter,
            String snapshotKey,
            String contentText,
            String contentSha256,
            String archiveStatus
    ) { }

    private record EvidencePersisted(
            String id,
            String evidenceKey,
            String sourceId,
            String claimText,
            String relationType,
            String contentDigest
    ) { }

    private record CandidatePersisted(
            String id,
            ResearchAgentCompletionEnvelope.Candidate envelope,
            List<EvidencePersisted> evidence,
            String contentDigest
    ) { }

    private record QuorumCandidate(
            String id,
            String taskId,
            String executionId,
            String candidateKey,
            String cellKey,
            int baseCellVersion,
            String value,
            int confidencePpm,
            List<String> domains,
            List<String> evidenceKeys,
            String blindDigest,
            int slot,
            boolean supported
    ) { }

    private record MergeOutcome(
            List<ResearchAgentCompletionReceipt.MergeReceipt> accepted,
            List<ResearchAgentCompletionReceipt.MergeReceipt> rejected
    ) { }

    private record BudgetLedger(
            Map<String, Long> reserved,
            Map<String, Long> consumed,
            Map<String, Long> released
    ) { }
}
