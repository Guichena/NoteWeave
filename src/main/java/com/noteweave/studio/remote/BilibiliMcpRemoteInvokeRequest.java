package com.noteweave.studio.remote;

import jakarta.validation.constraints.NotBlank;

public record BilibiliMcpRemoteInvokeRequest(
        @NotBlank String url
) {
}
