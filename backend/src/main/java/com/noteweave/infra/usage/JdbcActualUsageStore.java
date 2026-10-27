package com.noteweave.infra.usage;

import com.noteweave.common.Ids;
import com.noteweave.infra.usage.ActualUsageStore.ActualUsageRecord;
import com.noteweave.infra.usage.ActualUsageStore.Result;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * C5 真实 usage 的 JDBC 实现（FB-07，迁移 V206）。
 *
 * <p>record/upsert 原子幂等：requestAttemptId 唯一约束选赢家；完整度单调升级
 * UNKNOWN→PARTIAL→COMPLETE，COMPLETE 不被降级覆盖；同等级不同计数计入
 * conflict_count 并返回 CONFLICT，不累加、不覆盖。所有字段为供应商实际上报，
 * 不由 TokenCounter 估算填充；不存 prompt/正文/密钥。
 */
@Repository
public class JdbcActualUsageStore implements ActualUsageStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcActualUsageStore.class);

    private final JdbcTemplate jdbcTemplate;

    public JdbcActualUsageStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Result record(ActualUsageRecord record) {
        if (record == null || blank(record.requestAttemptId()) || blank(record.businessContext())) {
            log.warn("actual usage record rejected: missing attempt identity or context");
            return Result.CONFLICT;
        }
        String completeness = normalizeCompleteness(record.completeness());
        try {
            jdbcTemplate.update("""
                    insert into actual_usage_fact(
                        id, request_attempt_id, workspace_id, business_context, provider,
                        requested_model, returned_model, input_tokens, output_tokens,
                        cached_input_tokens, reasoning_tokens, reported, completeness,
                        usage_schema_version, occurred_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Ids.newId(), record.requestAttemptId(), record.workspaceId(),
                    record.businessContext(), record.provider(), record.requestedModel(),
                    record.returnedModel(), record.inputTokens(), record.outputTokens(),
                    record.cachedInputTokens(), record.reasoningTokens(), record.reported(),
                    completeness, record.usageSchemaVersion(),
                    Timestamp.from(record.occurredAt() == null ? Instant.now() : record.occurredAt()));
            return Result.STORED;
        } catch (DuplicateKeyException duplicate) {
            return resolveDuplicate(record, completeness);
        }
    }

    /** 同 attempt 已存在：按完整度单调升级；降级覆盖拒绝，同等级差异可观察。 */
    private Result resolveDuplicate(ActualUsageRecord record, String newCompleteness) {
        ExistingRow existing = jdbcTemplate.query("""
                select completeness, reported, input_tokens, output_tokens,
                       cached_input_tokens, reasoning_tokens, returned_model
                from actual_usage_fact where request_attempt_id = ?
                """, rs -> rs.next() ? new ExistingRow(
                rs.getString("completeness"), rs.getBoolean("reported"),
                rs.getObject("input_tokens") == null ? null : rs.getLong("input_tokens"),
                rs.getObject("output_tokens") == null ? null : rs.getLong("output_tokens"),
                rs.getObject("cached_input_tokens") == null ? null : rs.getLong("cached_input_tokens"),
                rs.getObject("reasoning_tokens") == null ? null : rs.getLong("reasoning_tokens"),
                rs.getString("returned_model")) : null, record.requestAttemptId());
        if (existing == null) {
            // 命中其他唯一约束：不能当幂等重复处理
            throw new IllegalStateException("actual usage insert hit unexpected constraint");
        }
        int existingRank = rank(existing.completeness());
        int newRank = rank(newCompleteness);
        if (newRank <= existingRank) {
            // V03：同等级比较覆盖全部计数列——cached/reasoning 不一致也是可观察冲突
            boolean sameLevelSameValues = newRank == existingRank
                    && Objects.equals(existing.inputTokens(), record.inputTokens())
                    && Objects.equals(existing.outputTokens(), record.outputTokens())
                    && Objects.equals(existing.cachedInputTokens(), record.cachedInputTokens())
                    && Objects.equals(existing.reasoningTokens(), record.reasoningTokens());
            if (sameLevelSameValues) {
                return Result.DUPLICATE;
            }
            // 同等级不同计数（或降级到达）：冲突可观察，保留首见值
            jdbcTemplate.update("""
                    update actual_usage_fact
                    set conflict_count = conflict_count + 1, updated_at = current_timestamp
                    where request_attempt_id = ?
                    """, record.requestAttemptId());
            log.warn("actual usage conflict observed: attemptId={}, existingCompleteness={}, incomingCompleteness={}",
                    record.requestAttemptId(), existing.completeness(), newCompleteness);
            return Result.CONFLICT;
        }
        // 单调升级：只允许向更完整记录推进，不被并发回退；
        // V03：CAS 同时锁定决策依据的旧完整度——两线程从同一 UNKNOWN 竞争不同 PARTIAL 时，
        // 输家的 UPDATE 不再匹配（旧值已被赢家推进为 PARTIAL），按冲突保留赢家首见值
        int upgraded = jdbcTemplate.update("""
                update actual_usage_fact
                set completeness = ?, reported = ?, provider = coalesce(?, provider),
                    requested_model = coalesce(?, requested_model),
                    returned_model = coalesce(?, returned_model),
                    input_tokens = coalesce(?, input_tokens), output_tokens = coalesce(?, output_tokens),
                    cached_input_tokens = coalesce(?, cached_input_tokens),
                    reasoning_tokens = coalesce(?, reasoning_tokens),
                    usage_schema_version = coalesce(?, usage_schema_version),
                    updated_at = current_timestamp
                where request_attempt_id = ? and completeness = ?
                """, newCompleteness, record.reported(), record.provider(), record.requestedModel(),
                record.returnedModel(), record.inputTokens(), record.outputTokens(),
                record.cachedInputTokens(), record.reasoningTokens(), record.usageSchemaVersion(),
                record.requestAttemptId(), existing.completeness());
        return upgraded == 1 ? Result.UPGRADED : Result.CONFLICT;
    }

    @Override
    public java.util.Optional<ActualUsageRecord> findByAttemptId(String requestAttemptId) {
        List<ActualUsageRecord> rows = jdbcTemplate.query("""
                select request_attempt_id, workspace_id, business_context, provider,
                       requested_model, returned_model, input_tokens, output_tokens,
                       cached_input_tokens, reasoning_tokens, reported, completeness,
                       usage_schema_version, occurred_at
                from actual_usage_fact where request_attempt_id = ?
                """, (rs, rowNum) -> new ActualUsageRecord(
                rs.getString("request_attempt_id"), rs.getString("workspace_id"),
                rs.getString("business_context"), rs.getString("provider"),
                rs.getString("requested_model"), rs.getString("returned_model"),
                rs.getObject("input_tokens") == null ? null : rs.getLong("input_tokens"),
                rs.getObject("output_tokens") == null ? null : rs.getLong("output_tokens"),
                rs.getObject("cached_input_tokens") == null ? null : rs.getLong("cached_input_tokens"),
                rs.getObject("reasoning_tokens") == null ? null : rs.getLong("reasoning_tokens"),
                rs.getBoolean("reported"), rs.getString("completeness"),
                rs.getString("usage_schema_version"),
                rs.getTimestamp("occurred_at") == null ? null : rs.getTimestamp("occurred_at").toInstant()),
                requestAttemptId);
        return rows.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(rows.get(0));
    }

    @Override
    public List<ActualUsageRecord> listByWorkspace(String workspaceId, Instant before, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 500));
        return jdbcTemplate.query("""
                select request_attempt_id, workspace_id, business_context, provider,
                       requested_model, returned_model, input_tokens, output_tokens,
                       cached_input_tokens, reasoning_tokens, reported, completeness,
                       usage_schema_version, occurred_at
                from actual_usage_fact
                where workspace_id = ? and (? is null or occurred_at < ?)
                order by occurred_at desc limit ?
                """, (rs, rowNum) -> new ActualUsageRecord(
                rs.getString("request_attempt_id"), rs.getString("workspace_id"),
                rs.getString("business_context"), rs.getString("provider"),
                rs.getString("requested_model"), rs.getString("returned_model"),
                rs.getObject("input_tokens") == null ? null : rs.getLong("input_tokens"),
                rs.getObject("output_tokens") == null ? null : rs.getLong("output_tokens"),
                rs.getObject("cached_input_tokens") == null ? null : rs.getLong("cached_input_tokens"),
                rs.getObject("reasoning_tokens") == null ? null : rs.getLong("reasoning_tokens"),
                rs.getBoolean("reported"), rs.getString("completeness"),
                rs.getString("usage_schema_version"),
                rs.getTimestamp("occurred_at") == null ? null : rs.getTimestamp("occurred_at").toInstant()),
                workspaceId, before == null ? null : Timestamp.from(before),
                before == null ? null : Timestamp.from(before), safeLimit);
    }

    /** 完整度等级：UNKNOWN(0) < PARTIAL(1) < COMPLETE(2)。 */
    private static int rank(String completeness) {
        return switch (normalizeCompleteness(completeness)) {
            case "COMPLETE" -> 2;
            case "PARTIAL" -> 1;
            default -> 0;
        };
    }

    private static String normalizeCompleteness(String completeness) {
        if ("COMPLETE".equals(completeness) || "PARTIAL".equals(completeness)) {
            return completeness;
        }
        return "UNKNOWN";
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private record ExistingRow(
            String completeness, boolean reported, Long inputTokens, Long outputTokens,
            Long cachedInputTokens, Long reasoningTokens, String returnedModel
    ) { }
}
