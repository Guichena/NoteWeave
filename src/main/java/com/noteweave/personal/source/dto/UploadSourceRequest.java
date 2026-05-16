package com.noteweave.personal.source.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UploadSourceRequest {

    @Size(max = 255)
    private String title;
}
