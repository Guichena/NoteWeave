package com.noteweave.llm.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class LlmCallLogQuery {

    private Long spaceId;
    private Long sessionId;
    private Long taskId;
    private Long artifactId;
    private String scene;
    private Boolean success;
    private String provider;
    private String model;
    private int page = 1;
    private int pageSize = 20;
    private String sort = "createdAt,desc";
}
