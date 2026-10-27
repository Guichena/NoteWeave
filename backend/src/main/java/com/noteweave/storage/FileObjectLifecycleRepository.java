package com.noteweave.storage;

import com.noteweave.common.Ids;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 共享 file_object 生命周期仓储（FB-06，合同见 contracts-ready.md）。
 *
 * <p>状态机 ACTIVE → DELETE_CLAIMED → DELETED；保留 V003 的
 * unique(workspace_id, sha256)，并发插入由唯一约束选赢家。所有变更方法都必须在
 * 业务短事务内调用；物理对象 IO 永远在事务外，经删除票据绑定的不可变
 * bucket/key/generation 执行，过期票据不能删除后来新代次的 key。
 */
@Repository
public class FileObjectLifecycleRepository {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DELETE_CLAIMED = "DELETE_CLAIMED";
    public static final String STATUS_DELETED = "DELETED";

    private final JdbcTemplate jdbcTemplate;

    public FileObjectLifecycleRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 行锁读取同 hash 对象（事务内）：不存在返回 null。 */
    public FileObjectRow lockByHash(String workspaceId, String sha256) {
        return jdbcTemplate.query("""
                select id, workspace_id, object_key, file_size, ref_count, status, object_generation
                from file_object
                where workspace_id = ? and sha256 = ?
                for update
                """, rs -> rs.next() ? new FileObjectRow(
                rs.getString("id"), rs.getString("workspace_id"), rs.getString("object_key"),
                rs.getLong("file_size"), rs.getInt("ref_count"), rs.getString("status"),
                rs.getLong("object_generation")) : null, workspaceId, sha256);
    }

    public FileObjectRow lockById(String fileObjectId) {
        return jdbcTemplate.query("""
                select id, workspace_id, object_key, file_size, ref_count, status, object_generation
                from file_object where id = ? for update
                """, rs -> rs.next() ? new FileObjectRow(
                rs.getString("id"), rs.getString("workspace_id"), rs.getString("object_key"),
                rs.getLong("file_size"), rs.getInt("ref_count"), rs.getString("status"),
                rs.getLong("object_generation")) : null, fileObjectId);
    }

    /**
     * 新建 ACTIVE 对象。同 hash 并发插入由唯一约束选赢家；
     * 输家收到 false 后按独立暂存回收，不得复用赢家 key 之外的路径。
     */
    public boolean insertActive(String fileObjectId, String workspaceId, String objectKey,
            String sha256, long fileSize, String mimeType) {
        return insertActive(fileObjectId, workspaceId, objectKey, sha256, fileSize, mimeType, 1);
    }

    /** NR13：生成来源准备路径的零引用新建（引用由提交事务内的 acquire 增加）。 */
    public boolean insertActive(String fileObjectId, String workspaceId, String objectKey,
            String sha256, long fileSize, String mimeType, int initialRefCount) {
        try {
            return jdbcTemplate.update("""
                    insert into file_object(
                        id, workspace_id, object_key, sha256, file_size, mime_type, ref_count,
                        status, object_generation)
                    values (?, ?, ?, ?, ?, ?, ?, 'ACTIVE', 1)
                    """, fileObjectId, workspaceId, objectKey, sha256, fileSize, mimeType,
                    Math.max(0, initialRefCount)) == 1;
        } catch (DuplicateKeyException duplicate) {
            return false;
        }
    }

    /** 既有 ACTIVE 对象加引用；DELETE_CLAIMED/DELETED 拒绝新增引用。 */
    public boolean acquireReference(String fileObjectId) {
        return jdbcTemplate.update("""
                update file_object set ref_count = ref_count + 1
                where id = ? and status = 'ACTIVE'
                """, fileObjectId) == 1;
    }

    /** 释放引用：禁止负数；同一来源删除只减一次由调用方删除事务保证。 */
    public boolean releaseReference(String fileObjectId) {
        return jdbcTemplate.update("""
                update file_object set ref_count = ref_count - 1
                where id = ? and ref_count > 0
                """, fileObjectId) == 1;
    }

    /**
     * DELETED 行的受控再发布：同 hash 新上传换全新不可变 key，代次 +1，引用重置为 1。
     * 只接受 status='DELETED'：DELETE_CLAIMED 中的对象必须等删除完成或票据过期处理。
     */
    public boolean republishDeletedWithNewKey(String fileObjectId, String newObjectKey,
            long expectedGeneration, String mimeType) {
        return republishDeletedWithNewKey(fileObjectId, newObjectKey, expectedGeneration, mimeType, 1);
    }

    /** NR13：生成来源准备路径的零引用换代再发布（引用由提交事务内的 acquire 增加）。 */
    public boolean republishDeletedWithNewKey(String fileObjectId, String newObjectKey,
            long expectedGeneration, String mimeType, int resetRefCount) {
        return jdbcTemplate.update("""
                update file_object
                set object_key = ?, status = 'ACTIVE', object_generation = object_generation + 1,
                    ref_count = ?, mime_type = coalesce(?, mime_type)
                where id = ? and status = 'DELETED' and object_generation = ?
                """, newObjectKey, Math.max(0, resetRefCount), mimeType, fileObjectId,
                expectedGeneration) == 1;
    }

    /**
     * 零引用 CAS 认领删除：置 DELETE_CLAIMED 并写入不可变删除票据。
     * 票据携带认领时的 bucket/key/generation；对象 IO 由调用方在事务外按票据执行。
     */
    public DeleteTicket claimDeletion(String fileObjectId, String bucket) {
        int claimed = jdbcTemplate.update("""
                update file_object set status = 'DELETE_CLAIMED'
                where id = ? and status = 'ACTIVE' and ref_count = 0
                """, fileObjectId);
        if (claimed != 1) {
            return null;
        }
        FileObjectRow row = lockById(fileObjectId);
        String ticketId = Ids.newId();
        jdbcTemplate.update("""
                insert into file_object_delete_ticket(
                    id, file_object_id, workspace_id, bucket, object_key, object_generation)
                values (?, ?, ?, ?, ?, ?)
                """, ticketId, fileObjectId, row.workspaceId(), bucket, row.objectKey(),
                row.generation());
        return new DeleteTicket(ticketId, fileObjectId, row.workspaceId(), bucket,
                row.objectKey(), row.generation());
    }

    /** 票据完成：对象按票据 key 删除成功后，行进入 DELETED。只更新票据所属代次。 */
    public boolean completeDeletion(String ticketId) {
        boolean ticketClosed = jdbcTemplate.update("""
                update file_object_delete_ticket
                set completed_at = current_timestamp, attempts = attempts + 1, last_error = null
                where id = ? and completed_at is null
                """, ticketId) == 1;
        if (!ticketClosed) {
            return false;
        }
        return jdbcTemplate.update("""
                update file_object
                set status = 'DELETED', ref_count = 0
                where exists (
                    select 1 from file_object_delete_ticket t
                    where t.id = ? and t.file_object_id = file_object.id
                      and t.object_generation = file_object.object_generation
                      and t.completed_at is not null
                )
                """, ticketId) == 1;
    }

    /** 票据失败：保留重试身份，行锁语义不变。 */
    public boolean retryDeletion(String ticketId, String error) {
        return jdbcTemplate.update("""
                update file_object_delete_ticket
                set attempts = attempts + 1, failed_at = current_timestamp,
                    last_error = coalesce(?, last_error)
                where id = ? and completed_at is null
                """, abbreviate(error, 500), ticketId) == 1;
    }

    /**
     * NR16：无未完成票据的 DELETE_CLAIMED 行——认领 UPDATE 与票据 INSERT 分开提交时
     * 故障留下的孤儿认领（无票据则对账循环永远不会处理，行永久卡死拒绝同 hash 上传）。
     */
    public List<FileObjectRow> claimedWithoutOpenTicket(int limit) {
        return jdbcTemplate.query("""
                select f.id, f.workspace_id, f.object_key, f.file_size, f.ref_count,
                       f.status, f.object_generation
                from file_object f
                where f.status = 'DELETE_CLAIMED' and f.ref_count = 0
                  and not exists (
                      select 1 from file_object_delete_ticket t
                      where t.file_object_id = f.id and t.completed_at is null
                  )
                order by f.id
                limit ?
                """, (rs, rowNum) -> new FileObjectRow(
                rs.getString("id"), rs.getString("workspace_id"), rs.getString("object_key"),
                rs.getLong("file_size"), rs.getInt("ref_count"), rs.getString("status"),
                rs.getLong("object_generation")), Math.max(1, limit));
    }

    /** NR16：为孤儿认领重建未完成票据（幂等；行已非 DELETE_CLAIMED 或已有票据时返回 null）。 */
    public DeleteTicket reopenTicket(String fileObjectId, String bucket) {
        FileObjectRow row = lockById(fileObjectId);
        if (row == null || !STATUS_DELETE_CLAIMED.equals(row.status())) {
            return null;
        }
        Integer open = jdbcTemplate.queryForObject("""
                select count(*) from file_object_delete_ticket
                where file_object_id = ? and completed_at is null
                """, Integer.class, fileObjectId);
        if (open != null && open > 0) {
            return null;
        }
        String ticketId = Ids.newId();
        jdbcTemplate.update("""
                insert into file_object_delete_ticket(
                    id, file_object_id, workspace_id, bucket, object_key, object_generation)
                values (?, ?, ?, ?, ?, ?)
                """, ticketId, fileObjectId, row.workspaceId(), bucket, row.objectKey(),
                row.generation());
        return new DeleteTicket(ticketId, fileObjectId, row.workspaceId(), bucket,
                row.objectKey(), row.generation());
    }

    /** 未完成票据（对账循环用，有界）。 */
    public List<DeleteTicket> openTickets(int limit) {
        return jdbcTemplate.query("""
                select id, file_object_id, workspace_id, bucket, object_key, object_generation
                from file_object_delete_ticket
                where completed_at is null
                order by claimed_at, id limit ?
                """, (rs, rowNum) -> new DeleteTicket(
                rs.getString("id"), rs.getString("file_object_id"), rs.getString("workspace_id"),
                rs.getString("bucket"), rs.getString("object_key"), rs.getLong("object_generation")),
                Math.max(1, limit));
    }

    private String abbreviate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    /** file_object 行快照。 */
    public record FileObjectRow(
            String id, String workspaceId, String objectKey, long fileSize,
            int refCount, String status, long generation
    ) { }

    /** 不可变删除票据：物理删除只能按这里的 bucket/key/generation 执行。 */
    public record DeleteTicket(
            String ticketId, String fileObjectId, String workspaceId,
            String bucket, String objectKey, long generation
    ) { }
}
