package com.noteweave.artifact;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.http.HttpStatus;

/** Rebuilds every deck slide from immutable Host evidence before file publication. */
final class VideoDeckValidator {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> IR_FIELDS = Set.of("schema_version", "bundle_content_digest",
            "plan_content_digest", "bvid", "part", "title", "slides", "content_digest");
    private static final Set<String> SLIDE_FIELDS = Set.of("sequence_no", "node_id", "title",
            "frame_id", "file_id", "image_checksum_sha256", "at_ms", "part", "claims",
            "evidence_refs", "coverage_gaps");

    private VideoDeckValidator() {}

    static void validate(Map<String, Object> payload, Map<String, Object> bundle,
                         Map<String, Object> plan, String bundleDigest, String planDigest) {
        VideoKnowledgePlanValidator.validate(plan, bundle, bundleDigest);
        Map<?, ?> ir = map(payload.get("video_deck_ir"));
        if (!ir.keySet().equals(IR_FIELDS) || !"video-deck-ir-v1".equals(ir.get("schema_version"))
                || !bundleDigest.equals(ir.get("bundle_content_digest"))
                || !planDigest.equals(ir.get("plan_content_digest"))
                || !bundle.get("bvid").equals(ir.get("bvid"))
                || !bundle.get("part").equals(ir.get("part"))) throw invalid();
        List<?> nodes = list(plan.get("nodes"));
        String title = text(map(nodes.get(0)).get("title"));
        if (!title.equals(ir.get("title")) || !title.equals(map(payload.get("content_ir")).get("title")))
            throw invalid();
        Map<String, Map<?, ?>> frames = new LinkedHashMap<>();
        for (Object raw : list(bundle.get("frames"))) {
            Map<?, ?> frame = map(raw);
            frames.put(text(frame.get("frame_id")), frame);
        }
        if (frames.isEmpty() || frames.size() > 32) throw invalid();
        List<Map<String, Object>> expected = new ArrayList<>();
        boolean semantic = false;
        for (Object rawNode : nodes) {
            Map<?, ?> node = map(rawNode);
            List<String> claims = new ArrayList<>();
            LinkedHashSet<String> refs = new LinkedHashSet<>();
            for (Object rawClaim : list(node.get("claims"))) {
                Map<?, ?> claim = map(rawClaim);
                if (!"EXTRACTED".equals(claim.get("status")) || claims.size() == 2) continue;
                claims.add(text(claim.get("text")));
                for (Object rawRef : list(claim.get("evidence_refs"))) refs.add(text(rawRef));
            }
            if (!claims.isEmpty() && Set.of("CONCEPT", "EXAMPLE").contains(node.get("kind")))
                semantic = true;
            for (Object rawFrameId : list(node.get("frame_ids"))) {
                String frameId = text(rawFrameId);
                Map<?, ?> frame = frames.get(frameId);
                if (frame == null) throw invalid();
                LinkedHashSet<String> gaps = new LinkedHashSet<>();
                for (Object gap : list(node.get("missing"))) gaps.add(text(gap));
                if ("EVIDENCE_WINDOW".equals(node.get("kind")))
                    gaps.add("VISUAL_SEMANTICS_UNVERIFIED");
                Map<String, Object> slide = new LinkedHashMap<>();
                slide.put("node_id", node.get("node_id"));
                slide.put("title", node.get("title"));
                slide.put("frame_id", frameId);
                slide.put("file_id", frame.get("file_id"));
                slide.put("image_checksum_sha256", frame.get("checksum_sha256"));
                slide.put("at_ms", frame.get("at_ms"));
                slide.put("part", frame.get("part"));
                slide.put("claims", claims);
                slide.put("evidence_refs", new ArrayList<>(refs));
                slide.put("coverage_gaps", new ArrayList<>(gaps));
                expected.add(slide);
            }
        }
        if (!semantic || expected.size() != frames.size()) throw invalid();
        expected.sort(Comparator.comparingInt((Map<String, Object> slide) ->
                number(slide.get("at_ms"))).thenComparing(slide -> text(slide.get("frame_id"))));
        List<?> actualSlides = list(ir.get("slides"));
        if (actualSlides.size() != expected.size()) throw invalid();
        for (int index = 0; index < expected.size(); index++) {
            Map<String, Object> slide = expected.get(index);
            slide.put("sequence_no", index + 1);
            Map<?, ?> actual = map(actualSlides.get(index));
            if (!actual.keySet().equals(SLIDE_FIELDS) || !actual.equals(slide)) throw invalid();
        }
        Map<String, Object> unsigned = new LinkedHashMap<>();
        ir.forEach((key, value) -> {
            if (!"content_digest".equals(key)) unsigned.put(String.valueOf(key), value);
        });
        if (!hashJson(unsigned).equals(ir.get("content_digest"))) throw invalid();
        String markdown = markdown(title, text(ir.get("bvid")), expected);
        if (!markdown.equals(payload.get("markdown"))) throw invalid();
        List<?> sections = list(payload.get("sections"));
        if (sections.size() != expected.size()) throw invalid();
        for (int index = 0; index < expected.size(); index++) {
            Map<String, Object> slide = expected.get(index);
            @SuppressWarnings("unchecked")
            List<String> claims = (List<String>) slide.get("claims");
            @SuppressWarnings("unchecked")
            List<String> refs = (List<String>) slide.get("evidence_refs");
            List<String> sourceRefs = new ArrayList<>(refs);
            sourceRefs.add("frame:" + slide.get("frame_id"));
            Map<?, ?> section = map(sections.get(index));
            if (!section.keySet().equals(Set.of("heading", "body", "source_refs"))
                    || !slide.get("title").equals(section.get("heading"))
                    || !(claims.isEmpty() ? "Visual evidence only; meaning remains unverified."
                        : String.join("\n\n", claims)).equals(section.get("body"))
                    || !sourceRefs.equals(section.get("source_refs"))) throw invalid();
        }
        if (!sections.equals(map(payload.get("content_ir")).get("sections"))) throw invalid();
        Map<?, ?> trace = map(payload.get("export_trace"));
        if (!"COMPILED".equals(trace.get("status")) || !"PPTX".equals(trace.get("format"))
                || !"learning-deck.pptx".equals(trace.get("file_name"))
                || number(trace.get("preview_count")) != expected.size()) throw invalid();
        List<?> required = list(map(payload.get("candidate")).get("required_files"));
        if (required.size() != expected.size() + 2) throw invalid();
        Set<String> keys = new LinkedHashSet<>();
        for (Object raw : required) {
            Map<?, ?> file = map(raw);
            String role = text(file.get("role"));
            int sequence = number(file.get("sequence_no"));
            String variant = text(file.get("variant"));
            String name = text(file.get("file_name"));
            String key = role + ":" + variant + ":" + sequence;
            if (!keys.add(key) || !(role.equals("PRIMARY_MARKDOWN") && variant.isEmpty()
                    && sequence == 0 || role.equals("PRIMARY_PPTX") && variant.equals("original")
                    && sequence == 0 && name.equals("learning-deck.pptx")
                    || role.equals("SLIDE_PREVIEW") && variant.equals("original")
                    && sequence >= 1 && sequence <= expected.size()
                    && name.equals("slide-%03d.png".formatted(sequence)))) throw invalid();
        }
        if (keys.size() != expected.size() + 2
                || !keys.contains("PRIMARY_MARKDOWN::0")
                || !keys.contains("PRIMARY_PPTX:original:0")) throw invalid();
        for (int index = 1; index <= expected.size(); index++) {
            if (!keys.contains("SLIDE_PREVIEW:original:" + index)) throw invalid();
        }
    }

    private static String markdown(String title, String bvid, List<Map<String, Object>> slides) {
        List<String> lines = new ArrayList<>(List.of("# " + title, ""));
        for (Map<String, Object> slide : slides) {
            lines.add("## " + slide.get("sequence_no") + ". " + slide.get("title"));
            lines.add("");
            lines.add("- Video: " + bvid + " P" + slide.get("part") + " @ "
                    + String.format(Locale.ROOT, "%.1f", number(slide.get("at_ms")) / 1000.0) + "s");
            lines.add("- Original frame: " + slide.get("frame_id") + " ("
                    + slide.get("image_checksum_sha256") + ")");
            for (Object claim : list(slide.get("claims"))) lines.add("- " + claim);
            for (Object ref : list(slide.get("evidence_refs"))) lines.add("- Evidence: " + ref);
            for (Object gap : list(slide.get("coverage_gaps"))) lines.add("- Gap: " + gap);
            lines.add("");
        }
        return String.join("\n", lines).stripTrailing() + "\n";
    }

    private static String hashJson(Object value) {
        try {
            String json = MAPPER.writeValueAsString(canonical(value));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(json.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw invalid();
        }
    }

    private static Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, child) -> sorted.put(String.valueOf(key), canonical(child)));
            return sorted;
        }
        if (value instanceof List<?> list) return list.stream().map(VideoDeckValidator::canonical).toList();
        return value;
    }

    private static Map<?, ?> map(Object value) {
        if (value instanceof Map<?, ?> map) return map;
        throw invalid();
    }

    private static List<?> list(Object value) {
        if (value instanceof List<?> list) return list;
        throw invalid();
    }

    private static String text(Object value) { return value instanceof String text ? text : ""; }
    private static int number(Object value) { return value instanceof Integer number ? number : -1; }

    private static BusinessException invalid() {
        return new BusinessException("VIDEO_DECK_INVALID",
                "学习 PPTX 与冻结视频证据或逐页交付清单不一致", HttpStatus.CONFLICT);
    }
}
