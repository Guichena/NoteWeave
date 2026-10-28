package com.noteweave.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArtifactSkillDirectoryTest {
    private final Path root = Path.of("..").toAbsolutePath().normalize();

    private ArtifactSkillCatalogService catalog() {
        return new ArtifactSkillCatalogService(root.resolve("skills").toString(), root.resolve("schemas").toString(), "v2");
    }

    @Test
    void sharedDirectoryListsShapesAndResolvesOldAliases() {
        ArtifactSkillCatalogService service = catalog();
        assertThat(service.listSkills()).hasSize(14);
        assertThat(service.listSkills()).extracting(ArtifactSkillSummaryResponse::kind)
                .contains("DOC", "QUIZ", "CARDS", "TREE", "TABLE", "DECK");
        assertThat(service.resolveSkill("report_draft").skillKey()).isEqualTo("briefing");
        assertThat(service.resolveSkill("knowledge_blog").skillKey()).isEqualTo("blog-post");
        assertThat(service.resolveSkill("mindmap_from_workspace").skillKey()).isEqualTo("mind-map");
        assertThat(service.resolveSkill("video_learning_deck").skillKey()).isEqualTo("video-deck");
        assertThat(service.resolveSkill("bilibili_course_note_pdf").skillKey()).isEqualTo("lecture-notes-pdf");
        assertThat(service.requiredFileRoles("slide-deck")).containsExactly("PRIMARY_MARKDOWN", "PRIMARY_PPTX");
        assertThat(service.requiredFileRoles("lecture-notes-pdf")).containsExactly("PRIMARY_MARKDOWN", "PRIMARY_PDF");
        assertThat(service.requiredFileRoles("data-table")).containsExactly("PRIMARY_MARKDOWN", "PRIMARY_CSV");
        assertThat(new ArtifactSkillCatalogService("../skills", "../schemas", "legacy").kind("mindmap_from_workspace"))
                .isEqualTo("TREE");
        assertThat(service.validateAndNormalizeInputs(service.resolveSkill("quiz"), Map.of())).containsEntry("count", "10");
        assertThatThrownBy(() -> service.validateAndNormalizeInputs(service.resolveSkill("quiz"), Map.of("count", "999")))
                .hasMessageContaining("取值不受支持");
        assertThatThrownBy(() -> service.validateAndNormalizeInputs(service.resolveSkill("quiz"), Map.of("unknown", "x")))
                .hasMessageContaining("不支持");
    }

    @Test
    @SuppressWarnings("unchecked")
    void hostAndPythonWorkerUseTheSameDirectoryDigest() throws Exception {
        Map<String, Object> payload = new ObjectMapper().readValue(Files.readAllBytes(root.resolve(
                "workers/artifact-worker/tests/pipeline/pipeline-result-v1.json")), Map.class);
        assertThat(catalog().catalogDigest()).isEqualTo(((Map<?, ?>) payload.get("candidate")).get("catalog_digest"));
    }

    @Test
    void oneAdditionalSkillFileAppearsInTheExistingListEndpoint(@TempDir Path directory) throws Exception {
        Path skill = directory.resolve("new-learning-product/SKILL.md");
        Files.createDirectories(skill.getParent());
        String content = Files.readString(root.resolve("skills/study-guide/SKILL.md"))
                .replace("name: study-guide", "name: new-learning-product").replace("- study_guide", "- new_learning_alias");
        Files.writeString(skill, content);
        ArtifactSkillCatalogService service = new ArtifactSkillCatalogService(directory.toString(), root.resolve("schemas").toString(), "v2");
        assertThat(new ArtifactSkillController(service).listSkills().data()).singleElement()
                .satisfies(value -> {
                    assertThat(value.skillKey()).isEqualTo("new-learning-product");
                    assertThat(value.kind()).isEqualTo("DOC");
                });
        assertThat(service.resolveSkill("new_learning_alias").skillKey()).isEqualTo("new-learning-product");
        Files.writeString(skill, content.replace("\r\n", "\n").replace("\n", "\r\n"));
        assertThat(new ArtifactSkillCatalogService(directory.toString(), root.resolve("schemas").toString(), "v2").catalogDigest())
                .isEqualTo(service.catalogDigest());
        Files.writeString(skill, content.replace("sections:", "unknown_rule:"));
        assertThatThrownBy(() -> new ArtifactSkillCatalogService(directory.toString(), root.resolve("schemas").toString(), "v2"))
                .isInstanceOf(IllegalStateException.class).hasRootCauseMessage("契约规则未注册或不适用于形态：unknown_rule");
    }
}
