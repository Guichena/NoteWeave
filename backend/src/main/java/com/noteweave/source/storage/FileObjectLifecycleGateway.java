package com.noteweave.source.storage;

import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 文件对象生命周期网关：B 侧实现存在时走端口，否则回退到既有内联 SQL。
 *
 * <p>回退路径就是改造前的直接引用计数 SQL，不是新的并发协议；
 * 端口接线状态会记录在交接 completion-matrix（INTEGRATION_PENDING），
 * 不把回退路径当成协议已接通。回退路径零引用判断存在检查-删除竞态，
 * 该竞态由 B 侧票据实现消除。</p>
 */
@Component
public class FileObjectLifecycleGateway {

    private static final Logger log = LoggerFactory.getLogger(FileObjectLifecycleGateway.class);

    private final ObjectProvider<FileObjectLifecyclePort> portProvider;
    private final JdbcTemplate jdbcTemplate;
    private volatile boolean legacyPathLogged;

    public FileObjectLifecycleGateway(
            ObjectProvider<FileObjectLifecyclePort> portProvider,
            JdbcTemplate jdbcTemplate
    ) {
        this.portProvider = portProvider;
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean portWired() {
        return portProvider.getIfAvailable() != null;
    }

    /** 当前事务内 +1；回退路径为既有内联更新。返回调整后的计数。 */
    public long acquire(String workspaceId, String fileObjectId) {
        FileObjectLifecyclePort port = portProvider.getIfAvailable();
        if (port != null) {
            return port.acquireReference(workspaceId, fileObjectId);
        }
        logLegacyOnce();
        Long updated = jdbcTemplate.queryForObject("""
                select ref_count + 1 from file_object where id = ?
                """, Long.class, fileObjectId);
        jdbcTemplate.update("update file_object set ref_count = ref_count + 1 where id = ?",
                fileObjectId);
        return updated == null ? -1 : updated;
    }

    /** 当前事务内 -1（下限 0）；返回调整后的计数。 */
    public long release(String workspaceId, String fileObjectId) {
        FileObjectLifecyclePort port = portProvider.getIfAvailable();
        if (port != null) {
            return port.releaseReference(workspaceId, fileObjectId);
        }
        logLegacyOnce();
        jdbcTemplate.update("""
                update file_object
                set ref_count = case when ref_count > 0 then ref_count - 1 else 0 end
                where id = ?
                """, fileObjectId);
        Integer remaining = jdbcTemplate.queryForObject(
                "select ref_count from file_object where id = ?", Integer.class, fileObjectId);
        return remaining == null ? -1 : remaining;
    }

    /**
     * 零引用删除认领。回退路径按 object_key 检查活跃引用（存在检查-删除竞态，
     * 保持既有行为）；端口路径由 B 实现保证票据原子性。
     */
    public Optional<FileObjectLifecyclePort.DeletionTicket> claimZeroReferenceDeletion(
            String workspaceId, String fileObjectId, String bucket, String objectKey) {
        FileObjectLifecyclePort port = portProvider.getIfAvailable();
        if (port != null) {
            return port.claimZeroReferenceDeletion(workspaceId, fileObjectId);
        }
        logLegacyOnce();
        Integer activeReferences = jdbcTemplate.queryForObject("""
                select count(*) from file_object
                where object_key = ? and ref_count > 0
                """, Integer.class, objectKey);
        if (activeReferences != null && activeReferences > 0) {
            return Optional.empty();
        }
        return Optional.of(new FileObjectLifecyclePort.DeletionTicket(
                fileObjectId, bucket, objectKey, 0L));
    }

    private void logLegacyOnce() {
        if (!legacyPathLogged) {
            legacyPathLogged = true;
            log.info("FileObjectLifecyclePort not wired; using legacy inline reference SQL");
        }
    }
}
