package com.noteweave.team.document.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class DocumentProcessTaskPayload {
    private Long taskId;
    private Long documentId;
    private Long spaceId;
    private Long knowledgeBaseId;
    private String fileMd5;
    private String objectKey;
    private String fileName;
    private String contentType;
    private Long uploadedBy;
    private Instant createdAt;
}
