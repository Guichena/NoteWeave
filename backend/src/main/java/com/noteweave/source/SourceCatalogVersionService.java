package com.noteweave.source;

import com.noteweave.common.BusinessException;
import java.util.LinkedHashSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 工作台资料目录的版本号，资料列表缓存按它失效。
 * <p>
 * 在事务里调用 bump 时，版本号在事务提交后用单独的短事务递增。原因有两个：
 * 读到新版本号的请求一定能读到已提交的数据；并且不在业务事务里持有工作台行的排他锁。
 * 上传、解析各阶段的事务都会插入引用工作台的行（外键会对工作台行加共享锁），
 * 如果同时在事务内更新工作台行，多个资料并发处理时会互相等待对方的共享锁而死锁。
 */
@Component
public class SourceCatalogVersionService {

    private static final Logger log = LoggerFactory.getLogger(SourceCatalogVersionService.class);

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate separateTransaction;

    public SourceCatalogVersionService(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, null);
    }

    @Autowired
    public SourceCatalogVersionService(JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        if (transactionManager == null) {
            this.separateTransaction = null;
        } else {
            this.separateTransaction = new TransactionTemplate(transactionManager);
            this.separateTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        }
    }

    public long current(String workspaceId) {
        Long version = jdbcTemplate.query("""
                select source_catalog_version from workspace where id = ?
                """, rs -> rs.next() ? rs.getLong("source_catalog_version") : null, workspaceId);
        if (version == null) {
            throw new BusinessException("WORKSPACE_NOT_FOUND", "工作台不存在");
        }
        return version;
    }

    public void bump(String workspaceId) {
        if (separateTransaction == null || !TransactionSynchronizationManager.isSynchronizationActive()) {
            increment(workspaceId);
            return;
        }
        current(workspaceId);
        pendingBumps().add(workspaceId);
    }

    private void increment(String workspaceId) {
        int updated = jdbcTemplate.update("""
                update workspace
                set source_catalog_version = source_catalog_version + 1
                where id = ?
                """, workspaceId);
        if (updated != 1) {
            throw new BusinessException("WORKSPACE_NOT_FOUND", "工作台不存在");
        }
    }

    /** 同一个事务里多次 bump 同一个工作台，提交后只递增一次。 */
    @SuppressWarnings("unchecked")
    private Set<String> pendingBumps() {
        Object resource = TransactionSynchronizationManager.getResource(this);
        if (resource instanceof Set<?> pending) {
            return (Set<String>) pending;
        }
        Set<String> pending = new LinkedHashSet<>();
        TransactionSynchronizationManager.bindResource(this, pending);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                for (String workspaceId : pending) {
                    try {
                        separateTransaction.executeWithoutResult(status -> increment(workspaceId));
                    } catch (RuntimeException ex) {
                        // 递增失败只影响缓存命中，列表缓存到期后会重新读取
                        log.warn("Source catalog version bump failed after commit: workspaceId={}: {}",
                                workspaceId, ex.getMessage());
                    }
                }
            }

            @Override
            public void afterCompletion(int status) {
                TransactionSynchronizationManager.unbindResourceIfPossible(SourceCatalogVersionService.this);
            }
        });
        return pending;
    }
}
