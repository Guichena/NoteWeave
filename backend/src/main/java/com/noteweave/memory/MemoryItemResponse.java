package com.noteweave.memory;

import java.time.Instant;
import java.util.List;

/**
 * 记忆列表中的一行：生效中的当前版本，或等待确认的新版本。
 * 记忆只作为表达控制编入提示词，不作为事实来源。
 */
public record MemoryItemResponse(
        String memoryItemId,
        String revisionId,
        String memoryScope,
        String itemStatus,
        String reviewStatus,
        String revisionStatus,
        int versionNo,
        String displayText,
        String candidateType,
        List<String> taskNeighborhoods,
        String provenanceType,
        double utilityScore,
        int applicationCount,
        String conflictStatus,
        // 仅由候选晋升产生的记忆带有门控打分；直接提交的观察记录为空
        MemoryGateResponse gate,
        Instant lastConfirmedAt,
        Instant createdAt
) {
}
