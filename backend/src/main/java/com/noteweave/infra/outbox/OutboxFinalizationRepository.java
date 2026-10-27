package com.noteweave.infra.outbox;

import com.noteweave.common.Ids;
import com.noteweave.common.Json;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * outbox 死信补偿收尾的持久登记与租约认领（B-01 / FB-01）。
 *
 * <p>PENDING 意图与 DEAD_LETTER 终止写入在同一业务短事务内插入；
 * 独立 worker 之后按数据库时间认领租约执行业务收尾。认领事务里只做 CAS 更新，
 * 不执行网络、ES 或文件调用；每条补偿独立事务，单条失败不回滚整批。
 *
 * <p>FB-01：每次认领生成一次性 claim_token，所有结果写回（finish/retryLater）
 * 必须同时持有 id + claim_token + RUNNING；同实例或同 owner 的迟到写回被令牌拒绝。
 * lease_owner 仅用于诊断归属，不再作为写回凭证。人工重驱在同一事务内调用
 * {@link #cancelSuperseded} 把旧代次补偿置为 IGNORED_STALE，与换代原子提交。
 */
@Repository
public class OutboxFinalizationRepository {

    public static final String STORE_TASK = "TASK_OUTBOX";
    public static final String STORE_AGENT = "AGENT_OUTBOX";
    public static final String STORE_KAFKA_DLT = "KAFKA_DLT";

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_RETRY_WAIT = "RETRY_WAIT";
    public static final String STATUS_APPLIED = "APPLIED";
    public static final String STATUS_IGNORED_STALE = "IGNORED_STALE";
    public static final String STATUS_TERMINAL_FAILURE = "TERMINAL_FAILURE";

    /** 身份不可恢复标记：登记时身份超长被摘要化，补偿不能按空身份当陈旧任务处理。 */
    public static final String IDENTITY_UNRECOVERABLE_KEY = "_identityUnrecoverable";

    private static final int IDENTITY_MAX_CHARS = 1000;
    /** 摘要化前先收敛到收尾必需的标量身份键，绝大多数身份在这里就能压进上限。 */
    private static final String[] IDENTITY_ESSENTIAL_KEYS = {
            "taskId", "task_id", "workspaceId", "workspace_id", "sourceId", "source_id",
            "snapshotId", "sourceSnapshotId", "messageId", "message_id",
            "batchNo", "batchVersion", "topic", "errorCode", "outboxId", "attemptNo"
    };

    private static final Logger log = LoggerFactory.getLogger(OutboxFinalizationRepository.class);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;

    public OutboxFinalizationRepository(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /**
     * 幂等插入 PENDING 补偿意图。必须在 DEAD_LETTER 终止写入的同一事务内调用：
     * 意图插入失败会让整个死信事务回滚，杜绝「死信已提交但没有补偿」的窗口。
     * 已存在同身份记录时返回 false，不覆盖既有状态。
     *
     * <p>身份 JSON 超长时先收敛到必需标量键；仍超长则保留 SHA-256 摘要标记，
     * 执行器读到该标记按 TERMINAL_FAILURE 处理，不把截断后的残缺 JSON 当合法身份。
     */
    public boolean insertPending(
            String storeType,
            String outboxId,
            int deliveryGeneration,
            String businessKind,
            String workspaceId,
            Map<String, Object> businessIdentity,
            int maxAttempts
    ) {
        String identityJson = safeIdentityJson(businessIdentity);
        try {
            return jdbcTemplate.update("""
                    insert into outbox_finalization(
                        id, workspace_id, store_type, outbox_id, delivery_generation, business_kind,
                        business_identity_json, status, attempt_count, max_attempts,
                        created_at, updated_at)
                    values (?, ?, ?, ?, ?, ?, ?, 'PENDING', 0, ?, current_timestamp, current_timestamp)
                    """, Ids.newId(), workspaceId, storeType, outboxId, deliveryGeneration, businessKind,
                    identityJson, maxAttempts) == 1;
        } catch (DuplicateKeyException duplicate) {
            return false;
        }
    }

    /** 读取既有意图状态，供消费死信先行去重判断。 */
    public String statusOf(String storeType, String outboxId, int deliveryGeneration) {
        List<String> statuses = jdbcTemplate.queryForList("""
                select status from outbox_finalization
                where store_type = ? and outbox_id = ? and delivery_generation = ?
                """, String.class, storeType, outboxId, deliveryGeneration);
        return statuses.isEmpty() ? null : statuses.get(0);
    }

    /**
     * 内联路径直接完结已登记的意图（消费死信先登记再内联执行的场景）：
     * 仅当意图尚未进入租约执行时生效，避免覆盖补偿执行器的写回。
     */
    public boolean completePending(String storeType, String outboxId, int deliveryGeneration,
            String status, String errorCode) {
        return jdbcTemplate.update("""
                update outbox_finalization
                set status = ?, lease_owner = null, lease_until = null, claim_token = null, next_attempt_at = null,
                    completed_at = current_timestamp, updated_at = current_timestamp,
                    last_error_code = coalesce(?, last_error_code)
                where store_type = ? and outbox_id = ? and delivery_generation = ?
                  and status in ('PENDING', 'RETRY_WAIT', 'RUNNING')
                """, status, errorCode, storeType, outboxId, deliveryGeneration) == 1;
    }

    /** 内联路径暂时失败：意图转入 RETRY_WAIT，由补偿执行器退避重试。 */
    public boolean retryPending(String storeType, String outboxId, int deliveryGeneration,
            String errorCode, String summary, long backoffSeconds) {
        return jdbcTemplate.update("""
                update outbox_finalization
                set status = 'RETRY_WAIT', lease_owner = null, lease_until = null, claim_token = null,
                    next_attempt_at = timestampadd(second, ?, current_timestamp), updated_at = current_timestamp,
                    last_error_code = ?, last_error_summary = ?
                where store_type = ? and outbox_id = ? and delivery_generation = ?
                  and status in ('PENDING', 'RUNNING')
                """, Math.max(1L, backoffSeconds), abbreviate(errorCode, 128), abbreviate(summary, 500),
                storeType, outboxId, deliveryGeneration) == 1;
    }

    /**
     * 认领到期补偿：PENDING/RETRY_WAIT 到期、RUNNING 且租约过期的行都可以被认领。
     * 每行 CAS 认领并生成一次性 claim_token（FB-01）；旧认领的迟到写回因令牌不匹配被拒绝。
     * 认领只更新租约，不做业务调用。返回值携带登记时间，供 DLT 侧业务阶段围栏判定。
     */
    public List<ClaimedFinalization> claimDue(String ownerId, int limit, Duration leaseDuration) {
        List<PendingRow> due = jdbcTemplate.query("""
                select id, store_type, outbox_id, delivery_generation, business_kind,
                       business_identity_json, attempt_count, max_attempts, created_at
                from outbox_finalization
                where status in ('PENDING', 'RETRY_WAIT', 'RUNNING')
                  and (next_attempt_at is null or next_attempt_at <= current_timestamp)
                  and (lease_until is null or lease_until < current_timestamp)
                order by created_at, id
                limit ?
                """, (rs, rowNum) -> new PendingRow(
                rs.getString("id"), rs.getString("store_type"), rs.getString("outbox_id"),
                rs.getInt("delivery_generation"), rs.getString("business_kind"),
                rs.getString("business_identity_json"), rs.getInt("attempt_count"), rs.getInt("max_attempts"),
                rs.getTimestamp("created_at") == null ? null : rs.getTimestamp("created_at").toInstant()),
                limit);
        List<ClaimedFinalization> claimed = new ArrayList<>();
        for (PendingRow row : due) {
            String claimToken = Ids.newId();
            int updated = jdbcTemplate.update("""
                    update outbox_finalization
                    set status = 'RUNNING', lease_owner = ?, claim_token = ?,
                        lease_until = timestampadd(second, ?, current_timestamp),
                        attempt_count = attempt_count + 1, updated_at = current_timestamp
                    where id = ? and status in ('PENDING', 'RETRY_WAIT', 'RUNNING')
                      and (next_attempt_at is null or next_attempt_at <= current_timestamp)
                      and (lease_until is null or lease_until < current_timestamp)
                    """, ownerId, claimToken, Math.max(1L, leaseDuration.toSeconds()), row.id());
            if (updated == 1) {
                claimed.add(new ClaimedFinalization(
                        row.id(), row.storeType(), row.outboxId(), row.deliveryGeneration(),
                        row.businessKind(), readIdentity(row.businessIdentityJson()), ownerId, claimToken,
                        row.attemptCount() + 1, row.maxAttempts(), row.createdAt()));
            }
        }
        return claimed;
    }

    /** 收尾成功（APPLIED / IGNORED_STALE）：按 id + claimToken CAS，过期认领不能提交。 */
    public boolean finish(String id, String claimToken, String status, Instant completedAt) {
        return jdbcTemplate.update("""
                update outbox_finalization
                set status = ?, lease_owner = null, lease_until = null, claim_token = null, next_attempt_at = null,
                    completed_at = ?, updated_at = current_timestamp
                where id = ? and claim_token = ? and status = 'RUNNING'
                """, status, java.sql.Timestamp.from(completedAt), id, claimToken) == 1;
    }

    /** 可重试失败：未达上限进 RETRY_WAIT 并退避；达上限进入 TERMINAL_FAILURE 保持可查询。 */
    public boolean retryLater(
            String id, String claimToken, String errorCode, String summary, long backoffSeconds) {
        int terminal = jdbcTemplate.update("""
                update outbox_finalization
                set status = 'TERMINAL_FAILURE', lease_owner = null, lease_until = null, claim_token = null,
                    next_attempt_at = null, completed_at = current_timestamp, updated_at = current_timestamp,
                    last_error_code = ?, last_error_summary = ?
                where id = ? and claim_token = ? and status = 'RUNNING'
                  and attempt_count >= max_attempts
                """, abbreviate(errorCode, 128), abbreviate(summary, 500), id, claimToken);
        if (terminal == 1) {
            return true;
        }
        return jdbcTemplate.update("""
                update outbox_finalization
                set status = 'RETRY_WAIT', lease_owner = null, lease_until = null, claim_token = null,
                    next_attempt_at = timestampadd(second, ?, current_timestamp), updated_at = current_timestamp,
                    last_error_code = ?, last_error_summary = ?
                where id = ? and claim_token = ? and status = 'RUNNING'
                """, Math.max(1L, backoffSeconds), abbreviate(errorCode, 128), abbreviate(summary, 500),
                id, claimToken) == 1;
    }

    /**
     * 人工重驱在同一事务内失效旧代次补偿（FB-01）：与 outbox 换代 CAS 同提交，
     * 未到期的旧代次补偿直接进入 IGNORED_STALE，不再被补偿执行器认领执行。
     * 已被认领（RUNNING）的行同样失效；其执行线程的写回会因 claim_token 不匹配被拒绝。
     */
    public int cancelSuperseded(String storeType, String outboxId, int deliveryGeneration, String errorCode) {
        return jdbcTemplate.update("""
                update outbox_finalization
                set status = 'IGNORED_STALE', lease_owner = null, lease_until = null, claim_token = null,
                    next_attempt_at = null, completed_at = current_timestamp, updated_at = current_timestamp,
                    last_error_code = coalesce(?, last_error_code)
                where store_type = ? and outbox_id = ? and delivery_generation = ?
                  and status in ('PENDING', 'RUNNING', 'RETRY_WAIT')
                """, abbreviate(errorCode, 128), storeType, outboxId, deliveryGeneration);
    }

    /**
     * 按业务任务失效补偿（FB-01）：重驱 task_outbox 行时，同任务在消费死信（KAFKA_DLT）
     * 侧登记的补偿一并失效。identity JSON 里包含 taskId 的行按前缀匹配定位；
     * 精确围栏由执行器侧的 TASK_REDRIVEN 事件围栏保证，这里只做同事务的可观测收敛。
     */
    public int cancelSupersededByTask(String storeType, String taskId, String errorCode) {
        if (taskId == null || taskId.isBlank()) {
            return 0;
        }
        return jdbcTemplate.update("""
                update outbox_finalization
                set status = 'IGNORED_STALE', lease_owner = null, lease_until = null, claim_token = null,
                    next_attempt_at = null, completed_at = current_timestamp, updated_at = current_timestamp,
                    last_error_code = coalesce(?, last_error_code)
                where store_type = ? and status in ('PENDING', 'RUNNING', 'RETRY_WAIT')
                  and business_identity_json like concat('%\"taskId\":\"', ?, '\"%')
                """, abbreviate(errorCode, 128), storeType, taskId);
    }

    /** 补偿视角指标：待处理数量、最老等待时长、重试与终态计数。 */
    public Map<String, Long> metrics() {
        Map<String, Long> counts = new HashMap<>();
        jdbcTemplate.query("""
                select status, count(*) as total,
                       coalesce(max(timestampdiff(second, created_at, current_timestamp)), 0) as oldest_age
                from outbox_finalization
                where completed_at is null or updated_at > timestampadd(second, -3600, current_timestamp)
                group by status
                """, rs -> {
            counts.put("finalization." + rs.getString("status") + ".count", rs.getLong("total"));
            counts.put("finalization." + rs.getString("status") + ".oldest_age_seconds", rs.getLong("oldest_age"));
        });
        return counts;
    }

    /** 身份 JSON 收敛：先保留必需标量键；仍超长则写摘要标记，执行器按不可恢复终态处理。 */
    String safeIdentityJson(Map<String, Object> businessIdentity) {
        Map<String, Object> identity = businessIdentity == null ? Map.of() : businessIdentity;
        String json = Json.write(objectMapper, identity);
        if (json.length() <= IDENTITY_MAX_CHARS) {
            return json;
        }
        Map<String, Object> reduced = new LinkedHashMap<>();
        for (String key : IDENTITY_ESSENTIAL_KEYS) {
            Object value = identity.get(key);
            if (value instanceof Number || value instanceof Boolean
                    || (value instanceof String text && !text.isBlank())) {
                reduced.put(key, value);
            }
        }
        json = Json.write(objectMapper, reduced);
        if (json.length() <= IDENTITY_MAX_CHARS) {
            log.warn("outbox 补偿身份超长，已收敛到必需身份键: keys={}", identity.keySet());
            return json;
        }
        Map<String, Object> digestMarker = new LinkedHashMap<>();
        digestMarker.put(IDENTITY_UNRECOVERABLE_KEY, true);
        digestMarker.put("_digest", sha256(Json.write(objectMapper, identity)));
        log.error("outbox 补偿身份在收敛后仍超长，登记为不可恢复摘要: kind-agnostic keys={}", identity.keySet());
        return Json.write(objectMapper, digestMarker);
    }

    private Map<String, Object> readIdentity(String json) {
        try {
            return objectMapper.readValue(json == null ? "{}" : json, new TypeReference<>() { });
        } catch (Exception failure) {
            log.error("outbox 补偿身份无法解析，标记为不可恢复: {}", failure.getMessage());
            // 历史行可能存在截断残缺 JSON：按不可恢复处理，不退化为空身份当陈旧任务
            Map<String, Object> marker = new LinkedHashMap<>();
            marker.put(IDENTITY_UNRECOVERABLE_KEY, true);
            marker.put("_rawDigest", sha256(String.valueOf(json)));
            return marker;
        }
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException("SHA-256 unavailable", failure);
        }
    }

    private String abbreviate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private record PendingRow(
            String id, String storeType, String outboxId, int deliveryGeneration, String businessKind,
            String businessIdentityJson, int attemptCount, int maxAttempts, Instant createdAt
    ) { }

    public record ClaimedFinalization(
            String id, String storeType, String outboxId, int deliveryGeneration, String businessKind,
            Map<String, Object> businessIdentity, String leaseOwner, String claimToken,
            int attemptCount, int maxAttempts, Instant registeredAt
    ) {
        /** 兼容旧构造（测试）：无令牌与登记时间。 */
        public ClaimedFinalization(
                String id, String storeType, String outboxId, int deliveryGeneration, String businessKind,
                Map<String, Object> businessIdentity, String leaseOwner, int attemptCount, int maxAttempts
        ) {
            this(id, storeType, outboxId, deliveryGeneration, businessKind, businessIdentity,
                    leaseOwner, null, attemptCount, maxAttempts, null);
        }
    }
}
