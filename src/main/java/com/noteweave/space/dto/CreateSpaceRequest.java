package com.noteweave.space.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CreateSpaceRequest {

    @NotBlank
    @Size(max = 128)
    private String name;

    @Size(max = 500)
    private String description;
}
