package com.noteweave.artifact;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import com.noteweave.common.BusinessException;

/** Rechecks Worker knowledge plans against the Host's immutable material bytes. */
final class VideoKnowledgePlanValidator {
    private VideoKnowledgePlanValidator() {}

    static void validate(Map<String, Object> plan, Map<String, Object> bundle, String bundleDigest) {
        if (plan == null || !plan.keySet().equals(Set.of("schema_version", "bundle_content_digest",
                "bvid", "part", "duration_ms", "nodes"))
                || !"video-knowledge-plan-v1".equals(plan.get("schema_version"))
                || !bundleDigest.equals(plan.get("bundle_content_digest"))
                || !bundle.get("bvid").equals(plan.get("bvid"))
                || !bundle.get("part").equals(plan.get("part"))
                || !bundle.get("duration_ms").equals(plan.get("duration_ms"))
                || !(plan.get("nodes") instanceof List<?> rawNodes)
                || rawNodes.isEmpty() || rawNodes.size() > 128) throw invalid("plan identity or shape is invalid");

        int duration = number(plan.get("duration_ms"));
        Map<String, Map<?, ?>> segments = index((List<?>) bundle.get("transcript_segments"), "segment_id");
        Map<String, Map<?, ?>> frames = index((List<?>) bundle.get("frames"), "frame_id");
        Map<String, List<String>> ocr = new HashMap<>();
        Object rawObservations = bundle.get("frame_observations");
        if (rawObservations instanceof List<?> observations) {
            for (Object raw : observations) {
                Map<?, ?> observation = (Map<?, ?>) raw;
                List<String> texts = new ArrayList<>();
                for (Object item : (List<?>) observation.get("observations")) {
                    texts.add(string(((Map<?, ?>) item).get("text")));
                }
                ocr.put(string(observation.get("file_id")), texts);
            }
        }

        Map<String, Map<?, ?>> nodes = index(rawNodes, "node_id");
        if (nodes.size() != rawNodes.size()) throw invalid("node IDs are duplicated");
        int roots = 0;
        Set<String> coveredSegments = new HashSet<>();
        Set<String> assignedFrames = new HashSet<>();
        for (Map<?, ?> node : nodes.values()) {
            if (!node.keySet().equals(Set.of("node_id", "parent_id", "kind", "title",
                    "start_ms", "end_ms", "transcript_segment_ids", "frame_ids",
                    "terms", "claims", "missing"))
                    || !Set.of("TOPIC", "CONCEPT", "EXAMPLE", "EVIDENCE_WINDOW")
                        .contains(node.get("kind"))
                    || string(node.get("title")).isBlank() || string(node.get("title")).length() > 160
                    || number(node.get("start_ms")) < 0
                    || number(node.get("end_ms")) <= number(node.get("start_ms"))
                    || number(node.get("end_ms")) > duration
                    || !(node.get("transcript_segment_ids") instanceof List<?> segmentRefs)
                    || !(node.get("frame_ids") instanceof List<?> frameRefs)
                    || !(node.get("terms") instanceof List<?> terms)
                    || !(node.get("claims") instanceof List<?> claims)
                    || !(node.get("missing") instanceof List<?> missing)
                    || segmentRefs.size() > 128 || frameRefs.size() > 32
                    || terms.size() > 20 || claims.size() > 10 || missing.size() > 20
                    || segmentRefs.size() != new HashSet<>(segmentRefs).size()
                    || frameRefs.size() != new HashSet<>(frameRefs).size()) throw invalid("node fields are invalid");
            if (string(node.get("parent_id")).isBlank()) {
                roots++;
                if (!"TOPIC".equals(node.get("kind"))) throw invalid("root must be a topic");
            }
            Set<String> visited = new HashSet<>();
            visited.add(string(node.get("node_id")));
            String parentId = string(node.get("parent_id"));
            while (!parentId.isBlank()) {
                Map<?, ?> parent = nodes.get(parentId);
                if (parent == null || !visited.add(parentId)
                        || number(parent.get("start_ms")) > number(node.get("start_ms"))
                        || number(parent.get("end_ms")) < number(node.get("end_ms")))
                    throw invalid("node hierarchy is invalid");
                parentId = string(parent.get("parent_id"));
            }
            Map<String, List<String>> evidence = new HashMap<>();
            for (Object ref : segmentRefs) {
                Map<?, ?> segment = segments.get(ref);
                if (segment == null || number(segment.get("end_ms")) < number(node.get("start_ms"))
                        || number(segment.get("start_ms")) > number(node.get("end_ms")))
                    throw invalid("subtitle reference is outside node time");
                coveredSegments.add(string(ref));
                evidence.put("segment:" + ref, List.of(string(segment.get("corrected_text"))));
            }
            for (Object ref : frameRefs) {
                Map<?, ?> frame = frames.get(ref);
                if (frame == null || number(frame.get("at_ms")) < number(node.get("start_ms"))
                        || number(frame.get("at_ms")) > number(node.get("end_ms"))
                        || !assignedFrames.add(string(ref))) throw invalid("frame assignment is invalid");
                List<String> lines = ocr.get(string(frame.get("file_id")));
                if (lines != null) evidence.put("frame:" + ref, lines);
            }
            for (Object rawTerm : terms) {
                String term = string(rawTerm);
                if (term.isBlank() || term.length() > 100 || evidence.values().stream()
                        .flatMap(List::stream).noneMatch(text -> text.toLowerCase(java.util.Locale.ROOT)
                                .contains(term.toLowerCase(java.util.Locale.ROOT))))
                    throw invalid("term has no cited evidence");
            }
            for (Object rawClaim : claims) {
                if (!(rawClaim instanceof Map<?, ?> claim)
                        || !claim.keySet().equals(Set.of("text", "status", "evidence_refs"))
                        || string(claim.get("text")).isBlank()
                        || string(claim.get("text")).length() > 1_000
                        || !(claim.get("evidence_refs") instanceof List<?> refs)
                        || refs.size() > 8) throw invalid("claim shape is invalid");
                if ("EXTRACTED".equals(claim.get("status"))) {
                    if (refs.isEmpty() || refs.stream().anyMatch(ref -> !evidence.containsKey(ref))
                            || refs.stream().noneMatch(ref -> evidence.get(ref).stream()
                                .anyMatch(text -> text.contains(string(claim.get("text"))))))
                        throw invalid("extracted claim is not in cited evidence");
                } else if (!"UNVERIFIED".equals(claim.get("status"))
                        || !refs.isEmpty() || !missing.contains("UNVERIFIED_CLAIM")) {
                    throw invalid("unsupported claim lacks an explicit gap");
                }
            }
            if (segmentRefs.isEmpty() && frameRefs.isEmpty() && missing.isEmpty()
                    && nodes.values().stream().noneMatch(child -> node.get("node_id")
                        .equals(child.get("parent_id")))) throw invalid("node has no evidence or child");
        }
        if (roots != 1 || !coveredSegments.equals(segments.keySet())
                || !assignedFrames.equals(frames.keySet())) throw invalid("plan coverage is incomplete");
    }

    private static Map<String, Map<?, ?>> index(List<?> records, String field) {
        Map<String, Map<?, ?>> result = new HashMap<>();
        for (Object raw : records) {
            if (!(raw instanceof Map<?, ?> value) || string(value.get(field)).isBlank()
                    || result.putIfAbsent(string(value.get(field)), value) != null) throw invalid("duplicate evidence ID");
        }
        return result;
    }

    private static int number(Object value) { return value instanceof Integer n ? n : -1; }
    private static String string(Object value) { return value instanceof String s ? s : ""; }
    private static BusinessException invalid(String message) {
        return new BusinessException("VIDEO_KNOWLEDGE_PLAN_INVALID", message, HttpStatus.CONFLICT);
    }
}

