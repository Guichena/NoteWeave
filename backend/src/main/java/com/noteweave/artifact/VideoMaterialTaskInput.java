package com.noteweave.artifact;

import java.util.Map;

/** Frozen acquisition identity returned only to the active Worker delivery. */
public record VideoMaterialTaskInput(String schemaVersion, String taskId, String requestId,
                                     String workspaceId, String inputSnapshotId,
                                     String templateVersion, Map<String, Object> inputs) {}
