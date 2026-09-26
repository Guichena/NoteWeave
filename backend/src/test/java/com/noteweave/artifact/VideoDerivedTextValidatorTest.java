package com.noteweave.artifact;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class VideoDerivedTextValidatorTest {
    private static final String BUNDLE_DIGEST = "a".repeat(64);

    @Test
    void acceptsBothFormatsAndRejectsRewrittenClaimsEvenWithFreshDigests() throws Exception {
        Map<String, Object> bundle = Map.of(
                "bvid", "BV1234567890", "part", 1, "duration_ms", 5000,
                "transcript_segments", List.of(Map.of(
                        "segment_id", "s1", "start_ms", 1000, "end_ms", 4000,
                        "corrected_text", "cache consistency")),
                "frames", List.of(), "frame_observations", List.of());
        Map<String, Object> root = Map.ofEntries(
                Map.entry("node_id", "root"), Map.entry("parent_id", ""),
                Map.entry("kind", "TOPIC"), Map.entry("title", "Cache"),
                Map.entry("start_ms", 0), Map.entry("end_ms", 5000),
                Map.entry("transcript_segment_ids", List.of()), Map.entry("frame_ids", List.of()),
                Map.entry("terms", List.of()), Map.entry("claims", List.of()),
                Map.entry("missing", List.of()));
        Map<String, Object> concept = Map.ofEntries(
                Map.entry("node_id", "concept"), Map.entry("parent_id", "root"),
                Map.entry("kind", "CONCEPT"), Map.entry("title", "Consistency"),
                Map.entry("start_ms", 1000), Map.entry("end_ms", 4000),
                Map.entry("transcript_segment_ids", List.of("s1")), Map.entry("frame_ids", List.of()),
                Map.entry("terms", List.of("cache")),
                Map.entry("claims", List.of(Map.of("text", "cache consistency",
                        "status", "EXTRACTED", "evidence_refs", List.of("segment:s1")))),
                Map.entry("missing", List.of()));
        Map<String, Object> plan = Map.of("schema_version", "video-knowledge-plan-v1",
                "bundle_content_digest", BUNDLE_DIGEST, "bvid", "BV1234567890",
                "part", 1, "duration_ms", 5000, "nodes", List.of(root, concept));
        String planDigest = digest(plan);
        Map<String, Object> claim = Map.of("text", "cache consistency",
                "evidence_refs", List.of("segment:s1"));
        Map<String, Object> blog = Map.of("node_id", "concept", "heading", "Consistency",
                "claims", List.of(claim), "gaps", List.of());
        Map<String, Object> qa = Map.of("node_id", "concept",
                "question", "What does the source say about Consistency?",
                "short_answer", "cache consistency", "detailed_answer", List.of(claim),
                "related_knowledge", List.of("cache"), "gaps", List.of());
        for (String skill : List.of("knowledge_blog", "interview_qa")) {
            String markdown = "# Cache\n\nSource: " + BUNDLE_DIGEST
                    + "\nKnowledge plan: " + planDigest
                    + "\n\n## Terms\n\ncache\n\n"
                    + ("knowledge_blog".equals(skill)
                    ? "## Consistency\n\ncache consistency\nEvidence: segment:s1\n"
                    : "## What does the source say about Consistency?\n\n### Short answer\n\n"
                    + "cache consistency\n\n### Detailed answer\n\ncache consistency\n"
                    + "Evidence: segment:s1\n\n### Related knowledge\n\ncache\n");
            Map<String, Object> ir = new LinkedHashMap<>(Map.of(
                    "schema_version", "video-derived-text-v1", "artifact_type", skill,
                    "bundle_content_digest", BUNDLE_DIGEST, "plan_content_digest", planDigest,
                    "title", "Cache", "terms", List.of("cache"),
                    "blog_sections", "knowledge_blog".equals(skill) ? List.of(blog) : List.of(),
                    "interview_questions", "interview_qa".equals(skill) ? List.of(qa) : List.of(),
                    "markdown_sha256", hash(markdown)));
            ir.put("content_digest", digest(ir));
            Map<String, Object> section = Map.of("heading", "knowledge_blog".equals(skill)
                    ? "Consistency" : "What does the source say about Consistency?",
                    "body", "cache consistency", "source_refs", List.of("segment:s1"));
            Map<String, Object> payload = new LinkedHashMap<>(Map.of(
                    "derived_text_ir", ir, "markdown", markdown, "sections", List.of(section)));
            VideoDerivedTextValidator.validate(skill, payload, bundle, plan, BUNDLE_DIGEST, planDigest);

            Map<String, Object> forgedClaim = Map.of("text", "cache always consistent",
                    "evidence_refs", List.of("segment:s1"));
            Map<String, Object> forgedItem = "knowledge_blog".equals(skill)
                    ? Map.of("node_id", "concept", "heading", "Consistency",
                            "claims", List.of(forgedClaim), "gaps", List.of())
                    : Map.of("node_id", "concept", "question", qa.get("question"),
                            "short_answer", "cache always consistent",
                            "detailed_answer", List.of(forgedClaim),
                            "related_knowledge", List.of("cache"), "gaps", List.of());
            Map<String, Object> changedIr = new LinkedHashMap<>(ir);
            changedIr.put("knowledge_blog".equals(skill) ? "blog_sections" : "interview_questions",
                    List.of(forgedItem));
            changedIr.put("content_digest", digest(changedIr));
            payload.put("derived_text_ir", changedIr);
            assertThatThrownBy(() -> VideoDerivedTextValidator.validate(
                    skill, payload, bundle, plan, BUNDLE_DIGEST, planDigest))
                    .isInstanceOf(BusinessException.class).hasMessageContaining("冻结证据");
        }
    }

    private static String digest(Map<String, Object> value) throws Exception {
        Map<String, Object> copy = new TreeMap<>(value);
        copy.remove("content_digest");
        return hash(new ObjectMapper().writeValueAsString(canonical(copy)));
    }

    private static Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, child) -> sorted.put(String.valueOf(key), canonical(child)));
            return sorted;
        }
        if (value instanceof List<?> list) return list.stream().map(VideoDerivedTextValidatorTest::canonical).toList();
        return value;
    }

    private static String hash(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
