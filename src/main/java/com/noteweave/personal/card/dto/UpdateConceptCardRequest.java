package com.noteweave.personal.card.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UpdateConceptCardRequest {

    @NotBlank
    private String definition;

    @NotBlank
    private String explanation;

    private List<String> useCases;

    private List<String> commonMisunderstandings;
}
