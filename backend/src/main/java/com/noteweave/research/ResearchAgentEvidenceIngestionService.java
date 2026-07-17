package com.noteweave.research;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * MA4D-2B append-only, workspace-grounded evidence intake.
 *
 * <p>It intentionally does not create candidates or alter research cells.
 * Each quote is checked against the task's server-persisted source scope, not
 * against Worker-provided page text.</p>
 */
@Service
public class ResearchAgentEvidenceIngestionService {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ResearchAgentEvidenceIngestionService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Deprecated(forRemoval = true)
    @Transactional
    public BatchReceipt appendWorkspaceEvidence(EvidenceBatchCommand command) {
        if (command == null || command.evidence() == null || command.evidence().isEmpty()) {
            throw new BusinessException("RESEARCH_AGENT_EVIDENCE_INVALID", "Evidence batch is empty");
        }
        rejectAtomicSplitCompletion(command.taskId());
        TaskScope scope = requireCurrentLease(command);
        Map<String, Map<String, Object>> sources = trustedSources(scope.executionContextJson());
        int appended = 0;
        int replayed = 0;
        for (WorkspaceEvidence evidence : command.evidence()) {
            validateEvidence(evidence, sources);
            if (appendOne(scope, evidence)) appended++; else replayed++;
        }
        return new BatchReceipt(appended, replayed);
    }

    private boolean appendOne(TaskScope scope, WorkspaceEvidence evidence) {
        ExistingEvidence existing = jdbcTemplate.query("""
                select source_id, quote_text, claim_text, relation_type
                from source_evidence where research_run_id = ? and evidence_key = ?
                """, rs -> rs.next() ? new ExistingEvidence(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)) : null,
                scope.runId(), evidence.evidenceKey());
        if (existing != null) {
            if (same(existing.sourceId(), evidence.sourceId()) && same(existing.quoteText(), evidence.quoteText())
                    && same(existing.claimText(), evidence.claimText()) && same(existing.relationType(), evidence.relationType())) {
                return false;
            }
            throw new BusinessException("RESEARCH_AGENT_EVIDENCE_IDEMPOTENCY_CONFLICT", "Evidence key is bound to different content");
        }
        try {
            jdbcTemplate.update("""
                    insert into source_evidence(
                        id, research_run_id, evidence_key, window_id, source_id, source_title, source_url,
                        provider, adapter, search_query, read_focus, quote_text, claim_text, relation_type,
                        support_score, conflict_score, snapshot_status, snapshot_key
                    ) values (?, ?, ?, ?, ?, ?, null, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'WORKSPACE', null)
                    """, Ids.newId(), scope.runId(), evidence.evidenceKey(), blankToNull(evidence.windowId()), evidence.sourceId(),
                    blankToNull(evidence.sourceTitle()), "workspace", "workspace", blankToNull(evidence.searchQuery()),
                    blankToNull(evidence.readFocus()), evidence.quoteText(), evidence.claimText(), evidence.relationType(),
                    BigDecimal.valueOf(evidence.supportScore()), BigDecimal.valueOf(evidence.conflictScore()));
            return true;
        } catch (DataIntegrityViolationException duplicate) {
            return appendOne(scope, evidence);
        }
    }

    private TaskScope requireCurrentLease(EvidenceBatchCommand command) {
        if (blank(command.taskId()) || blank(command.workerInstanceId()) || command.leaseEpoch() < 1 || command.fencingToken() < 1) {
            throw new BusinessException("RESEARCH_AGENT_EVIDENCE_INVALID", "Evidence lease identity is invalid");
        }
        TaskScope scope = jdbcTemplate.query("""
                select rat.research_run_id, rat.execution_context_json
                from research_agent_task rat
                join research_run rr on rr.id = rat.research_run_id
                where rat.id = ? and rat.status in ('CLAIMED', 'RUNNING') and rat.worker_instance_id = ?
                  and rat.lease_epoch = ? and rat.fencing_token = ? and rat.lease_expires_at > current_timestamp
                  and rr.agent_execution_mode = 'INCREMENTAL_V1'
                  and rr.status not in ('COMPLETED', 'FAILED', 'CANCELLED')
                for update
                """, rs -> rs.next() ? new TaskScope(rs.getString(1), rs.getString(2)) : null,
                command.taskId(), command.workerInstanceId(), command.leaseEpoch(), command.fencingToken());
        if (scope == null) throw new BusinessException("RESEARCH_AGENT_TASK_STALE_LEASE", "Evidence batch lease is stale");
        return scope;
    }

    private Map<String, Map<String, Object>> trustedSources(String contextJson) {
        try {
            Map<String, Object> context = objectMapper.readValue(contextJson, new TypeReference<Map<String, Object>>() { });
            Object policy = context.get("source_policy");
            if (!(policy instanceof Map<?, ?> sourcePolicy) || !(sourcePolicy.get("source_scope") instanceof List<?> rawSources)) {
                throw new BusinessException("RESEARCH_AGENT_EVIDENCE_SCOPE_INVALID", "Task has no trusted workspace source scope");
            }
            Map<String, Map<String, Object>> sources = new java.util.LinkedHashMap<>();
            for (Object raw : rawSources) {
                if (!(raw instanceof Map<?, ?> source)) continue;
                String sourceId = String.valueOf(source.get("source_id")).trim();
                String sample = String.valueOf(source.get("sample_text")).trim();
                if (!sourceId.isBlank() && !sample.isBlank()) {
                    Map<String, Object> normalized = new java.util.LinkedHashMap<>();
                    source.forEach((key, value) -> normalized.put(String.valueOf(key), value));
                    sources.put(sourceId, normalized);
                }
            }
            if (sources.isEmpty()) throw new BusinessException("RESEARCH_AGENT_EVIDENCE_SCOPE_INVALID", "Task has no trusted workspace source samples");
            return sources;
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new BusinessException("RESEARCH_AGENT_EVIDENCE_SCOPE_INVALID", "Task source scope is invalid");
        }
    }

    private void validateEvidence(WorkspaceEvidence evidence, Map<String, Map<String, Object>> sources) {
        if (evidence == null || blank(evidence.evidenceKey()) || blank(evidence.sourceId()) || blank(evidence.quoteText())
                || blank(evidence.claimText()) || !"WORKSPACE".equals(evidence.snapshotStatus())
                || !List.of("SUPPORTS", "WEAK_SUPPORT", "CONFLICTS").contains(evidence.relationType())
                || evidence.supportScore() < 0 || evidence.supportScore() > 1 || evidence.conflictScore() < 0 || evidence.conflictScore() > 1) {
            throw new BusinessException("RESEARCH_AGENT_EVIDENCE_INVALID", "Workspace evidence contract is invalid");
        }
        Map<String, Object> source = sources.get(evidence.sourceId());
        if (source == null || !String.valueOf(source.get("sample_text")).contains(evidence.quoteText())) {
            throw new BusinessException("RESEARCH_AGENT_EVIDENCE_UNGROUNDED", "Evidence quote is not grounded in the trusted source scope");
        }
    }

    private boolean same(String left, String right) { return java.util.Objects.equals(left, right); }
    private void rejectAtomicSplitCompletion(String taskId) {
        Integer required = jdbcTemplate.queryForObject("""
                select count(*) from research_agent_task rat
                join research_run rr on rr.id = rat.research_run_id
                where rat.id = ?
                  and ((rat.role = 'DEEP_CELL' and rat.snapshot_schema_version in (
                          'research-agent-task-snapshot.v1', 'research-agent-task-snapshot.v2'))
                       or (rat.role = 'COUNTERFACTUAL'
                           and rat.snapshot_schema_version = 'research-agent-task-snapshot.v2'))
                  and rr.agent_execution_mode = 'INCREMENTAL_V1'
                """, Integer.class, taskId);
        if (required != null && required == 1) {
            throw new BusinessException("RESEARCH_AGENT_ATOMIC_COMPLETION_REQUIRED",
                    "Snapshot-ready research agent tasks must use the atomic completion endpoint");
        }
    }
    private boolean blank(String value) { return value == null || value.isBlank(); }
    private String blankToNull(String value) { return blank(value) ? null : value.trim(); }

    public record EvidenceBatchCommand(String taskId, String workerInstanceId, int leaseEpoch, long fencingToken,
                                       List<WorkspaceEvidence> evidence) { }
    public record WorkspaceEvidence(String evidenceKey, String windowId, String sourceId, String sourceTitle,
                                    String searchQuery, String readFocus, String quoteText, String claimText,
                                    String relationType, double supportScore, double conflictScore, String snapshotStatus) { }
    public record BatchReceipt(int appendedCount, int idempotentReplayCount) { }
    private record TaskScope(String runId, String executionContextJson) { }
    private record ExistingEvidence(String sourceId, String quoteText, String claimText, String relationType) { }
}
