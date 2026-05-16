package com.noteweave.studio.service;

import com.noteweave.artifact.model.ArtifactType;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ArtifactGenerateTaskInput {

    private Long artifactId;
    private Long spaceId;
    private Long researchProjectId;
    private ArtifactType artifactType;
    private String sourceScopeType;
    private List<Long> sourceIds;
    private Long createdFromSessionId;
    private Long createdFromMessageId;
    private Map<String, Object> params;
}
