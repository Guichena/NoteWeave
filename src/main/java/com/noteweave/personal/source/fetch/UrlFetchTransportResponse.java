package com.noteweave.personal.source.fetch;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

public record UrlFetchTransportResponse(int statusCode, Map<String, List<String>> headers, InputStream body) {

    public String firstHeader(String name) {
        if (headers == null || name == null) {
            return null;
        }
        return headers.entrySet().stream()
                .filter(entry -> entry.getKey() != null && entry.getKey().equalsIgnoreCase(name))
                .map(Map.Entry::getValue)
                .filter(values -> values != null && !values.isEmpty())
                .map(values -> values.get(0))
                .findFirst()
                .orElse(null);
    }
}
