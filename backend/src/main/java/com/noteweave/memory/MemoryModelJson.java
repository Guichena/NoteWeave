package com.noteweave.memory;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 模型输出只能是一个完整 JSON 根；兼容整个响应恰好是一段 JSON 代码围栏。 */
final class MemoryModelJson {
    private MemoryModelJson() { }

    static JsonNode read(ObjectMapper mapper, String output) {
        if (mapper == null || output == null) return null;
        String json = output.trim();
        if (json.startsWith("```")) {
            int newline = json.indexOf('\n');
            if (newline < 0 || !json.endsWith("```")) return null;
            String language = json.substring(3, newline).trim();
            if (!language.isEmpty() && !"json".equalsIgnoreCase(language)) return null;
            json = json.substring(newline + 1, json.length() - 3).trim();
        }
        if (json.isEmpty()) return null;
        try (JsonParser parser = mapper.getFactory().createParser(json)) {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode root = mapper.readTree(parser);
            return parser.nextToken() == null ? root : null;
        } catch (Exception rejected) {
            // 不记录响应或解析异常消息，其中可能含用户正文。
            return null;
        }
    }
}
