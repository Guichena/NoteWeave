package com.noteweave.knowledge;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 新建知识条目。page_kind 只对 Wiki 页面生效，不传时按标题和正文推断；
 * 此前接口没有这个字段，调用方传了也被静默忽略，只能建出 TOPIC 页。
 */
public record KnowledgeItemRequest(
        @NotBlank @Pattern(regexp = "NOTE|WIKI") String itemType,
        @NotBlank @Size(max = 300) String title,
        @NotBlank @Size(max = 20000) String content,
        @Size(max = 36) String sourceMessageId,
        @Pattern(regexp = "TOPIC|CONCEPT|OVERVIEW|COMPARISON", message = "page_kind 只能是 TOPIC、CONCEPT、OVERVIEW 或 COMPARISON")
        String pageKind
) {
    public KnowledgeItemRequest(String itemType, String title, String content, String sourceMessageId) {
        this(itemType, title, content, sourceMessageId, null);
    }
}
