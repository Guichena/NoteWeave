package com.noteweave.storage;

import com.noteweave.common.BusinessException;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A 的文件对象生命周期端口适配（FB 组合接入，回应 A NEE-1/反向复查 #4）：
 * 将 source.storage.FileObjectLifecyclePort 的分相语义映射到 B 的
 * FileObjectLifecycleRepository。Bean 出现后 A 的 Gateway 自动离开 legacy 内联 SQL。
 * acquire/release 必须在调用方（A 的上传/报告/删除）业务事务内执行。
 *
 * <p>NR16：claimZeroReferenceDeletion 的「置 DELETE_CLAIMED + 写票据」两组库写
 * 包在适配器自己的短事务里——调用方没有事务时（清理监听器路径）两条语句不再分开提交，
 * 认领与票据原子出现，故障不再留下无票据的孤儿认领。</p>
 */
@Component
public class FileObjectLifecyclePortAdapter implements com.noteweave.source.storage.FileObjectLifecyclePort {

    private final FileObjectLifecycleRepository objects;
    private final TransactionTemplate transactions;
    private final String bucketSource;

    public FileObjectLifecyclePortAdapter(
            FileObjectLifecycleRepository objects,
            com.noteweave.config.NoteWeaveProperties properties,
            PlatformTransactionManager transactionManager) {
        this.objects = objects;
        this.transactions = new TransactionTemplate(transactionManager);
        this.bucketSource = properties.storage().minio().bucketSource();
    }

    @Override
    public long acquireReference(String workspaceId, String fileObjectId) {
        FileObjectLifecycleRepository.FileObjectRow row = objects.lockById(fileObjectId);
        if (row == null) {
            throw new BusinessException("FILE_OBJECT_NOT_FOUND", "文件对象不存在: " + fileObjectId);
        }
        if (!FileObjectLifecycleRepository.STATUS_ACTIVE.equals(row.status())
                || !objects.acquireReference(fileObjectId)) {
            throw new BusinessException("FILE_OBJECT_NOT_ACQUIRABLE",
                    "文件对象当前不可新增引用（删除中或已删除）: " + fileObjectId);
        }
        return row.refCount() + 1L;
    }

    @Override
    public long releaseReference(String workspaceId, String fileObjectId) {
        // 幂等释放：引用已为零或行不存在时返回当前计数，不重复扣减、不为负
        objects.releaseReference(fileObjectId);
        FileObjectLifecycleRepository.FileObjectRow row = objects.lockById(fileObjectId);
        return row == null ? 0L : row.refCount();
    }

    @Override
    public Optional<DeletionTicket> claimZeroReferenceDeletion(String workspaceId, String fileObjectId) {
        // A 已在同一删除事务内 release，这里只做零引用 CAS 认领，绝不再次减引用；
        // 认领与票据同事务（NR16），无事务调用方也原子
        return transactions.execute(status -> {
            FileObjectLifecycleRepository.FileObjectRow row = objects.lockById(fileObjectId);
            if (row == null || row.refCount() != 0
                    || !FileObjectLifecycleRepository.STATUS_ACTIVE.equals(row.status())) {
                return Optional.<DeletionTicket>empty();
            }
            FileObjectLifecycleRepository.DeleteTicket ticket =
                    objects.claimDeletion(fileObjectId, bucketSource);
            if (ticket == null) {
                return Optional.empty();
            }
            return Optional.of(new DeletionTicket(
                    ticket.fileObjectId(), ticket.bucket(), ticket.objectKey(), ticket.generation()));
        });
    }
}
