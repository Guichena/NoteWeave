package com.noteweave.artifact;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

class VideoDeckFileVerifierTest {
    @Test
    void checksOriginalPictureBytesAndEditableTextInsideRealPythonPptx() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> fixture;
        try (var source = getClass().getResourceAsStream("/video-deck-v1-cross-language.json")) {
            fixture = mapper.readValue(source, new TypeReference<>() {});
        }
        byte[] pptx;
        try (var source = getClass().getResourceAsStream("/video-deck-v1.pptx")) {
            pptx = source.readAllBytes();
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) fixture.get("payload");
        @SuppressWarnings("unchecked")
        Map<String, Object> ir = (Map<String, Object>) payload.get("video_deck_ir");
        List<?> slides = (List<?>) ir.get("slides");
        VideoDeckFileVerifier.validate(pptx, slides);

        ByteArrayOutputStream forged = new ByteArrayOutputStream();
        try (ZipInputStream source = new ZipInputStream(new ByteArrayInputStream(pptx));
             ZipOutputStream destination = new ZipOutputStream(forged)) {
            ZipEntry entry;
            while ((entry = source.getNextEntry()) != null) {
                destination.putNextEntry(new ZipEntry(entry.getName()));
                byte[] content = source.readAllBytes();
                if (entry.getName().startsWith("ppt/media/")) content[content.length - 1] ^= 1;
                destination.write(content);
                destination.closeEntry();
            }
        }
        assertThatThrownBy(() -> VideoDeckFileVerifier.validate(forged.toByteArray(), slides))
                .isInstanceOf(BusinessException.class).hasMessageContaining("原画面");
    }
}
