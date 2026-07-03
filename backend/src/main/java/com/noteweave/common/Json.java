package com.noteweave.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class Json {

    private Json() {
    }

    public static String write(ObjectMapper objectMapper, Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new BusinessException("JSON_WRITE_FAILED", "无法序列化任务载荷");
        }
    }
}
