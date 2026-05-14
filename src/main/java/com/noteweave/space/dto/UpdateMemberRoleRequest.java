package com.noteweave.space.dto;

import com.noteweave.space.model.SpaceRole;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UpdateMemberRoleRequest {

    @NotNull
    private SpaceRole role;
}
