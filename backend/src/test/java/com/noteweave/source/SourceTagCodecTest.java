package com.noteweave.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.noteweave.common.BusinessException;
import org.junit.jupiter.api.Test;

class SourceTagCodecTest {
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final SourceTagCodec codec = new SourceTagCodec(new ObjectMapper(), meterRegistry);

    @Test
    void decodesStringAndFacetTagsWithoutDroppingTheCollection() {
        assertThat(codec.decode("[\"retrieval\",{\"name\":\"RAG\",\"facet\":\"topic\"},{\"ignored\":true}]"))
                .containsExactly("retrieval", "topic:RAG");
    }

    @Test
    void shouldRejectMalformedStoredTagsInsteadOfReturningAnEmptyCollection() {
        assertThatThrownBy(() -> codec.decode("{broken"))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.code()).isEqualTo("SOURCE_TAG_DATA_INVALID");
                    assertThat(error.status()).isEqualTo(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR);
                });
        assertThat(meterRegistry.counter("noteweave.source.tags.invalid").count()).isEqualTo(1.0);
    }

    @Test
    void shouldRejectUnsupportedTagNodeTypesInsteadOfSilentlyDroppingThem() {
        assertThatThrownBy(() -> codec.decode("[\"valid\", 42, true]"))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.code()).isEqualTo("SOURCE_TAG_DATA_INVALID"));
    }
}
