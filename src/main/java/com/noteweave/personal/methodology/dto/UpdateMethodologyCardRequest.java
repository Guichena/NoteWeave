package com.noteweave.personal.methodology.dto;

import com.noteweave.personal.methodology.model.MethodologyCardScope;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UpdateMethodologyCardRequest {

    @NotBlank
    private String name;

    private String scene;

    private String problemType;

    private List<String> workflow;

    private List<String> requiredConcepts;

    private List<String> outputStructure;

    private List<String> qualityChecklist;

    @NotNull
    private MethodologyCardScope cardScope;

    private Long researchProjectId;
}
