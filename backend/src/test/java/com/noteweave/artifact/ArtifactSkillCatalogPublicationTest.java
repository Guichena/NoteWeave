package com.noteweave.artifact;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
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
    }
}
