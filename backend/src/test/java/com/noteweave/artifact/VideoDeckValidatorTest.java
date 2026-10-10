package com.noteweave.artifact;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class VideoDeckValidatorTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void acceptsPythonCandidateAndRejectsForgedSlideAndMissingPreview() throws Exception {
        try (var source = getClass().getResourceAsStream("/video-deck-v1-cross-language.json")) {
            if (source == null) throw new IllegalStateException("deck fixture is missing");
            Map<String, Object> fixture = mapper.readValue(source, new TypeReference<>() {});
            @SuppressWarnings("unchecked")
            Map<String, Object> bundle = (Map<String, Object>) fixture.get("bundle");
            @SuppressWarnings("unchecked")
            Map<String, Object> plan = (Map<String, Object>) fixture.get("plan");
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) fixture.get("payload");
            String bundleDigest = String.valueOf(fixture.get("bundle_digest"));
            String planDigest = String.valueOf(fixture.get("plan_digest"));
            VideoDeckValidator.validate(payload, bundle, plan, bundleDigest, planDigest, "zh-CN");
            assertThatThrownBy(() -> VideoDeckValidator.validate(
                    payload, bundle, plan, bundleDigest, planDigest, "en"))
                    .isInstanceOf(BusinessException.class).hasMessageContaining("冻结视频证据");

            @SuppressWarnings("unchecked")
            Map<String, Object> originalIr = (Map<String, Object>) payload.get("video_deck_ir");
            @SuppressWarnings("unchecked")
            Map<String, Object> originalSlide = (Map<String, Object>)
                    ((List<?>) originalIr.get("slides")).get(0);
            Map<String, Object> forgedSlide = new LinkedHashMap<>(originalSlide);
            forgedSlide.put("file_id", "foreign-frame-file");
            Map<String, Object> forgedIr = new LinkedHashMap<>(originalIr);
            forgedIr.put("slides", List.of(forgedSlide));
            payload.put("video_deck_ir", forgedIr);
            assertThatThrownBy(() -> VideoDeckValidator.validate(
                    payload, bundle, plan, bundleDigest, planDigest, "zh-CN"))
                    .isInstanceOf(BusinessException.class).hasMessageContaining("冻结视频证据");
            payload.put("video_deck_ir", originalIr);

            @SuppressWarnings("unchecked")
            Map<String, Object> candidate = (Map<String, Object>) payload.get("candidate");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> files = (List<Map<String, Object>>) candidate.get("required_files");
            candidate.put("required_files", files.subList(0, 2));
            assertThatThrownBy(() -> VideoDeckValidator.validate(
                    payload, bundle, plan, bundleDigest, planDigest, "zh-CN"))
                    .isInstanceOf(BusinessException.class).hasMessageContaining("逐页交付清单");
        }
    }
}
