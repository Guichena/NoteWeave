package com.noteweave.storage;

import com.noteweave.common.BusinessException;
import com.noteweave.common.Ids;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 共享文件对象生命周期编排（FB-06）：事务内状态变更 + 事务外物理 IO。
 *
 * <p>上传/报告保存/Artifact 另存等全部获取引用入口经由
 * {@link #acquireOrPrepare}：ACTIVE 共享引用；DELETE_CLAIMED 明确待重试；
 * DELETED 以全新不可变 key 换代再发布；同 hash 并发由唯一约束选赢家。
 * 来源删除侧（A 的 SourceService）生产的真实合同是
 * source.storage.FileObjectLifecycleGateway → storage.FileObjectLifecyclePortAdapter
 * 两步：release 引用 + claimZeroReferenceDeletion 认领票据；物理删除由
 * {@link #reconcileOpenTickets} 按票据的不可变 key/generation 在事务外重试。
 * {@link #releaseAndClaimDeletion} 是两步的组合便捷方法，生产装配不经过
 * （A 走 PortAdapter），仅供测试与未来需要单步语义的调用方使用（AU-006 澄清）。
 *
 * <p>派生对象（解析文档/切片文本/向量批次/清单）由写入方在发布元数据前
 * {@link #registerDerived} 登记；发布检查失败的对象标记 ORPHANED；
 * 来源删除事务经 {@link #releaseDerivedForSource} 登记 RELEASED 清理意图（AU-001 接线），
 * 与 {@link #reconcileStaleDerivedRegistrations} 的过期登记对账共同保证竞态窗口内
 * 晚到登记的行也能收敛；物理删除统一由 {@link #reconcileStagedOrphans} 有界执行，
 * 不扫删未知桶对象。
 */
@Service
public class FileObjectLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(FileObjectLifecycleService.class);

    private final FileObjectLifecycleRepository objects;
    private final DerivedObjectRegistry derived;
    private final ObjectStorage storage;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactions;
    private final String bucketSource;
    private final String bucketDerived;
    private final boolean enabled;

    public FileObjectLifecycleService(
            FileObjectLifecycleRepository objects,
            DerivedObjectRegistry derived,
            ObjectStorage storage,
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager,
            com.noteweave.config.NoteWeaveProperties properties,
            @Value("${noteweave.storage.lifecycle-reconcile-enabled:true}") boolean enabled
    ) {
        this.objects = objects;
        this.derived = derived;
        this.storage = storage;
        this.jdbcTemplate = jdbcTemplate;
        this.transactions = new TransactionTemplate(transactionManager);
        this.bucketSource = properties.storage().minio().bucketSource();
        this.bucketDerived = properties.storage().minio().bucketDerived();
        this.enabled = enabled;
    }

    /**
     * 引用获取或准备（FB-06.1 合同）：在同一个短事务内完成行级决策。
     * candidateKeySupplier 在需要写入全新对象时提供候选不可变 key（事务外已写入暂存）。
     * 返回生效的 objectKey；DELETE_CLAIMED 冲突抛明确待重试异常，调用方稍后重试上传。
     */
    public AcquireResult acquireOrPrepare(String workspaceId, String sha256, long fileSize,
            String mimeType, String freshObjectKey) {
        return transactions.execute(status -> {
            FileObjectLifecycleRepository.FileObjectRow row = objects.lockByHash(workspaceId, sha256);
            // 同 hash 但大小不一致：极小概率哈希碰撞或数据损坏，宁可拒绝也不共享错对象
            // （与旧路径 UPLOAD_FILE_SIZE_MISMATCH 语义一致）
            if (row != null && row.fileSize() != fileSize) {
                throw new BusinessException("UPLOAD_FILE_SIZE_MISMATCH", "同哈希对象的文件大小不一致");
            }
            if (row == null) {
                String fileObjectId = Ids.newId();
                if (!objects.insertActive(fileObjectId, workspaceId, freshObjectKey, sha256, fileSize, mimeType)) {
                    // 并发赢家已插入：共享赢家行
                    row = objects.lockByHash(workspaceId, sha256);
                    if (row == null || !FileObjectLifecycleRepository.STATUS_ACTIVE.equals(row.status())) {
                        throw new BusinessException("FILE_OBJECT_ACQUIRE_CONFLICT",
                                "同 hash 对象并发发布失败，请重试上传");
                    }
                    objects.acquireReference(row.id());
                    return new AcquireResult(row.id(), row.objectKey(), false);
                }
                return new AcquireResult(fileObjectId, freshObjectKey, true);
            }
            switch (row.status()) {
                case FileObjectLifecycleRepository.STATUS_ACTIVE -> {
                    objects.acquireReference(row.id());
                    return new AcquireResult(row.id(), row.objectKey(), false);
                }
                case FileObjectLifecycleRepository.STATUS_DELETE_CLAIMED -> throw new BusinessException(
                        "FILE_OBJECT_DELETE_IN_PROGRESS",
                        "同 hash 对象正在等待物理删除，稍后重试上传");
                case FileObjectLifecycleRepository.STATUS_DELETED -> {
                    // 换代再发布：全新不可变 key，代次 +1，引用重置
                    if (!objects.republishDeletedWithNewKey(row.id(), freshObjectKey,
                            row.generation(), mimeType)) {
                        throw new BusinessException("FILE_OBJECT_REPUBLISH_CONFLICT",
                                "同 hash 对象换代发布竞争失败，请重试上传");
                    }
                    return new AcquireResult(row.id(), freshObjectKey, true);
                }
                default -> throw new BusinessException("FILE_OBJECT_STATE_INVALID",
                        "未知文件对象状态: " + row.status());
            }
        });
    }

    /**
     * 释放并认领删除的组合便捷方法（AU-006：生产不经过——A 的删除事务走
     * Gateway→PortAdapter 的 release + claimZeroReferenceDeletion 两步；本方法供
     * 测试与需要单步语义的调用方使用）。必须在调用方事务内执行。
     */
    public FileObjectLifecycleRepository.DeleteTicket releaseAndClaimDeletion(String fileObjectId) {
        return transactions.execute(status -> {
            if (!objects.releaseReference(fileObjectId)) {
                log.warn("文件对象引用释放失败（引用已为零或行不存在）: fileObjectId={}", fileObjectId);
            }
            FileObjectLifecycleRepository.FileObjectRow row = objects.lockById(fileObjectId);
            if (row == null || row.refCount() > 0
                    || !FileObjectLifecycleRepository.STATUS_ACTIVE.equals(row.status())) {
                return null;
            }
            return objects.claimDeletion(fileObjectId, bucketSource);
        });
    }

    /** 派生对象登记：发布元数据前调用；同 (bucket, key) 幂等。 */
    public boolean registerDerived(String workspaceId, String sourceId, String snapshotId,
            String batchVersion, String objectKind, String bucket, String objectKey, String sha256) {
        return derived.register(workspaceId, sourceId, snapshotId, batchVersion, objectKind,
                bucket, objectKey, sha256);
    }

    /** 派生对象发布前检查：来源未删除且快照/批次仍是当前代次。 */
    public boolean derivedTargetCurrent(String workspaceId, String sourceId, String snapshotId,
            String batchVersion) {
        return derived.targetCurrent(workspaceId, sourceId, snapshotId, batchVersion);
    }

    /** 发布检查失败的对象按孤儿登记，等待有界清理。 */
    public boolean markDerivedOrphan(String bucket, String objectKey) {
        return derived.markOrphan(bucket, objectKey);
    }

    /**
     * 来源删除的派生清理意图（A 删除事务内调用）：枚举该来源的登记对象，
     * 标记 RELEASED 并返回 (bucket, key) 清单；物理删除由 reconcile 执行。
     * 只处理被撤销来源/被取代代次的登记，其他来源共享对象不受影响。
     */
    public List<DerivedObjectRegistry.DerivedObject> releaseDerivedForSource(String workspaceId,
            String sourceId, String onlySnapshotId) {
        return derived.releaseForSource(workspaceId, sourceId, onlySnapshotId);
    }

    /**
     * 删除票据对账（有界）：按票据不可变 key 物理删除，成功关票据；
     * 行后来换代的新 key 不在票据内，不会被误删。失败计入票据重试。
     *
     * <p>NR16：关票据与置 DELETED 两组库写在同一短事务提交（此前分开提交，
     * 关票后故障会让行永久卡在 DELETE_CLAIMED）；同轮先为孤儿认领重建票据
     * （认领与票据写入分开提交的故障恢复），行不再永久卡死拒绝同 hash 上传。</p>
     */
    @Scheduled(fixedDelayString = "${noteweave.storage.ticket-reconcile-delay-ms:60000}")
    public void reconcileOpenTickets() {
        if (!enabled) {
            return;
        }
        for (FileObjectLifecycleRepository.FileObjectRow orphan : objects.claimedWithoutOpenTicket(20)) {
            FileObjectLifecycleRepository.DeleteTicket reopened = transactions.execute(
                    status -> objects.reopenTicket(orphan.id(), bucketSource));
            if (reopened != null) {
                log.warn("孤儿删除认领已重建票据: fileObjectId={}, objectKey={}",
                        orphan.id(), orphan.objectKey());
            }
        }
        for (FileObjectLifecycleRepository.DeleteTicket ticket : objects.openTickets(20)) {
            try {
                // ObjectStorage.delete 为 void：IO 异常走 catch 计入票据重试
                storage.delete(ticket.bucket(), ticket.objectKey());
                transactions.executeWithoutResult(status -> objects.completeDeletion(ticket.ticketId()));
            } catch (Exception failure) {
                objects.retryDeletion(ticket.ticketId(), String.valueOf(failure.getMessage()));
                log.warn("文件对象删除票据重试: ticketId={}, reason={}",
                        ticket.ticketId(), failure.getMessage());
            }
        }
    }

    /**
     * 孤儿派生对象有界清理（FB-06.2）：只清理登记为 ORPHANED/RELEASED 的对象，
     * 不扫删未知桶对象。RELEASED 由来源删除意图驱动；ORPHANED 由发布失败登记。
     */
    @Scheduled(fixedDelayString = "${noteweave.storage.orphan-reconcile-delay-ms:300000}")
    public void reconcileStagedOrphans() {
        if (!enabled) {
            return;
        }
        List<DerivedObjectRegistry.DerivedObject> due = derived.dueForCleanup(20);
        for (DerivedObjectRegistry.DerivedObject orphan : due) {
            try {
                if (storage.exists(orphan.bucket(), orphan.objectKey())) {
                    storage.delete(orphan.bucket(), orphan.objectKey());
                }
                derived.markCleaned(orphan.id());
            } catch (Exception failure) {
                derived.markCleanupFailed(orphan.id(), String.valueOf(failure.getMessage()));
                log.warn("孤儿派生对象清理失败: key={}, reason={}",
                        orphan.objectKey(), failure.getMessage());
            }
        }
    }

    /**
     * 过期登记对账（AU-001 第二部分）：删除事务释放意图之后才晚到写入的
     * REGISTERED 行（release 与 register 交错的窄窗口），按目标失效统一收敛为
     * ORPHANED 交给孤儿清理；来源/快照仍有效的登记不受影响。有界、不物理删除。
     */
    @Scheduled(fixedDelayString = "${noteweave.storage.derived-stale-reconcile-delay-ms:600000}")
    public void reconcileStaleDerivedRegistrations() {
        if (!enabled) {
            return;
        }
        for (DerivedObjectRegistry.DerivedObject stale : derived.staleRegistered(20)) {
            if (derived.markOrphan(stale.bucket(), stale.objectKey())) {
                log.warn("派生登记目标已失效，按孤儿收敛: sourceId={}, snapshotId={}, kind={}, key={}",
                        stale.sourceId(), stale.sourceSnapshotId(), stale.objectKind(), stale.objectKey());
            }
        }
    }

    /** 引用获取结果：activated=true 表示写入了全新对象 key（调用方需把暂存对象复制/提交到该 key）。 */
    public record AcquireResult(String fileObjectId, String objectKey, boolean activated) { }

    /** 生成来源准备结果：activated=true 表示新建/换代发布了全新 key（调用方需写入该 key）。 */
    public record PrepareResult(String fileObjectId, String objectKey, boolean activated) { }

    /**
     * 生成来源零引用准备（NR13）：与 {@link #acquireOrPrepare} 同一套状态决策，
     * 但不产生引用增量——ACTIVE 共享不 +1，新建/换代行 ref=0；引用由提交事务内的
     * acquireReference 增加（Research 报告的「prepare 零增量、CAS 赢家提交」协议）。
     * DELETE_CLAIMED 明确待重试；DELETED 以全新不可变 key 换代再发布，
     * 旧票据的不可变 key 不会误删新来源对象。
     */
    public PrepareResult prepareOrAcquire(String workspaceId, String sha256, long fileSize,
            String mimeType, String freshObjectKey) {
        return transactions.execute(status -> {
            FileObjectLifecycleRepository.FileObjectRow row = objects.lockByHash(workspaceId, sha256);
            if (row == null) {
                String fileObjectId = Ids.newId();
                if (!objects.insertActive(fileObjectId, workspaceId, freshObjectKey, sha256,
                        fileSize, mimeType, 0)) {
                    FileObjectLifecycleRepository.FileObjectRow winner = objects.lockByHash(workspaceId, sha256);
                    if (winner == null || !FileObjectLifecycleRepository.STATUS_ACTIVE.equals(winner.status())) {
                        throw new BusinessException("FILE_OBJECT_ACQUIRE_CONFLICT",
                                "同 hash 对象并发发布失败，请重试");
                    }
                    return new PrepareResult(winner.id(), winner.objectKey(), false);
                }
                return new PrepareResult(fileObjectId, freshObjectKey, true);
            }
            if (row.fileSize() != fileSize) {
                throw new BusinessException("UPLOAD_FILE_SIZE_MISMATCH", "同哈希对象的文件大小不一致");
            }
            switch (row.status()) {
                case FileObjectLifecycleRepository.STATUS_ACTIVE -> {
                    return new PrepareResult(row.id(), row.objectKey(), false);
                }
                case FileObjectLifecycleRepository.STATUS_DELETE_CLAIMED -> throw new BusinessException(
                        "FILE_OBJECT_DELETE_IN_PROGRESS",
                        "同 hash 对象正在等待物理删除，稍后重试保存");
                case FileObjectLifecycleRepository.STATUS_DELETED -> {
                    if (!objects.republishDeletedWithNewKey(row.id(), freshObjectKey,
                            row.generation(), mimeType, 0)) {
                        throw new BusinessException("FILE_OBJECT_REPUBLISH_CONFLICT",
                                "同 hash 对象换代发布竞争失败，请重试保存");
                    }
                    return new PrepareResult(row.id(), freshObjectKey, true);
                }
                default -> throw new BusinessException("FILE_OBJECT_STATE_INVALID",
                        "未知文件对象状态: " + row.status());
            }
        });
    }
}
