package com.noteweave.storage;

import com.noteweave.common.Ids;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 派生对象登记表（FB-06.2，迁移 V207）。
 *
 * <p>解析文档、切片文本、向量批次与清单等派生物按
 * workspace/source/snapshot/batchVersion/objectKind/bucket/key 登记；
 * 删除与晚到写入对账都走登记表，不靠猜字符串路径。批次对象不能只按
 * sourceId 前缀全部删：releaseForSource 支持 onlySnapshotId 精确圈定被取代代次。
 */
@Repository
public class DerivedObjectRegistry {

    public static final String STATUS_REGISTERED = "REGISTERED";
    public static final String STATUS_RELEASED = "RELEASED";
    public static final String STATUS_ORPHANED = "ORPHANED";

    private final JdbcTemplate jdbcTemplate;

    public DerivedObjectRegistry(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 登记（幂等）：同 (bucket, key) 已存在时不覆盖既有状态。 */
    public boolean register(String workspaceId, String sourceId, String snapshotId,
            String batchVersion, String objectKind, String bucket, String objectKey, String sha256) {
        try {
            return jdbcTemplate.update("""
                    insert into derived_file_object(
                        id, workspace_id, source_id, source_snapshot_id, batch_version,
                        object_kind, bucket, object_key, sha256, status)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, 'REGISTERED')
                    """, Ids.newId(), workspaceId, sourceId, snapshotId, batchVersion,
                    objectKind, bucket, objectKey, sha256) == 1;
        } catch (DuplicateKeyException duplicate) {
            return false;
        }
    }

    /** 发布前检查：来源未删除，且快照仍是当前版本（batchVersion 提供时还需匹配）。 */
    public boolean targetCurrent(String workspaceId, String sourceId, String snapshotId,
            String batchVersion) {
        StringBuilder sql = new StringBuilder("""
                select count(*) from source s
                join source_snapshot ss on ss.id = ? and ss.source_id = s.id
                where s.workspace_id = ? and s.id = ? and s.status <> 'DELETED'
                  and ss.version_no = (select max(n.version_no) from source_snapshot n where n.source_id = s.id)
                """);
        if (batchVersion != null && !batchVersion.isBlank()) {
            sql.append(" and ss.embedding_batch_version = ?");
            Integer count = jdbcTemplate.queryForObject(sql.toString(), Integer.class,
                    snapshotId, workspaceId, sourceId, batchVersion);
            return count != null && count == 1;
        }
        Integer count = jdbcTemplate.queryForObject(sql.toString(), Integer.class,
                snapshotId, workspaceId, sourceId);
        return count != null && count == 1;
    }

    /** 发布失败/晚到写入：失去发布机会的对象按孤儿登记，等待有界清理。 */
    public boolean markOrphan(String bucket, String objectKey) {
        return jdbcTemplate.update("""
                update derived_file_object
                set status = 'ORPHANED', updated_at = current_timestamp
                where bucket = ? and object_key = ? and status = 'REGISTERED'
                """, bucket, objectKey) == 1;
    }

    /**
     * 来源删除的清理意图（A 删除事务内调用）：登记对象标记 RELEASED 并返回清单。
     * onlySnapshotId 非空时只圈定该快照（被取代代次的批次），保护新代次与其他来源共享对象。
     */
    public List<DerivedObject> releaseForSource(String workspaceId, String sourceId, String onlySnapshotId) {
        int released = jdbcTemplate.update("""
                update derived_file_object
                set status = 'RELEASED', updated_at = current_timestamp
                where workspace_id = ? and source_id = ? and status = 'REGISTERED'
                  and (? is null or source_snapshot_id = ?)
                """, workspaceId, sourceId, onlySnapshotId, onlySnapshotId);
        if (released == 0) {
            return List.of();
        }
        return jdbcTemplate.query("""
                select id, workspace_id, source_id, source_snapshot_id, batch_version,
                       object_kind, bucket, object_key, sha256, status
                from derived_file_object
                where workspace_id = ? and source_id = ? and status = 'RELEASED'
                """, (rs, rowNum) -> new DerivedObject(
                rs.getString("id"), rs.getString("workspace_id"), rs.getString("source_id"),
                rs.getString("source_snapshot_id"), rs.getString("batch_version"),
                rs.getString("object_kind"), rs.getString("bucket"), rs.getString("object_key"),
                rs.getString("sha256"), rs.getString("status")),
                workspaceId, sourceId);
    }

    /** 待清理（有界）：ORPHANED 与 RELEASED，按登记顺序。 */
    public List<DerivedObject> dueForCleanup(int limit) {
        return jdbcTemplate.query("""
                select id, workspace_id, source_id, source_snapshot_id, batch_version,
                       object_kind, bucket, object_key, sha256, status
                from derived_file_object
                where status in ('ORPHANED', 'RELEASED')
                order by updated_at, id limit ?
                """, (rs, rowNum) -> new DerivedObject(
                rs.getString("id"), rs.getString("workspace_id"), rs.getString("source_id"),
                rs.getString("source_snapshot_id"), rs.getString("batch_version"),
                rs.getString("object_kind"), rs.getString("bucket"), rs.getString("object_key"),
                rs.getString("sha256"), rs.getString("status")),
                Math.max(1, limit));
    }

    /**
     * 过期登记对账（AU-001 第二部分）：来源已删除/缺失，或快照不再是当前代次的
     * REGISTERED 行。覆盖删除事务与晚到写入交错的窄窗口——release 意图先于 register
     * 提交时，登记行不会带 RELEASED，只能由这里按目标失效统一收敛为孤儿。
     * source_id 为空的登记无法判定归属，不纳入。
     */
    public List<DerivedObject> staleRegistered(int limit) {
        return jdbcTemplate.query("""
                select d.id, d.workspace_id, d.source_id, d.source_snapshot_id, d.batch_version,
                       d.object_kind, d.bucket, d.object_key, d.sha256, d.status
                from derived_file_object d
                left join source s on s.workspace_id = d.workspace_id and s.id = d.source_id
                left join source_snapshot ss on ss.id = d.source_snapshot_id
                where d.status = 'REGISTERED' and d.source_id is not null
                  and (
                       s.id is null or s.status = 'DELETED'
                    or ss.id is null
                    or ss.version_no <> (select max(n.version_no) from source_snapshot n
                                         where n.source_id = d.source_id)
                  )
                order by d.created_at, d.id
                limit ?
                """, (rs, rowNum) -> new DerivedObject(
                rs.getString("id"), rs.getString("workspace_id"), rs.getString("source_id"),
                rs.getString("source_snapshot_id"), rs.getString("batch_version"),
                rs.getString("object_kind"), rs.getString("bucket"), rs.getString("object_key"),
                rs.getString("sha256"), rs.getString("status")),
                Math.max(1, limit));
    }

    /** 清理完成：物理删除已执行，登记保留为已完成事实（不再出现在待清理查询）。 */
    public boolean markCleaned(String id) {
        return jdbcTemplate.update("""
                update derived_file_object
                set status = 'CLEANED', updated_at = current_timestamp
                where id = ? and status in ('ORPHANED', 'RELEASED')
                """, id) == 1;
    }

    /** 清理失败：保留状态，touch 更新时间等待下轮有界重试；原因进日志不进表。 */
    public boolean markCleanupFailed(String id, String error) {
        return jdbcTemplate.update("""
                update derived_file_object
                set updated_at = current_timestamp
                where id = ? and status in ('ORPHANED', 'RELEASED')
                """, id) == 1;
    }

    /** 登记对象视图。 */
    public record DerivedObject(
            String id, String workspaceId, String sourceId, String sourceSnapshotId,
            String batchVersion, String objectKind, String bucket, String objectKey,
            String sha256, String status
    ) { }
}
