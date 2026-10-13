package com.noteweave.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ArtifactSectionedGraphPublicationTest {
    @SuppressWarnings("unchecked")
    private Map<String, Object> entry(String skill) throws Exception {
        Map<String, Object> catalog = new ObjectMapper().readValue(
                getClass().getResourceAsStream("/artifact-skill-catalog-v2.json"), Map.class);
        return (Map<String, Object>) ((List<?>) catalog.get("skills")).stream()
                .map(Map.class::cast).filter(item -> skill.equals(item.get("skill_key"))).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private void validate(Map<String, Object> entry) {
        ArtifactSkillCatalogService.validatePublishedEntry(entry, (Map<?, ?>) entry.get("input_schema"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> graph(Map<String, Object> entry) {
        return (Map<String, Object>) entry.get("graph_definition");
    }

    @ParameterizedTest
    @ValueSource(strings = {"study_guide", "quiz_pack", "wiki_page"})
    void acceptsAllThreeDeclaredSectionedGraphsAndTheirExistingBacktrackNodes(String skill) throws Exception {
        var entry = entry(skill);
        assertThat(entry.get("generation_mode")).isEqualTo("SECTIONED");
        assertThatCode(() -> validate(entry)).doesNotThrowAnyException();
        List<?> nodes = (List<?>) graph(entry).get("nodes");
        assertThat(nodes).hasSize(7);
        assertThat(((Map<?, ?>) nodes.get(2)).get("node_id"))
                .isEqualTo("quiz_pack".equals(skill) ? "design" : "write");
    }

    @Test
    void legacyPublicationWithoutModeUsesSingleGenerationAndPublicMetadataKeepsExecutionStrategyPrivate() throws Exception {
        var legacy = entry("report_draft");
        legacy.remove("generation_mode");
        assertThatCode(() -> validate(legacy)).doesNotThrowAnyException();
        String metadata = new ObjectMapper().writeValueAsString(new ArtifactSkillCatalogService().listSkills());
        assertThat(metadata).doesNotContain("generation_mode", "chapter_plan", "citation_assignments", "consistency_trace");
    }

    @ParameterizedTest
    @ValueSource(strings = {"parallel", "SECTION_SYNTHESIS", "sectioned", ""})
    void rejectsUnknownOrRecipeSpecificExecutionModes(String mode) throws Exception {
        var entry = entry("report_draft");
        entry.put("generation_mode", mode);
        assertThatThrownBy(() -> validate(entry)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsExplicitNullAndWrongModeForBothSingleAndSectionedGraphs() throws Exception {
        var single = entry("resume_highlight");
        single.put("generation_mode", null);
        assertThatThrownBy(() -> validate(single)).isInstanceOf(IllegalStateException.class);
        single.put("generation_mode", "SECTIONED");
        assertThatThrownBy(() -> validate(single)).isInstanceOf(IllegalStateException.class);
        var sectioned = entry("study_guide");
        sectioned.put("generation_mode", "SINGLE");
        assertThatThrownBy(() -> validate(sectioned)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void rejectsMissingPlannerEvenWhenRemainingGraphIsValidAndAcyclic() throws Exception {
        var entry = entry("study_guide");
        var graph = graph(entry);
        ((List<Map<String, Object>>) graph.get("nodes")).removeIf(node -> "outline".equals(node.get("node_id")));
        graph.put("edges", edges(List.of("digest", "write", "consistency", "cite", "guard", "repair")));
        assertThatThrownBy(() -> validate(entry)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsCitationBeforeConsistencyDespiteAcyclicDependenciesAndCorrectNodeDeclarationOrder() throws Exception {
        var entry = entry("study_guide");
        graph(entry).put("edges", edges(List.of("digest", "outline", "write", "cite", "consistency", "guard", "repair")));
        assertThatThrownBy(() -> validate(entry)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void rejectsUnknownStateFieldsAndUnregisteredNodeTypes() throws Exception {
        var entry = entry("study_guide");
        var node = (Map<String, Object>) ((List<?>) graph(entry).get("nodes")).get(1);
        node.put("node_kind", "TOOL");
        assertThatThrownBy(() -> validate(entry)).isInstanceOf(IllegalStateException.class);
        node.put("node_kind", "LLM_STEP");
        var properties = (Map<String, Object>) ((Map<?, ?>) node.get("output_schema")).get("properties");
        properties.put("arbitrary_state", Map.of("type", "object"));
        assertThatThrownBy(() -> validate(entry)).isInstanceOf(IllegalStateException.class);
    }

    private List<Map<String, Object>> edges(List<String> order) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (int index = 0; index < order.size() - 1; index++) {
            result.add(Map.of("from_node", order.get(index), "to_node", order.get(index + 1), "edge_type", "PREREQUISITE"));
        }
        return result;
    }
}
