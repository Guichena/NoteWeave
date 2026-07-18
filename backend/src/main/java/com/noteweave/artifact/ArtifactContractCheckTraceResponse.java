package com.noteweave.artifact;

import java.util.Map;

public record ArtifactContractCheckTraceResponse(
        String label,
        String status,
        String detail,
        Map<String, Object> metadata
) {
}
