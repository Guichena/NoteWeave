package com.noteweave.source;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class SourceTagCodecTest {
    private final SourceTagCodec codec = new SourceTagCodec(new ObjectMapper());

    @Test
    void decodesStringAndFacetTagsWithoutDroppingTheCollection() {
        assertThat(codec.decode("[\"retrieval\",{\"name\":\"RAG\",\"facet\":\"topic\"},{\"ignored\":true}]"))
                .containsExactly("retrieval", "topic:RAG");
    }
}
