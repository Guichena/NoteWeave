package com.noteweave.artifact;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 宿主检查节点发布结构；实际 JSON Schema 与执行配方由 Worker 校验。 */
final class ArtifactNodeGraphPublicationPolicy {
    private ArtifactNodeGraphPublicationPolicy() {}

    private static final Set<String> GRAPH_FIELDS = Set.of(
            "graph_key", "graph_name", "action_type", "nodes", "edges", "schema_contract");
    private static final Set<String> NODE_FIELDS = Set.of(
            "node_id", "skill_key", "purpose", "optional", "node_kind",
            "prompt_recipe_id", "input_schema", "output_schema");
    private static final Set<String> STATE_FIELDS = Set.of(
            "generation_context", "material_passages", "section_materials", "facts", "normalized_facts",
            "bullets", "sections", "verification", "retrieval_trace", "repair_trace");
    private static final Map<String, String> SKILL_KINDS = Map.ofEntries(
            Map.entry("workspace_material_digest", "RETRIEVE"),
            Map.entry("resume_highlight_extractor", "LLM_STEP"),
            Map.entry("resume_impact_normalizer", "LLM_STEP"),
            Map.entry("resume_bullet_writer", "LLM_STEP"),
            Map.entry("resume_verifier", "VERIFY"), Map.entry("resume_local_repair", "REPAIR"),
            Map.entry("generic_section_repair", "REPAIR"),
            Map.entry("generic_section_writer", "LLM_STEP"), Map.entry("quiz_designer", "LLM_STEP"),
            Map.entry("quiz_difficulty_normalizer", "LLM_STEP"), Map.entry("wiki_structure_enforcer", "LLM_STEP"),
            Map.entry("style_polisher", "LLM_STEP"), Map.entry("evidence_guard", "VERIFY"),
            Map.entry("frozen_video_deck_layout", "TOOL"), Map.entry("original_frame_pptx_renderer", "TOOL"),
            Map.entry("frozen_video_deriver", "TOOL"));

    static void validate(Object graphKey, Object rawDefinition, Object rawContract) {
        Map<?, ?> graph = object(rawDefinition);
        require(graph.keySet().equals(GRAPH_FIELDS) && graphKey.equals(graph.get("graph_key")));
        require(graph.get("graph_name") instanceof String name && !name.isBlank());
        require(graph.get("action_type") instanceof String action && !action.isBlank());
        List<?> nodes = array(graph.get("nodes"));
        List<?> edges = array(graph.get("edges"));
        require(!nodes.isEmpty() && nodes.size() <= 40 && edges.size() <= 160);
        array(graph.get("schema_contract"));
        Set<String> ids = new HashSet<>();
        Map<String, Integer> indegree = new HashMap<>();
        Map<String, List<String>> adjacency = new HashMap<>();
        for (Object rawNode : nodes) {
            Map<?, ?> node = object(rawNode);
            require(node.keySet().equals(NODE_FIELDS));
            require(node.get("node_id") instanceof String id && id.matches("[a-z][a-z0-9_]{0,99}") && ids.add(id));
            String id = String.valueOf(node.get("node_id"));
            String skill = String.valueOf(node.get("skill_key"));
            String expectedKind = SKILL_KINDS.get(skill);
            require(expectedKind != null && expectedKind.equals(node.get("node_kind")));
            require(("node." + skill + ".v1").equals(node.get("prompt_recipe_id")));
            require(node.get("purpose") instanceof String purpose && !purpose.isBlank());
            require(node.get("optional") instanceof Boolean);
            if ("TOOL".equals(expectedKind)) {
                require(Set.of("video_deck_v1", "video_derived_text_v1").contains(String.valueOf(graphKey)));
            }
            validateSchema(node.get("input_schema"));
            validateSchema(node.get("output_schema"));
            indegree.put(id, 0);
            adjacency.put(id, new java.util.ArrayList<>());
        }
        Set<String> edgeIds = new HashSet<>();
        for (Object rawEdge : edges) {
            Map<?, ?> edge = object(rawEdge);
            require(edge.keySet().equals(Set.of("from_node", "to_node", "edge_type"))
                    && "PREREQUISITE".equals(edge.get("edge_type")));
            String from = String.valueOf(edge.get("from_node"));
            String to = String.valueOf(edge.get("to_node"));
            require(ids.contains(from) && ids.contains(to) && edgeIds.add(from + ":" + to));
            indegree.put(to, indegree.get(to) + 1);
            adjacency.get(from).add(to);
        }
        ArrayDeque<String> queue = new ArrayDeque<>();
        indegree.forEach((id, count) -> { if (count == 0) queue.add(id); });
        int visited = 0;
        while (!queue.isEmpty()) {
            String id = queue.remove();
            visited++;
            for (String next : adjacency.get(id)) {
                int count = indegree.get(next) - 1;
                indegree.put(next, count);
                if (count == 0) queue.add(next);
            }
        }
        require(visited == nodes.size());
        Map<?, ?> contract = object(rawContract);
        for (Object rule : array(contract.get("rules"))) {
            require(ids.contains(String.valueOf(object(rule).get("backtrack_to"))));
        }
    }

    private static void validateSchema(Object value) {
        Map<?, ?> schema = object(value);
        require("object".equals(schema.get("type")) && Boolean.FALSE.equals(schema.get("additionalProperties")));
        Map<?, ?> properties = object(schema.get("properties"));
        require(STATE_FIELDS.containsAll(properties.keySet()));
        List<?> required = array(schema.get("required"));
        require(properties.keySet().containsAll(required) && required.size() == Set.copyOf(required).size());
        rejectReferences(schema, 0);
    }

    private static void rejectReferences(Object value, int depth) {
        require(depth <= 64);
        if (value instanceof Map<?, ?> map) {
            require(!map.containsKey("$ref") && !map.containsKey("$dynamicRef"));
            map.values().forEach(child -> rejectReferences(child, depth + 1));
        } else if (value instanceof List<?> list) {
            list.forEach(child -> rejectReferences(child, depth + 1));
        }
    }

    private static Map<?, ?> object(Object value) {
        require(value instanceof Map<?, ?>);
        return (Map<?, ?>) value;
    }

    private static List<?> array(Object value) {
        require(value instanceof List<?>);
        return (List<?>) value;
    }

    private static void require(boolean valid) {
        if (!valid) throw new IllegalStateException("产物节点图不符合发布规范");
    }
}
