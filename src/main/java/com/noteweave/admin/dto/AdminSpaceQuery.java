package com.noteweave.admin.dto;

import com.noteweave.space.model.SpaceStatus;
import com.noteweave.space.model.SpaceType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AdminSpaceQuery {
    private String keyword;
    private SpaceStatus status;
    private SpaceType type;
    private Long ownerId;

    @Min(1)
    private int page = 1;

    @Min(1)
    @Max(100)
    private int pageSize = 20;

    private String sort = "createdAt,desc";
}
