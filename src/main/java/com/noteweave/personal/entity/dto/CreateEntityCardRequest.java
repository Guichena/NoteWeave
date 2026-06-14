package com.noteweave.personal.entity.dto;

import com.noteweave.personal.entity.model.EntityType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CreateEntityCardRequest {

    private Long researchProjectId;

    @NotBlank
    @Size(max = 255)
    private String canonicalName;

    @NotNull
    private EntityType entityType;

    private List<String> aliases;

    private String description;

    private List<String> externalRefs;

    private BigDecimal confidence;
}
