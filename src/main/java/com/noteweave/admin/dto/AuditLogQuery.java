package com.noteweave.admin.dto;

import com.noteweave.admin.model.AuditAction;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AuditLogQuery {
    private AuditAction action;
    private Long operatorId;
    private String targetType;
    private Long targetId;

    @Min(1)
    private int page = 1;

    @Min(1)
    @Max(100)
    private int pageSize = 20;

    private String sort = "createdAt,desc";
}
