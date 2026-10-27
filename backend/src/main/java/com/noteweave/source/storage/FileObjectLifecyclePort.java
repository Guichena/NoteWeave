package com.noteweave.source.storage;

import java.util.Optional;

/**
 * 文件对象生命周期端口（A 定义调用合同，B 提供 JDBC/存储实现）。
 *
 * <p>引用增量必须与业务行（source/snapshot/报告来源）在同一数据库事务内提交；
 * 零引用物理删除必须先取得绑定对象键与代次的删除票据，票据生成后
 * 同一对象代次不能再增加引用，新代次对象使用不同键。
 * 物理删除本身在事务外执行，实现不得在持有行锁时调用对象存储。
 * 派生对象（解析 JSON/提取正文/embedding 批次）由写入方在写入时登记，
 * 本端口不负责按路径猜测枚举。</p>
 */
public interface FileObjectLifecyclePort {

    /**
     * 在当前事务内为文件对象增加一次引用；返回调整后的引用计数。
     *
     * @throws IllegalStateException 当前没有活动事务
     * @throws IllegalArgumentException 对象不存在或已被删除票据封存
     */
    long acquireReference(String workspaceId, String fileObjectId);

    /**
     * 在当前事务内释放一次引用（下限 0）；返回调整后的引用计数。
     * USER→COMPLIANCE 升级等不应重复释放的路径由调用方保证只调用一次。
     */
    long releaseReference(String workspaceId, String fileObjectId);

    /**
     * 零引用删除认领：原子地把 ref_count=0 的对象标记为待物理删除并返回票据；
     * 仍有引用或票据已签发时返回空，调用方跳过物理删除。
     * 票据与对象键、代次绑定，旧票据不能删除新代次的键。
     */
    Optional<DeletionTicket> claimZeroReferenceDeletion(String workspaceId, String fileObjectId);

    /** 物理删除票据；objectKey 与 generation 一起唯一确定被删对象。 */
    record DeletionTicket(String fileObjectId, String bucket, String objectKey, long generation) {
    }
}
