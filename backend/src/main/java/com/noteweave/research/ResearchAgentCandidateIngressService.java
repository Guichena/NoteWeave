package com.noteweave.research;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** MA4D-2B Backend-owned candidate admission and structural evidence verdict. */
@Service
public class ResearchAgentCandidateIngressService {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ResearchAgentCellMergeService mergeService;

    public ResearchAgentCandidateIngressService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
                                                ResearchAgentCellMergeService mergeService) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.mergeService = mergeService;
    }

    @Deprecated(forRemoval = true)
    @Transactional
    public CandidateBatchReceipt appendAndVerify(CandidateBatchCommand command) {
        if (command == null || command.candidates() == null || command.candidates().isEmpty()) {
            throw new BusinessException("RESEARCH_AGENT_CANDIDATE_INVALID", "Candidate batch is empty");
        }
        rejectAtomicSplitCompletion(command.taskId());
        TaskScope scope = requireLease(command);
        Map<String, Integer> allowedTargets = targetVersions(scope.targetBindingsJson());
        int accepted = 0;
        int rejected = 0;
        for (CandidateProposal proposal : command.candidates()) {
            validateProposal(proposal, allowedTargets);
            String verdict = structuralVerdict(scope.runId(), proposal);
            ResearchAgentCellMergeService.CandidateReceipt candidate = mergeService.submitCandidate(
                    new ResearchAgentCellMergeService.CandidateCommand(
                            proposal.candidateId(), scope.runId(), command.taskId(), command.executionId(), proposal.idempotencyKey(),
                            proposal.cellKey(), proposal.baseCellVersion(), scope.planRevision(), scope.entitySetVersion(),
                            command.leaseEpoch(), command.fencingToken(), proposal.candidateValue(), proposal.evidenceKeys(), proposal.confidence()
                    )
            );
            ResearchAgentCellMergeService.MergeResult merge = mergeService.mergeCandidate(
                    new ResearchAgentCellMergeService.MergeCommand(scope.runId(), candidate.candidateId(),
                            "agent-merge:" + proposal.candidateId(), verdict)
            );
            if ("ACCEPTED".equals(merge.decision()) || "IDEMPOTENT_REPLAY".equals(merge.decision())) accepted++; else rejected++;
        }
        return new CandidateBatchReceipt(accepted, rejected);
    }

    private TaskScope requireLease(CandidateBatchCommand command) {
        if (blank(command.taskId()) || blank(command.workerInstanceId()) || blank(command.executionId())
                || command.leaseEpoch() < 1 || command.fencingToken() < 1) {
            throw new BusinessException("RESEARCH_AGENT_CANDIDATE_INVALID", "Candidate lease identity is invalid");
        }
        TaskScope scope = jdbcTemplate.query("""
                select rat.research_run_id, rat.plan_revision, rat.entity_set_version, rat.target_bindings_json
                from research_agent_task rat
                join research_run rr on rr.id = rat.research_run_id
                where rat.id = ? and rat.status in ('CLAIMED', 'RUNNING') and rat.worker_instance_id = ?
                  and rat.lease_epoch = ? and rat.fencing_token = ? and rat.lease_expires_at > current_timestamp
                  and rr.agent_execution_mode = 'INCREMENTAL_V1'
                  and rr.status not in ('COMPLETED', 'FAILED', 'CANCELLED')
                for update
                """, rs -> rs.next() ? new TaskScope(rs.getString(1), rs.getInt(2), rs.getInt(3), rs.getString(4)) : null,
                command.taskId(), command.workerInstanceId(), command.leaseEpoch(), command.fencingToken());
        if (scope == null) throw new BusinessException("RESEARCH_AGENT_TASK_STALE_LEASE", "Candidate batch lease is stale");
        return scope;
    }

    private Map<String, Integer> targetVersions(String raw) {
        try {
            List<Map<String, Object>> bindings = objectMapper.readValue(raw, new TypeReference<List<Map<String, Object>>>() { });
            Map<String, Integer> targets = new java.util.LinkedHashMap<>();
            for (Map<String, Object> binding : bindings) {
                Object cell = binding.get("cell_id"); Object version = binding.get("expected_version");
                if (cell instanceof String key && !key.isBlank() && version instanceof Number number) targets.put(key, number.intValue());
            }
            if (targets.isEmpty()) throw new BusinessException("RESEARCH_AGENT_CANDIDATE_INVALID", "Task has no versioned targets");
            return targets;
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new BusinessException("RESEARCH_AGENT_CANDIDATE_INVALID", "Task target scope is invalid");
        }
    }

    private void validateProposal(CandidateProposal proposal, Map<String, Integer> targets) {
        if (proposal == null || blank(proposal.candidateId()) || blank(proposal.idempotencyKey()) || blank(proposal.cellKey())
                || blank(proposal.candidateValue()) || proposal.evidenceKeys() == null || proposal.evidenceKeys().isEmpty()
                || proposal.confidence() < 0 || proposal.confidence() > 1
                || !targets.containsKey(proposal.cellKey()) || targets.get(proposal.cellKey()) != proposal.baseCellVersion()) {
            throw new BusinessException("RESEARCH_AGENT_CANDIDATE_INVALID", "Candidate is outside the claimed task scope");
        }
    }

    private String structuralVerdict(String runId, CandidateProposal proposal) {
        List<EvidenceRow> evidence = jdbcTemplate.query("""
                select evidence_key, claim_text, relation_type, quote_text from source_evidence
                where research_run_id = ? and evidence_key in (""" + placeholders(proposal.evidenceKeys()) + ")",
                (rs, rowNum) -> new EvidenceRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)),
                parameters(runId, proposal.evidenceKeys()));
        if (evidence.size() != proposal.evidenceKeys().size()) return "NOT_ENOUGH_INFO";
        for (EvidenceRow row : evidence) {
            if (!"SUPPORTS".equals(row.relationType()) || blank(row.quoteText()) || !proposal.candidateValue().equals(row.claimText())) {
                return "NOT_ENOUGH_INFO";
            }
        }
        return "SUPPORTS";
    }

    private String placeholders(List<String> values) { return String.join(",", java.util.Collections.nCopies(values.size(), "?")); }
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
    private Object[] parameters(String runId, List<String> keys) { java.util.List<Object> values = new java.util.ArrayList<>(); values.add(runId); values.addAll(keys); return values.toArray(); }
    private boolean blank(String value) { return value == null || value.isBlank(); }

    public record CandidateBatchCommand(String taskId, String workerInstanceId, int leaseEpoch, long fencingToken,
                                        String executionId, List<CandidateProposal> candidates) { }
    public record CandidateProposal(String candidateId, String idempotencyKey, String cellKey, int baseCellVersion,
                                    String candidateValue, List<String> evidenceKeys, double confidence) { }
    public record CandidateBatchReceipt(int acceptedMergeCount, int rejectedMergeCount) { }
    private record TaskScope(String runId, int planRevision, int entitySetVersion, String targetBindingsJson) { }
    private record EvidenceRow(String evidenceKey, String claimText, String relationType, String quoteText) { }
}
