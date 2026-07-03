package com.noteweave.config;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "noteweave")
public record NoteWeaveProperties(Storage storage, Document document) {

    public NoteWeaveProperties {
        if (storage == null) {
            storage = new Storage(Path.of("target/noteweave-storage"));
        }
        if (document == null) {
            document = new Document(900, 120);
        }
    }

    public record Storage(Path localRoot) {
    }

    public record Document(int chunkSize, int chunkOverlap) {
    }
}
