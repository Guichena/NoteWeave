package com.noteweave.user.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UpdateUserProfileRequest {

    @Size(max = 64)
    private String displayName;

    @Size(max = 255)
    private String avatarUrl;
}
