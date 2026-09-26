package com.noteweave.research;

import com.noteweave.common.BusinessException;
import java.text.Normalizer;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Strict recursive canonicalization shared by research snapshot and completion contracts. */
final class ResearchCanonicalJson {

    static final String KEY_COLLISION_CODE = "CANONICAL_KEY_COLLISION";

    private ResearchCanonicalJson() { }

    static Object canonicalize(Object value) {
        if (value instanceof String text) {
            return Normalizer.normalize(text, Normalizer.Form.NFC);
        }
        if (value instanceof Map<?, ?> raw) {
            Map<String, Object> sorted = new TreeMap<>(ResearchAgentBinaryOrder.UTF8);
            for (Map.Entry<?, ?> entry : raw.entrySet()) {
                String key = Normalizer.normalize(String.valueOf(entry.getKey()), Normalizer.Form.NFC);
                if (sorted.containsKey(key)) {
                    throw new BusinessException(
                            KEY_COLLISION_CODE,
                            "Canonical JSON contains duplicate keys after NFC normalization: " + key);
                }
                sorted.put(key, canonicalize(entry.getValue()));
            }
            return sorted;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(ResearchCanonicalJson::canonicalize).toList();
        }
        return value;
    }
}
