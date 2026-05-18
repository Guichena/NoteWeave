package com.noteweave.admin.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MarkTaskFailedRequest {
    @NotBlank
    private String reason;
}
