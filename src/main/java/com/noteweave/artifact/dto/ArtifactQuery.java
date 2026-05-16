package com.noteweave.artifact.dto;

import com.noteweave.artifact.model.ArtifactScopeType;
import com.noteweave.artifact.model.ArtifactStatus;
import com.noteweave.artifact.model.ArtifactType;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ArtifactQuery {

    private ArtifactType artifactType;
    private ArtifactStatus status;
    private ArtifactScopeType sourceScopeType;
    private Long researchProjectId;
    private String keyword;
}
