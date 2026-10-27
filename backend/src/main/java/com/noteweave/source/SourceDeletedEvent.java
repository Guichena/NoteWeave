package com.noteweave.source;

import java.util.List;

/**
 * 资料删除事件。
 *
 * <p>reason 区分用户主动删除与合规删除：USER 表示用户主动删除，笔记继续保留；
 * COMPLIANCE 表示合规删除，引用该资料的笔记已退出可用状态。
 * transition 区分首次删除与 USER 升级 COMPLIANCE；升级事件不携带对象键，
 * 清理监听器不会重复安排物理清理。事件保持值语义（无随机 ID），
 * 清理幂等以 source + cleanup_type + 对象键的业务身份为准。</p>
 */
public record SourceDeletedEvent(
        String workspaceId,
        String sourceId,
        List<String> objectKeys,
        String reason,
        String transition
) {
    public static final String REASON_USER = "USER";
    public static final String REASON_COMPLIANCE = "COMPLIANCE";
    public static final String TRANSITION_FIRST_DELETE = "FIRST_DELETE";
    public static final String TRANSITION_UPGRADE_TO_COMPLIANCE = "UPGRADE_TO_COMPLIANCE";

    public SourceDeletedEvent {
        objectKeys = objectKeys == null ? List.of() : objectKeys.stream()
                .filter(key -> key != null && !key.isBlank())
                .distinct()
                .toList();
        // 兼容历史事件：未提供删除原因时按用户主动删除处理
        reason = reason == null || reason.isBlank() ? REASON_USER : reason.trim();
        transition = transition == null || transition.isBlank()
                ? TRANSITION_FIRST_DELETE : transition.trim();
    }

    /** 兼容原有构造方式，默认按用户主动删除的首次删除处理。 */
    public SourceDeletedEvent(String workspaceId, String sourceId, List<String> objectKeys) {
        this(workspaceId, sourceId, objectKeys, REASON_USER);
    }

    public SourceDeletedEvent(String workspaceId, String sourceId, List<String> objectKeys, String reason) {
        this(workspaceId, sourceId, objectKeys, reason, TRANSITION_FIRST_DELETE);
    }

    /** 兼容原有单对象键构造方式。 */
    public SourceDeletedEvent(String workspaceId, String sourceId, String objectKey) {
        this(workspaceId, sourceId, objectKey, REASON_USER);
    }

    public SourceDeletedEvent(String workspaceId, String sourceId, String objectKey, String reason) {
        this(workspaceId, sourceId,
                objectKey == null || objectKey.isBlank() ? List.of() : List.of(objectKey),
                reason);
    }

    /** 升级删除不携带对象键，避免重复安排物理清理。 */
    public static SourceDeletedEvent upgradeToCompliance(String workspaceId, String sourceId) {
        return new SourceDeletedEvent(workspaceId, sourceId, List.of(),
                REASON_COMPLIANCE, TRANSITION_UPGRADE_TO_COMPLIANCE);
    }

    /**
     * 升级删除携带快照/原件键：用于按 NOT EXISTS 幂等补登记缺失的清理意图，
     * 不重复释放引用；已登记（含已完成）的键不会重复插入。
     */
    public static SourceDeletedEvent upgradeToCompliance(
            String workspaceId, String sourceId, List<String> compensationObjectKeys) {
        return new SourceDeletedEvent(workspaceId, sourceId, compensationObjectKeys,
                REASON_COMPLIANCE, TRANSITION_UPGRADE_TO_COMPLIANCE);
    }

    public boolean isUpgrade() {
        return TRANSITION_UPGRADE_TO_COMPLIANCE.equals(transition);
    }
}
