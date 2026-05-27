package com.noteweave.studio.remote;

import com.noteweave.common.api.ApiResponse;
import com.noteweave.studio.service.BilibiliMcpCoreService;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/mcp/bilibili")
@ConditionalOnProperty(prefix = "noteweave.studio.mcp.remote-server", name = "enabled", havingValue = "true")
public class BilibiliMcpRemoteController {

    private final BilibiliMcpCoreService bilibiliMcpCoreService;

    public BilibiliMcpRemoteController(BilibiliMcpCoreService bilibiliMcpCoreService) {
        this.bilibiliMcpCoreService = bilibiliMcpCoreService;
    }

    @PostMapping("/invoke")
    public ApiResponse<BilibiliMcpRemoteInvokeResponse> invoke(@Valid @RequestBody BilibiliMcpRemoteInvokeRequest request) {
        BilibiliMcpCoreService.BilibiliMcpContext context = bilibiliMcpCoreService.fetch(request.url());
        return ApiResponse.success(new BilibiliMcpRemoteInvokeResponse(
                "bilibili",
                "Bilibili MCP",
                context.title(),
                context.promptContext(),
                Map.of(
                        "url", context.url(),
                        "title", context.title(),
                        "subtitleLineCount", context.subtitleLineCount(),
                        "pageCount", context.pageCount()
                )
        ));
    }
}
