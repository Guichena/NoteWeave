package com.noteweave.studio.remote;

import java.util.Map;

public record BilibiliMcpRemoteInvokeResponse(
        String toolName,
        String displayName,
        String title,
        String promptContext,
        Map<String, Object> output
) {
}
