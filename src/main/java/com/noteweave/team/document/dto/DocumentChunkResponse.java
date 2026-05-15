package com.noteweave.team.document.dto;

import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class DocumentChunkResponse {
    private Long id;
    private Long spaceId;
    private Long knowledgeBaseId;
    private Long documentId;
    private Integer indexVersion;
    private Integer chunkIndex;
    private String content;
    private String contentHash;
    private Integer tokenCount;
    private Integer pageNo;
    private String sectionTitle;
    private Integer sourceStart;
    private Integer sourceEnd;
    private String esDocId;
    private LocalDateTime createdAt;
}
