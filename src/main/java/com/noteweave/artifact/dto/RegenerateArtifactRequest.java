package com.noteweave.artifact.dto;

import java.util.Map;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RegenerateArtifactRequest {

    private Map<String, Object> params;
}
