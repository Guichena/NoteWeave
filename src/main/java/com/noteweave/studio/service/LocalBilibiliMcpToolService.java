package com.noteweave.studio.service;

import com.noteweave.common.error.BusinessException;
import com.noteweave.common.error.ErrorCode;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "noteweave.studio.mcp.bilibili", name = "remote-enabled", havingValue = "false")
public class LocalBilibiliMcpToolService implements StudioMcpTool {

    private final BilibiliMcpCoreService bilibiliMcpCoreService;

    public LocalBilibiliMcpToolService(BilibiliMcpCoreService bilibiliMcpCoreService) {
        this.bilibiliMcpCoreService = bilibiliMcpCoreService;
    }

    @Override
    public String toolName() {
        return "bilibili";
    }

    @Override
    public String displayName() {
        return "Bilibili MCP";
    }

    @Override
    public String description() {
        return "Fetch bilibili.com or b23.tv metadata and CC subtitles as a local MCP context tool.";
    }

    @Override
    public ToolResult invoke(Map<String, Object> args) {
        Object rawUrl = args == null ? null : args.get("url");
        if (rawUrl == null || rawUrl.toString().isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Bilibili MCP tool requires args.url");
        }
        BilibiliMcpCoreService.BilibiliMcpContext context = bilibiliMcpCoreService.fetch(rawUrl.toString());
        return new ToolResult(
                toolName(),
                displayName(),
                context.title(),
                context.promptContext(),
                Map.of(
                        "url", context.url(),
                        "title", context.title(),
                        "subtitleLineCount", context.subtitleLineCount(),
                        "pageCount", context.pageCount()
                )
        );
    }
}
