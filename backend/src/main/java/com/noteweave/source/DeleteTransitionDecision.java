package com.noteweave.source;

/**
 * 删除请求对既有删除事实的转移判定。
 *
 * <p>三个分支：首次删除、重复删除（幂等返回既有事实）、USER 升级为 COMPLIANCE。
 * COMPLIANCE 不能降级回 USER；重复请求返回既有删除事实而不是再次执行副作用。</p>
 */
public record DeleteTransitionDecision(Kind kind, String effectiveReason) {

    public enum Kind {
        FIRST_DELETE,
        ALREADY_DELETED,
        UPGRADE_TO_COMPLIANCE
    }

    public static final DeleteTransitionDecision FIRST_DELETE_USER =
            new DeleteTransitionDecision(Kind.FIRST_DELETE, SourceService.DELETE_REASON_USER);
    public static final DeleteTransitionDecision FIRST_DELETE_COMPLIANCE =
            new DeleteTransitionDecision(Kind.FIRST_DELETE, SourceService.DELETE_REASON_COMPLIANCE);

    /**
     * 纯函数：依据当前状态、既有原因与请求原因计算转移。
     *
     * @param currentStatus source 行当前状态；DELETED 之外的值视为未删除
     * @param existingReason 既有删除原因（未删除时可传 null）
     * @param requestedReason 本次请求原因，必须已通过 parseDeleteReason 校验
     */
    public static DeleteTransitionDecision decide(
            String currentStatus,
            String existingReason,
            String requestedReason
    ) {
        boolean alreadyDeleted = "DELETED".equals(currentStatus);
        if (!alreadyDeleted) {
            return SourceService.DELETE_REASON_COMPLIANCE.equals(requestedReason)
                    ? FIRST_DELETE_COMPLIANCE
                    : FIRST_DELETE_USER;
        }
        boolean existingCompliance = SourceService.DELETE_REASON_COMPLIANCE.equals(existingReason);
        if (existingCompliance) {
            // 已是 COMPLIANCE：任何重复请求都幂等返回既有事实，COMPLIANCE 不降级
            return new DeleteTransitionDecision(Kind.ALREADY_DELETED,
                    SourceService.DELETE_REASON_COMPLIANCE);
        }
        if (SourceService.DELETE_REASON_COMPLIANCE.equals(requestedReason)) {
            return new DeleteTransitionDecision(Kind.UPGRADE_TO_COMPLIANCE,
                    SourceService.DELETE_REASON_COMPLIANCE);
        }
        return new DeleteTransitionDecision(Kind.ALREADY_DELETED, SourceService.DELETE_REASON_USER);
    }
}
