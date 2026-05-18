package com.noteweave.admin.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CleanupExecuteRequest {
    @NotNull
    private Long jobId;
}
