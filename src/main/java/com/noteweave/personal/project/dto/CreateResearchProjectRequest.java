package com.noteweave.personal.project.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CreateResearchProjectRequest {

    @NotBlank
    @Size(max = 255)
    private String title;

    @Size(max = 1024)
    private String description;

    @Size(max = 1024)
    private String researchGoal;
}
