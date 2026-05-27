package com.noteweave.studio.service;

import java.util.Map;

public interface StudioMcpTool {

    String toolName();

    String displayName();

    String description();

    ToolResult invoke(Map<String, Object> args);

    record ToolResult(
            String toolName,
            String displayName,
            String title,
            String promptContext,
            Map<String, Object> output
    ) {
    }
}
