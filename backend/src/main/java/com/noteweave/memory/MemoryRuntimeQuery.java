package com.noteweave.memory;

/**
 * @param verifiedActorUserId 后台任务代发起人召回时传入（调用方已复核其工作台权限）；为空时使用当前会话用户
 */
public record MemoryRuntimeQuery(
        String workspaceId,
        String verifiedActorUserId
) {
    public MemoryRuntimeQuery(String workspaceId) {
        this(workspaceId, null);
    }
}
