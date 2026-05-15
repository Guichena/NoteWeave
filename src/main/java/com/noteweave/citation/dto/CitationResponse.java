package com.noteweave.citation.dto;

import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class CitationResponse {
    private Long id;
    private Long messageId;
    private Long spaceId;
    private String sourceType;
    private Long sourceId;
    private Long chunkId;
    private String title;
    private String quoteText;
    private String locationInfo;
    private Integer pageNo;
    private Integer startOffset;
    private Integer endOffset;
    private String quoteHash;
    private String snapshotObjectKey;
    private String sourceVersion;
    private LocalDateTime createdAt;
}
