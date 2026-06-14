package com.noteweave.personal.entity.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class LinkEntityRelationRequest {

    @NotNull
    private Long entityCardId;

    private String relationType;

    private String evidence;
}
