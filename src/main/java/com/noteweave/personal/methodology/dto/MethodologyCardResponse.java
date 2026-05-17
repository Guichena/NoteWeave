package com.noteweave.personal.methodology.dto;

import com.noteweave.personal.methodology.model.MethodologyCardSource;
import com.noteweave.personal.methodology.model.MethodologyCardStatus;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Builder;

@Builder
public record MethodologyCardResponse(
        Long id,
        Long spaceId,
        Long researchProjectId,
        String name,
        String scene,
        String problemType,
        List<String> workflow,
        List<String> outputStructure,
        List<String> qualityChecklist,
        MethodologyCardSource cardSource,
        MethodologyCardStatus status,
        Integer version,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
