package com.noteweave.search.document;

import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class EsDocumentChunk {
    private String esDocId;
    private Long spaceId;
    private Long knowledgeBaseId;
    private Long documentId;
    private String documentStatus;
    private Integer indexVersion;
    private Integer activeIndexVersion;
    private Long chunkId;
    private Integer chunkIndex;
    private String title;
    private String content;
    private String contentHash;
    private String sourceType;
    private Long createdBy;
    private String lifecycleStatus;
    private LocalDateTime createdAt;
}
