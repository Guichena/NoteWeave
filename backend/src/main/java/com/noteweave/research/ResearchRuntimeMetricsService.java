package com.noteweave.research;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.Json;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 每次完成提交时写一行 research_runtime_metric，记录这次任务的检索词数、读取的网页数、模型调用次数和耗时。
 *
 * <p>表在 V189 建好，但 10-25 重新导入代码时写入逻辑丢失，之后一直没有新记录。这里按当前完成信封的字段重新写入：
 * 耗时取本租约第一次申请工具许可到提交的时间（服务端时钟，秒级），不依赖 Worker 上报。
 * 指标只用于排查，写入失败只记日志，不影响完成提交本身。
 */
final class ResearchRuntimeMetricsService {
    private static final Logger log = LoggerFactory.getLogger(ResearchRuntimeMetricsService.class);
    /** 与旧版约定一致：每行最多记录 64 个来源指纹。 */
    static final int MAX_DOCUMENT_FINGERPRINTS = 64;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    ResearchRuntimeMetricsService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    void record(ResearchAgentCompletionCommitter.TaskRow task, ResearchAgentCompletionEnvelope envelope,
                String completionId, int acceptedMerges) {
        try {
            insert(task, envelope, completionId, acceptedMerges);
        } catch (DataAccessException | IllegalArgumentException exception) {
            log.warn("research runtime metric not recorded task_id={} completion_id={}: {}",
                    task.id(), completionId, exception.getClass().getSimpleName());
        }
    }

    private void insert(ResearchAgentCompletionCommitter.TaskRow task, ResearchAgentCompletionEnvelope envelope,
                        String completionId, int acceptedMerges) {
        JsonNode diagnostics = mapper.valueToTree(envelope.extractionDiagnostics() == null
                ? Map.of() : envelope.extractionDiagnostics());
        List<String> fingerprints = fingerprints(envelope.evidence());
        Set<String> previous = new HashSet<>();
        for (String raw : jdbc.queryForList(
                "select document_fingerprints_json from research_runtime_metric where research_run_id = ?",
                String.class, task.runId())) {
            read(raw).forEach(value -> previous.add(value.asText()));
        }
        int duplicates = 0;
        for (String fingerprint : fingerprints) if (previous.contains(fingerprint)) duplicates++;
        Map<String, Integer> rejections = new TreeMap<>();
        diagnostics.path("rejection_counts").fields()
                .forEachRemaining(entry -> rejections.put(entry.getKey(), entry.getValue().asInt()));
        JsonNode providerCalls = diagnostics.path("provider_receipt").path("call_count");
        jdbc.update("""
                insert into research_runtime_metric(completion_id, research_run_id, task_id, role, query_count,
                    read_page_count, llm_calls, provider_call_count, input_tokens, output_tokens, duration_ms,
                    extracted_fact_count, qualified_support_count, rejection_counts_json, document_fingerprints_json,
                    duplicate_document_count)
                values (?, ?, ?, ?, ?, ?, ?, ?, null, null, ?, ?, ?, ?, ?, ?)
                """,
                completionId, task.runId(), task.id(), task.role(),
                diagnostics.path("execution_report").path("queries").size(),
                counter(envelope.telemetry(), "windows"),
                counter(envelope.budgetUsage(), "llm_calls"),
                providerCalls.isIntegralNumber() ? providerCalls.asLong() : null,
                durationMs(task),
                diagnostics.path("accepted_count").asInt(0),
                acceptedMerges,
                Json.write(mapper, rejections),
                Json.write(mapper, fingerprints),
                duplicates);
    }

    /** 本租约第一次申请工具许可到现在的秒数；没有申请过许可的角色任务记为空。 */
    private Long durationMs(ResearchAgentCompletionCommitter.TaskRow task) {
        Long seconds = jdbc.query("""
                select timestampdiff(second, min(created_at), current_timestamp)
                from research_agent_tool_grant
                where research_agent_task_id = ? and lease_epoch = ? and fencing_token = ?
                """, rs -> {
            if (!rs.next()) return null;
            long value = rs.getLong(1);
            return rs.wasNull() ? null : value;
        }, task.id(), task.leaseEpoch(), task.fencingToken());
        return seconds == null || seconds < 0 ? null : seconds * 1000L;
    }

    private static List<String> fingerprints(List<ResearchAgentCompletionEnvelope.Evidence> evidence) {
        Set<String> sources = new LinkedHashSet<>();
        if (evidence != null) {
            for (ResearchAgentCompletionEnvelope.Evidence item : evidence) {
                if (item.sourceId() != null && !item.sourceId().isBlank()) sources.add(item.sourceId());
            }
        }
        List<String> result = new ArrayList<>();
        for (String source : sources) {
            if (result.size() >= MAX_DOCUMENT_FINGERPRINTS) break;
            result.add(sha256(source));
        }
        return result;
    }

    private static long counter(Map<String, Long> counters, String key) {
        if (counters == null) return 0L;
        Long value = counters.get(key);
        return value == null ? 0L : value;
    }

    private JsonNode read(String raw) {
        try {
            JsonNode node = mapper.readTree(raw == null ? "[]" : raw);
            return node.isTextual() ? mapper.readTree(node.asText()) : node;
        } catch (Exception exception) {
            return mapper.createArrayNode();
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
