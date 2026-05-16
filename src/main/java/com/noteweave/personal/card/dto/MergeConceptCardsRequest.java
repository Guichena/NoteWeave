package com.noteweave.personal.card.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MergeConceptCardsRequest {

    @NotNull
    private Long targetConceptId;

    @NotEmpty
    private List<Long> sourceConceptIds;
}
