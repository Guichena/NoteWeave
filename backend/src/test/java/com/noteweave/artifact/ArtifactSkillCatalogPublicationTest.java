package com.noteweave.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArtifactSkillCatalogPublicationTest {
    @Test
    @SuppressWarnings("unchecked")
    void directoryDigestAndFileRolesMatchTheSharedWorkerContract() throws Exception {
        var catalog = new ArtifactSkillCatalogService();
        var payload = new ObjectMapper().readValue(Files.readAllBytes(Path.of(
                "../workers/artifact-worker/tests/pipeline/pipeline-result-v1.json")), Map.class);
        assertThat(catalog.catalogDigest()).isEqualTo(((Map<?, ?>) payload.get("candidate")).get("catalog_digest"));
        assertThat(catalog.requiredFileRoles("bilibili_course_note_pdf")).containsExactly("PRIMARY_MARKDOWN", "PRIMARY_PDF");
        assertThat(catalog.requiredFileRoles("study_guide")).containsExactly("PRIMARY_MARKDOWN");
        assertThat(catalog.requiredFileRoles("video_learning_deck")).containsExactly("PRIMARY_MARKDOWN", "PRIMARY_PPTX");
    }

    @Test
    void unknownContractRuleStopsCatalogLoadingBeforeJobCreation(@TempDir Path directory) throws Exception {
        Path skill = directory.resolve("briefing/SKILL.md");
        Files.createDirectories(skill.getParent());
        Files.writeString(skill, Files.readString(Path.of("../skills/briefing/SKILL.md"))
                .replace("contract:", "contract:\n  unknown_rule: true"));
        assertThatThrownBy(() -> new ArtifactSkillCatalogService(directory.toString(), "../schemas"))
                .hasMessageContaining("无法加载");
    }
}
