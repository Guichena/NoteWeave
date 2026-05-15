package com.noteweave.team.document.dto;

import java.util.List;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class UploadChunkResponse {
    private Long uploadId;
    private Integer chunkIndex;
    private boolean uploaded;
    private List<Integer> uploadedChunks;
    private Integer totalChunks;
    private double progress;
}
