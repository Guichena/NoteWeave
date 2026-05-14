package com.noteweave.space.dto;

import com.noteweave.space.model.SpaceRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AddMemberRequest {

    @NotBlank
    @Email
    @Size(max = 128)
    private String email;

    @NotNull
    private SpaceRole role;
}
