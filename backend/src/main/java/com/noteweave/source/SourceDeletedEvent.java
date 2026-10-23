package com.noteweave.source;

import java.util.List;

/**
 * 资料删除事件。
 *
 * <p>reason 区分用户主动删除与合规删除：USER 表示用户主动删除，笔记继续保留；
 * COMPLIANCE 表示合规删除，引用该资料的笔记已退出可用状态。</p>
 */
public record SourceDeletedEvent(
        String workspaceId,
        String sourceId,
        List<String> objectKeys,
        String reason
) {
    public static final String REASON_USER = "USER";
    public static final String REASON_COMPLIANCE = "COMPLIANCE";

    public SourceDeletedEvent {
        objectKeys = objectKeys == null ? List.of() : objectKeys.stream()
                .filter(key -> key != null && !key.isBlank())
                .distinct()
                .toList();
        // 兼容历史事件：未提供删除原因时按用户主动删除处理
        reason = reason == null || reason.isBlank() ? REASON_USER : reason.trim();
    }

    /** 兼容原有构造方式，默认按用户主动删除处理。 */
    public SourceDeletedEvent(String workspaceId, String sourceId, List<String> objectKeys) {
        this(workspaceId, sourceId, objectKeys, REASON_USER);
    }

    /** 兼容原有单对象键构造方式，默认按用户主动删除处理。 */
    public SourceDeletedEvent(String workspaceId, String sourceId, String objectKey) {
        this(workspaceId, sourceId, objectKey, REASON_USER);
    }

    public SourceDeletedEvent(String workspaceId, String sourceId, String objectKey, String reason) {
        this(workspaceId, sourceId,
                objectKey == null || objectKey.isBlank() ? List.of() : List.of(objectKey),
                reason);
    }
}
