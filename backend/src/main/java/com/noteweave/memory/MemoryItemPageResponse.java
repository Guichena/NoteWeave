package com.noteweave.memory;

import java.util.List;

/**
 * MEM-06：记忆列表的一页。{@code nextCursor} 为空表示没有下一页。
 */
public record MemoryItemPageResponse(
        List<MemoryItemResponse> items,
        String nextCursor
) {
}
