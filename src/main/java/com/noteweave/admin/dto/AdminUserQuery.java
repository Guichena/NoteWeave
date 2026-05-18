package com.noteweave.admin.dto;

import com.noteweave.user.model.UserStatus;
import com.noteweave.user.model.UserSystemRole;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AdminUserQuery {
    private String keyword;
    private UserStatus status;
    private UserSystemRole systemRole;

    @Min(1)
    private int page = 1;

    @Min(1)
    @Max(100)
    private int pageSize = 20;

    private String sort = "createdAt,desc";
}
