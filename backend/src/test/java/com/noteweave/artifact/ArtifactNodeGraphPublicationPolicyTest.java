package com.noteweave.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ArtifactNodeGraphPublicationPolicyTest {
    @SuppressWarnings("unchecked")
    private Map<String, Object> entry() throws Exception {
        Map<String, Object> catalog = new ObjectMapper().readValue(
                getClass().getResourceAsStream("/artifact-skill-catalog-v2.json"), Map.class);
        return (Map<String, Object>) ((List<?>) catalog.get("skills")).get(0);
    }

    private void validate(Map<String, Object> entry) {
        ArtifactNodeGraphPublicationPolicy.validate(
                entry.get("graph_key"), entry.get("graph_definition"), entry.get("output_contract"));
    }

    @Test
    void acceptsDeclaredGraphsAndKeepsNodeRecipesOutOfPublicMetadata() throws Exception {
        validate(entry());
        String metadata = new ObjectMapper().writeValueAsString(new ArtifactSkillCatalogService().listSkills());
        assertThat(metadata).doesNotContain("graph_definition", "node_kind", "node.resume", "output_schema");
    }

    @Test
    @SuppressWarnings("unchecked")
    void rejectsUnknownNodeKindsRecipesAndOpenSchemas() throws Exception {
        var entry = entry();
        var graph = (Map<String, Object>) entry.get("graph_definition");
        var node = (Map<String, Object>) ((List<?>) graph.get("nodes")).get(1);
        node.put("node_kind", "TOOL");
        assertThatThrownBy(() -> validate(entry)).isInstanceOf(IllegalStateException.class);
        node.put("node_kind", "LLM_STEP");
        node.put("prompt_recipe_id", "node.unknown.v1");
        assertThatThrownBy(() -> validate(entry)).isInstanceOf(IllegalStateException.class);
        node.put("prompt_recipe_id", "node.resume_highlight_extractor.v1");
        ((Map<String, Object>) node.get("input_schema")).put("additionalProperties", true);
        assertThatThrownBy(() -> validate(entry)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void rejectsCyclicDependenciesAndInvalidBacktrackNodes() throws Exception {
        var entry = entry();
        var graph = (Map<String, Object>) entry.get("graph_definition");
        var edges = (List<Object>) graph.get("edges");
        edges.add(Map.of("from_node", "repair", "to_node", "material_retrieve", "edge_type", "PREREQUISITE"));
        assertThatThrownBy(() -> validate(entry)).isInstanceOf(IllegalStateException.class);
        edges.remove(edges.size() - 1);
        var contract = (Map<String, Object>) entry.get("output_contract");
        ((Map<String, Object>) ((List<?>) contract.get("rules")).get(0)).put("backtrack_to", "missing");
        assertThatThrownBy(() -> validate(entry)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void rejectsUndeclaredStateFieldsAndExternalSchemaReferences() throws Exception {
        var entry = entry();
        var graph = (Map<String, Object>) entry.get("graph_definition");
        var node = (Map<String, Object>) ((List<?>) graph.get("nodes")).get(1);
        var schema = (Map<String, Object>) node.get("input_schema");
        var properties = (Map<String, Object>) schema.get("properties");
        properties.put("host_permissions", Map.of("type", "object"));
        assertThatThrownBy(() -> validate(entry)).isInstanceOf(IllegalStateException.class);
        properties.remove("host_permissions");
        properties.put("material_passages", Map.of("$ref", "https://invalid.example/schema"));
        assertThatThrownBy(() -> validate(entry)).isInstanceOf(IllegalStateException.class);
    }
}
