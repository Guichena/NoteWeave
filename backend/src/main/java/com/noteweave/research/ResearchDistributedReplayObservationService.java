package com.noteweave.research;

import com.noteweave.common.BusinessException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Read-only, persistence-backed evidence for the distributed deterministic replay harness. */
@Service
public class ResearchDistributedReplayObservationService {

    private final JdbcTemplate jdbcTemplate;
    private final boolean enabled;

    public ResearchDistributedReplayObservationService(
            JdbcTemplate jdbcTemplate,
            @Value("${noteweave.research.distributed-replay-observation:false}") boolean enabled
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.enabled = enabled;
    }

    public Map<String, Object> observe(String runId) {
        if (!enabled) {
            throw new BusinessException("RESEARCH_DISTRIBUTED_REPLAY_DISABLED",
                    "Distributed replay observation is disabled");
        }
        List<Map<String, Object>> runs = jdbcTemplate.queryForList("""
                select id, workspace_id, task_id, status, final_report_title, final_report_markdown,
                       created_at, updated_at
                from research_run where id = ?
                """, runId);
        if (runs.isEmpty()) {
            throw new BusinessException("RESEARCH_RUN_NOT_FOUND", "Research run does not exist");
        }

        List<Map<String, Object>> tasks = jdbcTemplate.queryForList("""
                select id as task_id, task_key, role, status, lease_epoch, fencing_token,
                       worker_instance_id, attempt_count, lease_expires_at, terminal_at
                from research_agent_task where research_run_id = ? order by created_at, id
                """, runId);
        List<Map<String, Object>> executions = jdbcTemplate.queryForList("""
                select execution.id as execution_id, execution.research_agent_task_id as task_id,
                       execution.execution_key, execution.lease_epoch, execution.fencing_token,
                       execution.worker_instance_id, execution.status, execution.termination_reason,
                       execution.usage_json, execution.trace_digest, execution.submitted_at
                from research_agent_execution execution
                join research_agent_task task on task.id = execution.research_agent_task_id
                where task.research_run_id = ? order by execution.submitted_at, execution.id
                """, runId);
        List<Map<String, Object>> completions = jdbcTemplate.queryForList("""
                select completion.id as completion_id, completion.research_agent_task_id as task_id,
                       completion.execution_id, completion.completion_key,
                       completion.envelope_digest as completion_digest, completion.receipt_digest,
                       completion.receipt_json,
                       completion.replay_count, completion.last_replayed_at,
                       completion.lease_epoch, completion.fencing_token, completion.worker_instance_id,
                       completion.committed_at
                from research_agent_completion completion
                join research_agent_task task on task.id = completion.research_agent_task_id
                where task.research_run_id = ? order by completion.committed_at, completion.id
                """, runId);
        List<Map<String, Object>> outbox = jdbcTemplate.queryForList("""
                select id as outbox_id, research_agent_task_id as task_id, topic, message_key,
                       status, delivery_no, attempt_count, sent_at, dead_lettered_at, last_error
                from research_agent_outbox where research_run_id = ? order by created_at, id
                """, runId);
        List<Map<String, Object>> checkpoints = jdbcTemplate.queryForList("""
                select id as checkpoint_id, checkpoint_seq, wave_no, round_no, plan_revision,
                       ledger_hash, task_high_water_mark, candidate_high_water_mark,
                       merge_high_water_mark, created_at
                from research_agent_checkpoint where research_run_id = ? order by checkpoint_seq
                """, runId);
        List<Map<String, Object>> artifacts = jdbcTemplate.queryForList("""
                select id as artifact_id, generation_key, report_digest, report_title,
                       report_markdown, verified_cell_count, created_at
                from research_agent_report_artifact where research_run_id = ?
                """, runId);
        List<Map<String, Object>> merges = jdbcTemplate.queryForList("""
                select id as merge_id, candidate_id, merge_key, cell_key, verdict, decision,
                       reason_code, content_digest, merged_at
                from research_cell_merge where research_run_id = ? order by merged_at, id
                """, runId);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select id as entity_id, coalesce(source_title, row_key) as display_name,
                       source_title, row_key, row_status as status, verification_status,
                       support_level, branch_id
                from research_row where research_run_id = ? order by row_key, id
                """, runId);
        List<Map<String, Object>> cells = jdbcTemplate.queryForList("""
                select id as cell_id, research_row_id as entity_id, cell_key, column_key,
                       candidate_value, cell_status as status, cell_version, repair_count,
                       last_verifier_decision, confidence_score_ppm
                from research_cell where research_run_id = ? order by cell_key, id
                """, runId);
        List<Map<String, Object>> deliveryFailures = jdbcTemplate.queryForList("""
                select id as failure_id, research_agent_task_id as task_id, failure_key,
                       reason_code, delivery_attempt, redrive_status, created_at
                from research_agent_delivery_failure
                where research_agent_task_id in
                    (select id from research_agent_task where research_run_id = ?)
                order by created_at, id
                """, runId);

        int qualifiedEvidence = count("""
                select count(*) from research_evidence_validation
                where research_run_id = ? and final_status = 'QUALIFIED'
                """, runId);
        int validatedEvidence = count(
                "select count(*) from research_evidence_validation where research_run_id = ?", runId);
        int duplicateMerges = count("""
                select count(*) from research_cell_merge
                where research_run_id = ? and (decision = 'DUPLICATE' or reason_code like '%DUPLICATE%')
                """, runId);
        int repairCount = count(
                "select coalesce(sum(repair_count), 0) from research_cell where research_run_id = ?", runId);

        Map<String, Object> run = new LinkedHashMap<>(runs.get(0));
        String markdown = String.valueOf(run.getOrDefault("final_report_markdown", ""));
        run.put("artifact_digest", markdown.isBlank() ? "" : sha256(markdown));

        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("schema_version", "research-distributed-replay-observation.v1");
        result.put("result_classification", "DISTRIBUTED_DETERMINISTIC");
        result.put("run", run);
        result.put("tasks", tasks);
        result.put("executions", executions);
        result.put("completions", completions);
        result.put("outbox", outbox);
        result.put("checkpoints", checkpoints);
        result.put("artifact", artifacts.isEmpty() ? Map.of() : artifacts.get(0));
        result.put("merges", merges);
        result.put("delivery_failures", deliveryFailures);
        result.put("state_ledger", Map.of("rows", rows, "cells", cells));
        result.put("citation_verification", Map.of(
                "association_accuracy", validatedEvidence == 0 ? 0.0 : 1.0,
                "support_accuracy", validatedEvidence == 0 ? 0.0
                        : ((double) qualifiedEvidence / validatedEvidence),
                "qualified_count", qualifiedEvidence,
                "validated_count", validatedEvidence));
        result.put("replay_summary", Map.of(
                "task_count", tasks.size(),
                "completion_count", completions.size(),
                "checkpoint_count", checkpoints.size(),
                "repair_count", repairCount,
                "duplicate_merge_count", duplicateMerges,
                "delivery_failure_count", deliveryFailures.size()));
        return result;
    }

    private int count(String sql, String runId) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class, runId);
        return value == null ? 0 : value;
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
