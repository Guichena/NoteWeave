package com.noteweave.team.document.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class InitUploadRequest {

    @NotBlank
    @Pattern(regexp = "^[a-fA-F0-9]{32}$")
    private String fileMd5;

    @NotBlank
    @Size(max = 255)
    private String fileName;

    @Size(max = 128)
    private String contentType;

    @NotNull
    @Min(1)
    private Long totalSize;

    @NotNull
    @Min(1)
    @Max(20 * 1024 * 1024)
    private Integer chunkSize;

    @NotNull
    @Min(1)
    @Max(20000)
    private Integer totalChunks;
}
