package com.noteweave.retrieval.projection;

import com.noteweave.common.Ids;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * source READY 持久摄取意图（B-02 / C3 / FB-02，M-WIKI-INTENT）。
 *
 * <p>资料索引 READY 提交的同一事务内写入意图；之后无论进程何时重启，
 * 消费侧都能按 workspace/source/snapshot/ingestPolicyVersion 身份认领并建 Wiki 任务，
 * 同一快照同政策不重复。旧 AFTER_COMMIT 监听只作为唤醒提示，不是唯一交付保证。
 *
 * <p>FB-02 / N-06：认领不再按 created_at 新鲜度过滤——停机或积压超过任何时长，
 * 未终态意图（PENDING / RETRY_WAIT 到期 / CLAIMED 租约过期）都持续可领，
 * 有效积压不会因为老化而消失。暂时失败按有界次数退避（RETRY_WAIT），
 * 达上限进入 FAILED 终态并有原因码，毒性意图不阻塞后续。
 * 认领只做 CAS 更新，事务内不做网络或索引调用；每次认领生成一次性 claim_token，
 * 回写必须同时持有 id + claim_token + CLAIMED 状态，迟到写回被拒绝。
 */
@Repository
public class SourceReadyIntentRepository {

    private final JdbcTemplate jdbcTemplate;

    public SourceReadyIntentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * READY 提交事务内写入意图。必须与 READY 状态变更同一事务调用；
     * 已存在同身份意图时幂等返回 false，不覆盖既有状态。
     */
    public boolean recordIfAbsent(
            String workspaceId, String sourceId, String snapshotId, String ingestPolicyVersion) {
        try {
            return jdbcTemplate.update("""
                    insert into source_ready_intent(
                        id, workspace_id, source_id, source_snapshot_id, ingest_policy_version,
                        status, created_at, updated_at)
                    values (?, ?, ?, ?, ?, 'PENDING', current_timestamp, current_timestamp)
                    """, Ids.newId(), workspaceId, sourceId, snapshotId,
                    ingestPolicyVersion == null || ingestPolicyVersion.isBlank() ? "1" : ingestPolicyVersion) == 1;
        } catch (DuplicateKeyException duplicate) {
            return false;
        }
    }

    /**
     * 认领到期意图：PENDING、到期 RETRY_WAIT、租约过期的 CLAIMED 都可被认领；
     * 不按 created_at 过滤（FB-02：长停机后的积压同样恢复）。
     * 每行 CAS 认领并生成一次性 claim_token。
     */
    public List<ReadySourceIntent> claimDue(String ownerId, int limit, Duration leaseDuration) {
        List<PendingIntent> due = jdbcTemplate.query("""
                select id, workspace_id, source_id, source_snapshot_id, ingest_policy_version
                from source_ready_intent
                where (status in ('PENDING', 'RETRY_WAIT')
                            and (next_attempt_at is null or next_attempt_at <= current_timestamp))
                   or (status = 'CLAIMED' and (lease_until is null or lease_until < current_timestamp))
                order by created_at, id
                limit ?
                """, (rs, rowNum) -> new PendingIntent(
                rs.getString("id"), rs.getString("workspace_id"), rs.getString("source_id"),
                rs.getString("source_snapshot_id"), rs.getString("ingest_policy_version")),
                limit);
        List<ReadySourceIntent> claimed = new ArrayList<>();
        for (PendingIntent row : due) {
            String claimToken = Ids.newId();
            int updated = jdbcTemplate.update("""
                    update source_ready_intent
                    set status = 'CLAIMED', lease_owner = ?, claim_token = ?,
                        lease_until = timestampadd(second, ?, current_timestamp),
                        attempt_count = attempt_count + 1, last_attempt_at = current_timestamp,
                        updated_at = current_timestamp
                    where id = ?
                      and ((status in ('PENDING', 'RETRY_WAIT')
                                and (next_attempt_at is null or next_attempt_at <= current_timestamp))
                        or (status = 'CLAIMED' and (lease_until is null or lease_until < current_timestamp)))
                    """, ownerId, claimToken, Math.max(1L, leaseDuration.toSeconds()), row.id());
            if (updated == 1) {
                claimed.add(new ReadySourceIntent(row.id(), row.workspaceId(), row.sourceId(),
                        row.snapshotId(), row.policyVersion(), ownerId, claimToken));
            }
        }
        return claimed;
    }

    /** 消费成功：按 id+claimToken CAS 回写 CONSUMED；意图保留作为对账事实，不物理删除。 */
    public boolean complete(String intentId, String claimToken) {
        return jdbcTemplate.update("""
                update source_ready_intent
                set status = 'CONSUMED', lease_owner = null, lease_until = null, claim_token = null,
                    next_attempt_at = null, updated_at = current_timestamp
                where id = ? and claim_token = ? and status = 'CLAIMED'
                """, intentId, claimToken) == 1;
    }

    /** 意图过期（快照被更新、资料删除、业务不需要）：无副作用回执，保留可查询事实。 */
    public boolean ignoreStale(String intentId, String claimToken, String reasonCode) {
        return jdbcTemplate.update("""
                update source_ready_intent
                set status = 'IGNORED_STALE', lease_owner = null, lease_until = null, claim_token = null,
                    next_attempt_at = null, last_error_code = coalesce(?, last_error_code),
                    updated_at = current_timestamp
                where id = ? and claim_token = ? and status = 'CLAIMED'
                """, abbreviate(reasonCode, 128), intentId, claimToken) == 1;
    }

    /** 暂时失败：退避后重试；未达上限进 RETRY_WAIT（FB-02：不抢占忙循环，有界恢复）。 */
    public boolean retryWait(String intentId, String claimToken, String errorCode, long backoffSeconds) {
        return jdbcTemplate.update("""
                update source_ready_intent
                set status = 'RETRY_WAIT', lease_owner = null, lease_until = null, claim_token = null,
                    next_attempt_at = timestampadd(second, ?, current_timestamp),
                    last_error_code = coalesce(?, last_error_code), updated_at = current_timestamp
                where id = ? and claim_token = ? and status = 'CLAIMED'
                """, Math.max(1L, backoffSeconds), abbreviate(errorCode, 128), intentId, claimToken) == 1;
    }

    /**
     * 功能暂停停放（AU-003）：业务功能（如 Wiki）关闭期间到达的意图不做终态化，
     * 也不消耗重试预算——回到 PENDING、attempt_count 清零、长延迟后再投。
     * 重新启用功能后调度器自然恢复处理，暂停窗口的合法积压不丢失。
     */
    public boolean park(String intentId, String claimToken, String reasonCode, long parkSeconds) {
        return jdbcTemplate.update("""
                update source_ready_intent
                set status = 'PENDING', lease_owner = null, lease_until = null, claim_token = null,
                    next_attempt_at = timestampadd(second, ?, current_timestamp),
                    attempt_count = 0, last_error_code = coalesce(?, last_error_code),
                    updated_at = current_timestamp
                where id = ? and claim_token = ? and status = 'CLAIMED'
                """, Math.max(60L, parkSeconds), abbreviate(reasonCode, 128), intentId, claimToken) == 1;
    }

    /** 达到重试上限的毒性意图终态：保留原因码供人工处置，不阻塞后续意图。 */
    public boolean failTerminal(String intentId, String claimToken, String errorCode) {
        return jdbcTemplate.update("""
                update source_ready_intent
                set status = 'FAILED', lease_owner = null, lease_until = null, claim_token = null,
                    next_attempt_at = null, last_error_code = coalesce(?, last_error_code),
                    updated_at = current_timestamp
                where id = ? and claim_token = ? and status = 'CLAIMED'
                """, abbreviate(errorCode, 128), intentId, claimToken) == 1;
    }

    /**
     * WIKI-04：READY 提交后的唤醒——只把该资料待处理的意图拉到立即到期，
     * 不新建任务、不绕过调度器。处理中（CLAIMED）的意图不动，交给持有租约的一方。
     *
     * @return 是否确实有意图被唤醒
     */
    public boolean wakeDue(String workspaceId, String sourceId) {
        return jdbcTemplate.update("""
                update source_ready_intent
                set next_attempt_at = current_timestamp, updated_at = current_timestamp
                where workspace_id = ? and source_id = ? and status in ('PENDING', 'RETRY_WAIT')
                """, workspaceId, sourceId) > 0;
    }

    /** 单条意图的当前重试进度。 */
    public IntentProgress progressOf(String intentId) {
        return jdbcTemplate.query("""
                select attempt_count, max_attempts from source_ready_intent where id = ?
                """, rs -> rs.next() ? new IntentProgress(rs.getInt("attempt_count"), rs.getInt("max_attempts"))
                : null, intentId);
    }

    /**
     * 历史缺口回填：分页扫描 READY 状态但缺意图的资料，同一幂等身份补登记（FB-02.4）。
     * 游标按快照 id 递增，单页有上限：调用方循环翻页直到不足一页，公平且可暂停。
     */
    public BackfillPage backfillMissing(String ingestPolicyVersion, int limit, String afterSnapshotId) {
        String cursor = afterSnapshotId == null ? "" : afterSnapshotId;
        List<BackfillRow> missing = jdbcTemplate.query("""
                select s.workspace_id, s.id as source_id, ss.id as snapshot_id
                from source s
                join source_snapshot ss on ss.source_id = s.id
                  and ss.version_no = (select max(n.version_no) from source_snapshot n where n.source_id = s.id)
                where s.status = 'READY' and s.index_status = 'INDEXED'
                  and ss.id > ?
                  and not exists (
                      select 1 from source_ready_intent intent
                      where intent.workspace_id = s.workspace_id and intent.source_id = s.id
                        and intent.source_snapshot_id = ss.id
                        and intent.ingest_policy_version = ?
                  )
                order by ss.id
                limit ?
                """, (rs, rowNum) -> new BackfillRow(
                rs.getString("workspace_id"), rs.getString("source_id"), rs.getString("snapshot_id")),
                cursor, ingestPolicyVersion, limit);
        int recorded = 0;
        String lastSnapshotId = cursor;
        for (BackfillRow row : missing) {
            lastSnapshotId = row.snapshotId();
            if (recordIfAbsent(row.workspaceId(), row.sourceId(), row.snapshotId(), ingestPolicyVersion)) {
                recorded++;
            }
        }
        return new BackfillPage(recorded, missing.size(), lastSnapshotId);
    }

    /** 调度可观测性：各状态数量、最老未决年龄（FB-02.9）。 */
    public Map<String, Long> metrics() {
        Map<String, Long> counts = new LinkedHashMap<>();
        jdbcTemplate.query("""
                select status, count(*) as total,
                       coalesce(max(timestampdiff(second, created_at, current_timestamp)), 0) as oldest_age
                from source_ready_intent
                group by status
                """, rs -> {
            counts.put("ready-intent." + rs.getString("status") + ".count", rs.getLong("total"));
            counts.put("ready-intent." + rs.getString("status") + ".oldest_age_seconds", rs.getLong("oldest_age"));
        });
        return counts;
    }

    private String abbreviate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private record PendingIntent(
            String id, String workspaceId, String sourceId, String snapshotId, String policyVersion
    ) { }

    private record BackfillRow(String workspaceId, String sourceId, String snapshotId) { }

    /** 单条意图的重试进度。 */
    public record IntentProgress(int attempts, int maxAttempts) { }

    /** 回填一页的结果：新增意图数、本页扫描数、继续翻页用的快照游标。 */
    public record BackfillPage(int recorded, int scanned, String lastSnapshotId) { }

    /** 交给 A 消费侧的意图快照：身份 + 认领令牌。 */
    public record ReadySourceIntent(
            String intentId, String workspaceId, String sourceId, String snapshotId,
            String ingestPolicyVersion, String leaseOwner, String claimToken
    ) {
        /** 兼容旧构造（测试）：无令牌。 */
        public ReadySourceIntent(
                String intentId, String workspaceId, String sourceId, String snapshotId,
                String ingestPolicyVersion, String leaseOwner
        ) {
            this(intentId, workspaceId, sourceId, snapshotId, ingestPolicyVersion, leaseOwner, null);
        }
    }
}
