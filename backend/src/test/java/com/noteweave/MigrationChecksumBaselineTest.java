package com.noteweave;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class MigrationChecksumBaselineTest {

    @Test
    void publishedMigrationsV001ToV024MustRemainImmutable() throws Exception {
        Path migrationDirectory = Path.of("src/main/resources/db/migration");
        Map<String, String> expected = new LinkedHashMap<>();
        for (String line : Files.readAllLines(Path.of("src/test/resources/migration-checksums-v001-v024.sha256"))) {
            if (!line.isBlank()) {
                String[] parts = line.trim().split("\\s+", 2);
                expected.put(parts[1], parts[0]);
            }
        }

        Set<String> actualPublishedFiles;
        try (var files = Files.list(migrationDirectory)) {
            actualPublishedFiles = files
                    .map(path -> path.getFileName().toString())
                    .filter(this::isPublishedMigration)
                    .collect(Collectors.toSet());
        }
        assertThat(actualPublishedFiles).containsExactlyInAnyOrderElementsOf(expected.keySet());

        for (Map.Entry<String, String> entry : expected.entrySet()) {
            byte[] content = Files.readString(migrationDirectory.resolve(entry.getKey()))
                    .replace("\r\n", "\n")
                    .replace('\r', '\n')
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            String actualHash = java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content));
            assertThat(actualHash)
                    .as("historical migration %s must not be edited", entry.getKey())
                    .isEqualTo(entry.getValue());
        }
    }

    private boolean isPublishedMigration(String fileName) {
        if (!fileName.matches("V\\d{3}__.+\\.sql")) {
            return false;
        }
        return Integer.parseInt(fileName.substring(1, 4)) <= 24;
    }
}
