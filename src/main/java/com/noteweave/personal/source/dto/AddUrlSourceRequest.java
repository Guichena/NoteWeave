package com.noteweave.personal.source.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AddUrlSourceRequest {

    @NotBlank
    @Size(max = 1024)
    private String url;

    @Size(max = 255)
    private String title;
}
