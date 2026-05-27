package com.noteweave.studio.service;

import com.noteweave.common.error.BusinessException;
import com.noteweave.common.error.ErrorCode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class StudioMcpToolRegistry {

    private final Map<String, StudioMcpTool> toolsByName;

    public StudioMcpToolRegistry(List<StudioMcpTool> tools) {
        Map<String, StudioMcpTool> indexed = new LinkedHashMap<>();
        for (StudioMcpTool tool : tools) {
            indexed.put(tool.toolName().toLowerCase(Locale.ROOT), tool);
        }
        this.toolsByName = Map.copyOf(indexed);
    }

    public Optional<McpInvocationSpec> resolve(Map<String, Object> params) {
        if (params == null || params.isEmpty()) {
            return Optional.empty();
        }
        Object rawToolName = params.get("mcpToolName");
        if (rawToolName != null && !rawToolName.toString().isBlank()) {
            String toolName = rawToolName.toString().trim().toLowerCase(Locale.ROOT);
            Map<String, Object> args = asArgsMap(params.get("mcpArgs"));
            if ("bilibili".equals(toolName) && !args.containsKey("url")) {
                Object legacyUrl = params.get("bilibiliUrl");
                if (legacyUrl != null && !legacyUrl.toString().isBlank()) {
                    args = new LinkedHashMap<>(args);
                    args.put("url", legacyUrl.toString().trim());
                }
            }
            return Optional.of(new McpInvocationSpec(toolName, Map.copyOf(args)));
        }
        Object bilibiliUrl = params.get("bilibiliUrl");
        if (bilibiliUrl == null || bilibiliUrl.toString().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new McpInvocationSpec(
                "bilibili",
                Map.of("url", bilibiliUrl.toString().trim())
        ));
    }

    public boolean hasConfiguredTool(Map<String, Object> params) {
        return resolve(params).isPresent();
    }

    public StudioMcpTool getRequired(String toolName) {
        StudioMcpTool tool = toolsByName.get(toolName == null ? "" : toolName.trim().toLowerCase(Locale.ROOT));
        if (tool == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Unsupported MCP tool: " + toolName);
        }
        return tool;
    }

    public List<StudioMcpToolDescriptor> descriptors() {
        return toolsByName.values().stream()
                .map(tool -> new StudioMcpToolDescriptor(tool.toolName(), tool.displayName(), tool.description()))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asArgsMap(Object rawArgs) {
        if (rawArgs instanceof Map<?, ?> rawMap) {
            Map<String, Object> args = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
                if (entry.getKey() == null) {
                    continue;
                }
                args.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return args;
        }
        return Map.of();
    }

    public record McpInvocationSpec(String toolName, Map<String, Object> args) {
    }

    public record StudioMcpToolDescriptor(String toolName, String displayName, String description) {
    }
}
