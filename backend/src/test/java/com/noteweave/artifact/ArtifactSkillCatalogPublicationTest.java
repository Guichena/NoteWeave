package com.noteweave.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ArtifactSkillCatalogPublicationTest {
    @Test
    void hostAndWorkerUseIdenticalPublishedCatalogBytes() throws Exception {
        Path root = Path.of("reference");
        if (!Files.exists(root)) root = Path.of("..", "reference");
        byte[] source = Files.readAllBytes(root.resolve("artifact-skill-catalog-v2.json"));
        Path projectRoot = root.toAbsolutePath().normalize().getParent();
        byte[] host = Files.readAllBytes(projectRoot.resolve(
                "backend/src/main/resources/artifact-skill-catalog-v2.json"));
        byte[] worker = Files.readAllBytes(projectRoot.resolve(
                "workers/artifact-worker/app/artifact-skill-catalog-v2.json"));
        assertThat(host).isEqualTo(source);
        assertThat(worker).isEqualTo(source);
        assertThat(new ArtifactSkillCatalogService().catalogDigest()).isEqualTo(
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source)));
        ArtifactSkillCatalogService catalog = new ArtifactSkillCatalogService();
        assertThat(catalog.requiredFileRoles("bilibili_course_note_pdf"))
                .containsExactly("PRIMARY_MARKDOWN", "PRIMARY_PDF");
        assertThat(catalog.requiredFileRoles("study_guide"))
                .containsExactly("PRIMARY_MARKDOWN");
    }

    @Test
    @SuppressWarnings("unchecked")
    void rejectsUnpublishedVersionAndUnknownPolicyFields() throws Exception {
        byte[] source = ArtifactSkillCatalogService.class
                .getResourceAsStream("/artifact-skill-catalog-v2.json").readAllBytes();
        Map<String, Object> catalog = new ObjectMapper().readValue(source, Map.class);
        Map<String, Object> entry = (Map<String, Object>) ((java.util.List<?>) catalog.get("skills")).get(0);
        Map<String, Object> schema = (Map<String, Object>) entry.get("input_schema");
        entry.put("version", "999.0.0");
        assertThatThrownBy(() -> ArtifactSkillCatalogService.validatePublishedEntry(entry, schema))
                .isInstanceOf(IllegalStateException.class);
        entry.put("version", "1.0.0");
        entry.put("arbitrary_script", "run.py");
        assertThatThrownBy(() -> ArtifactSkillCatalogService.validatePublishedEntry(entry, schema))
                .isInstanceOf(IllegalStateException.class);
    }
}
