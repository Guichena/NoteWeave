package com.noteweave.admin.dto;

import java.util.List;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class AdminSpaceDetailResponse {
    private AdminSpaceResponse space;
    private List<Long> knowledgeBaseIds;
    private List<Long> documentIds;
    private List<Long> artifactIds;
    private List<Long> recentTaskIds;
}
