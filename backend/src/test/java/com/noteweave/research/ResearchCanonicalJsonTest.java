package com.noteweave.research;

import com.noteweave.common.BusinessException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResearchCanonicalJsonTest {

    @Test
    void recursivelyNormalizesValuesAndOrdersUtf8Keys() {
        Object canonical = ResearchCanonicalJson.canonicalize(Map.of(
                "z", List.of(Map.of("e\u0301", "A\u030A")),
                "a", "value"));

        assertThat(canonical).isEqualTo(Map.of(
                "a", "value",
                "z", List.of(Map.of("é", "Å"))));
    }

    @Test
    void rejectsNestedKeysThatCollideAfterNfc() {
        LinkedHashMap<String, Object> nested = new LinkedHashMap<>();
        nested.put("é", 1);
        nested.put("e\u0301", 2);

        assertThatThrownBy(() -> ResearchCanonicalJson.canonicalize(Map.of("nested", nested)))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).code())
                .isEqualTo(ResearchCanonicalJson.KEY_COLLISION_CODE);
    }
}
