package com.noteweave.infra;

import com.noteweave.config.NoteWeaveProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.stereotype.Component;

@Component
public class LocalObjectStorage {

    private final Path root;

    public LocalObjectStorage(NoteWeaveProperties properties) {
        this.root = properties.storage().localRoot();
    }

    public Path write(String objectKey, byte[] content) {
        try {
            Path target = root.resolve(objectKey).normalize();
            Files.createDirectories(target.getParent());
            Files.write(target, content);
            return target;
        } catch (IOException ex) {
            throw new IllegalStateException("write object failed: " + objectKey, ex);
        }
    }

    public byte[] read(String objectKey) {
        try {
            return Files.readAllBytes(root.resolve(objectKey).normalize());
        } catch (IOException ex) {
            throw new IllegalStateException("read object failed: " + objectKey, ex);
        }
    }

    public boolean exists(String objectKey) {
        return Files.exists(root.resolve(objectKey).normalize());
    }

    public Path path(String objectKey) {
        return root.resolve(objectKey).normalize();
    }
}
