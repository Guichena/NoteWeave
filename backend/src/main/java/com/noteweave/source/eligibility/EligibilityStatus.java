package com.noteweave.source.eligibility;

/**
 * 来源资格状态。
 *
 * <p>USER 删除、COMPLIANCE 撤销、暂不可用、已更新（快照过期）、内部来源缺失是互不相同的状态，
 * 不能合并成一个 DELETED 或 UNAVAILABLE。</p>
 */
public enum EligibilityStatus {
    READY,
    /** PENDING/PARSING/FAILED 等暂时不可用，可能恢复。 */
    TEMPORARILY_UNAVAILABLE,
    /** 用户主动删除，历史 Note 正文保留，原文引用失效。 */
    USER_DELETED,
    /** 合规撤销，历史版本也不可绕过，正文与文件不得继续暴露。 */
    COMPLIANCE_REVOKED,
    /** 内部来源记录缺失；不能自动当作外部来源。 */
    MISSING_INTERNAL,
    /** 来源属于其他工作台；不泄露来源存在性。 */
    FOREIGN_WORKSPACE,
    /** 来源范围或依赖 schema 无法解析。 */
    UNKNOWN_SCHEMA,
    /** 引用的快照不再是当前可用版本（来源已更新）。 */
    STALE_SNAPSHOT
}
