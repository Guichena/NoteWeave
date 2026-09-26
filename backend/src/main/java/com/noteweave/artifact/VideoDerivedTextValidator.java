package com.noteweave.artifact;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.http.HttpStatus;

/** Checks every published derived claim against the immutable Host knowledge plan. */
final class VideoDerivedTextValidator {
    private static final Set<String> FIELDS = Set.of("schema_version", "artifact_type",
            "bundle_content_digest", "plan_content_digest", "title", "terms", "blog_sections",
            "interview_questions", "markdown_sha256", "content_digest");

    private VideoDerivedTextValidator() {}

    static void validate(String skillKey, Map<String, Object> payload,
                         Map<String, Object> bundle, Map<String, Object> plan,
                         String bundleDigest, String planDigest) {
        VideoKnowledgePlanValidator.validate(plan, bundle, bundleDigest);
        Object raw = payload.get("derived_text_ir");
        if (!(raw instanceof Map<?, ?> ir) || !ir.keySet().equals(FIELDS)
                || !"video-derived-text-v1".equals(ir.get("schema_version"))
                || !skillKey.equals(ir.get("artifact_type"))
                || !bundleDigest.equals(ir.get("bundle_content_digest"))
                || !planDigest.equals(ir.get("plan_content_digest"))
                || !(payload.get("markdown") instanceof String markdown)
                || !hash(markdown).equals(ir.get("markdown_sha256"))
                || !(ir.get("blog_sections") instanceof List<?> blog)
                || !(ir.get("interview_questions") instanceof List<?> qa)
                || !(ir.get("terms") instanceof List<?> terms)) throw invalid();
        Map<String, Object> content = new TreeMap<>();
        ir.forEach((key, value) -> {
            if (!"content_digest".equals(key)) content.put(String.valueOf(key), canonical(value));
        });
        if (!hashJson(content).equals(ir.get("content_digest"))) throw invalid();
        if (!bundleDigest.equals(plan.get("bundle_content_digest"))) throw invalid();
        List<?> nodes = list(plan.get("nodes"));
        if (nodes.isEmpty() || !text(map(nodes.get(0)).get("title")).equals(ir.get("title")))
            throw invalid();
        List<Map<?, ?>> semantic = new ArrayList<>();
        LinkedHashSet<String> expectedTerms = new LinkedHashSet<>();
        for (Object item : nodes) {
            Map<?, ?> node = map(item);
            if (!(Set.of("CONCEPT", "EXAMPLE").contains(node.get("kind")))
                    || list(node.get("claims")).stream().noneMatch(claim ->
                            "EXTRACTED".equals(map(claim).get("status")))) continue;
            semantic.add(node);
            for (Object term : list(node.get("terms"))) expectedTerms.add(text(term));
        }
        if (semantic.isEmpty() || !new ArrayList<>(expectedTerms).equals(terms)) throw invalid();
        List<?> output = "knowledge_blog".equals(skillKey) ? blog : qa;
        if (output.size() != semantic.size() || ("knowledge_blog".equals(skillKey)
                ? !qa.isEmpty() : !blog.isEmpty())) throw invalid();
        List<?> sections = list(payload.get("sections"));
        if (sections.size() != semantic.size()) throw invalid();
        for (int index = 0; index < semantic.size(); index++) {
            Map<?, ?> node = semantic.get(index);
            Map<?, ?> item = map(output.get(index));
            List<Map<String, Object>> expectedClaims = new ArrayList<>();
            List<String> refs = new ArrayList<>();
            boolean unverified = false;
            for (Object rawClaim : list(node.get("claims"))) {
                Map<?, ?> claim = map(rawClaim);
                if ("EXTRACTED".equals(claim.get("status"))) {
                    expectedClaims.add(Map.of("text", text(claim.get("text")),
                            "evidence_refs", list(claim.get("evidence_refs"))));
                    for (Object ref : list(claim.get("evidence_refs"))) {
                        if (!refs.contains(ref)) refs.add(text(ref));
                    }
                } else unverified = true;
            }
            List<String> gaps = new ArrayList<>();
            for (Object missing : list(node.get("missing"))) {
                if (!gaps.contains(missing)) gaps.add(text(missing));
            }
            if (unverified && !gaps.contains("UNVERIFIED_CLAIM")) gaps.add("UNVERIFIED_CLAIM");
            String heading;
            if ("knowledge_blog".equals(skillKey)) {
                if (!item.keySet().equals(Set.of("node_id", "heading", "claims", "gaps"))
                        || !node.get("node_id").equals(item.get("node_id"))
                        || !node.get("title").equals(item.get("heading"))
                        || !expectedClaims.equals(item.get("claims"))
                        || !gaps.equals(item.get("gaps"))) throw invalid();
                heading = text(item.get("heading"));
            } else {
                heading = "What does the source say about " + text(node.get("title")) + "?";
                if (!item.keySet().equals(Set.of("node_id", "question", "short_answer",
                        "detailed_answer", "related_knowledge", "gaps"))
                        || !node.get("node_id").equals(item.get("node_id"))
                        || !heading.equals(item.get("question"))
                        || !expectedClaims.get(0).get("text").equals(item.get("short_answer"))
                        || !expectedClaims.equals(item.get("detailed_answer"))
                        || !node.get("terms").equals(item.get("related_knowledge"))
                        || !gaps.equals(item.get("gaps"))) throw invalid();
            }
            Map<?, ?> section = map(sections.get(index));
            String body = String.join("\n\n", expectedClaims.stream()
                    .map(claim -> text(claim.get("text"))).toList());
            if (!heading.equals(section.get("heading")) || !body.equals(section.get("body"))
                    || !refs.equals(section.get("source_refs"))) throw invalid();
        }
        if (!render(ir, blog, qa, terms).equals(markdown)) throw invalid();
    }

    private static String render(Map<?, ?> ir, List<?> blog, List<?> qa, List<?> terms) {
        List<String> lines = new ArrayList<>(List.of("# " + text(ir.get("title")), "",
                "Source: " + text(ir.get("bundle_content_digest")),
                "Knowledge plan: " + text(ir.get("plan_content_digest")), ""));
        if (!terms.isEmpty()) {
            lines.addAll(List.of("## Terms", "", join(terms), ""));
        }
        if ("knowledge_blog".equals(ir.get("artifact_type"))) {
            for (Object raw : blog) {
                Map<?, ?> item = map(raw);
                lines.addAll(List.of("## " + text(item.get("heading")), ""));
                appendClaims(lines, list(item.get("claims")));
                appendGaps(lines, list(item.get("gaps")));
            }
        } else {
            for (Object raw : qa) {
                Map<?, ?> item = map(raw);
                lines.addAll(List.of("## " + text(item.get("question")), "", "### Short answer", "",
                        text(item.get("short_answer")), "", "### Detailed answer", ""));
                appendClaims(lines, list(item.get("detailed_answer")));
                List<?> related = list(item.get("related_knowledge"));
                lines.addAll(List.of("### Related knowledge", "",
                        related.isEmpty() ? "No verified terms." : join(related), ""));
                appendGaps(lines, list(item.get("gaps")));
            }
        }
        return String.join("\n", lines).stripTrailing() + "\n";
    }

    private static void appendClaims(List<String> lines, List<?> claims) {
        for (Object raw : claims) {
            Map<?, ?> claim = map(raw);
            lines.addAll(List.of(text(claim.get("text")),
                    "Evidence: " + join(list(claim.get("evidence_refs"))), ""));
        }
    }

    private static void appendGaps(List<String> lines, List<?> gaps) {
        for (Object gap : gaps) lines.addAll(List.of("Coverage gap: " + text(gap), ""));
    }

    private static String join(List<?> values) {
        return String.join(", ", values.stream().map(VideoDerivedTextValidator::text).toList());
    }

    private static Map<?, ?> map(Object value) {
        if (value instanceof Map<?, ?> map) return map;
        throw invalid();
    }

    private static List<?> list(Object value) {
        if (value instanceof List<?> list) return list;
        throw invalid();
    }

    private static String text(Object value) {
        if (value instanceof String text) return text;
        throw invalid();
    }

    private static Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, child) -> sorted.put(String.valueOf(key), canonical(child)));
            return sorted;
        }
        if (value instanceof List<?> list) return list.stream().map(VideoDerivedTextValidator::canonical).toList();
        return value;
    }

    private static String hashJson(Object value) {
        try { return hash(new ObjectMapper().writeValueAsString(value)); }
        catch (Exception ex) { throw invalid(); }
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) { throw invalid(); }
    }

    private static BusinessException invalid() {
        return new BusinessException("VIDEO_DERIVED_TEXT_INVALID",
                "视频派生产物与冻结证据或渲染不一致", HttpStatus.CONFLICT);
    }
}
