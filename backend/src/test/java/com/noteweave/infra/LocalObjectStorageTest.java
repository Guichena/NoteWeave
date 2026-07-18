package com.noteweave.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.noteweave.config.NoteWeaveProperties;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalObjectStorageTest {

    @TempDir
    Path tempDir;

    @Test
    void deleteShouldRemoveObjectAndRejectBucketEscape() {
        NoteWeaveProperties properties = new NoteWeaveProperties(
                new NoteWeaveProperties.Storage("local", tempDir, null),
                null, null, null, null, null);
        LocalObjectStorage storage = new LocalObjectStorage(properties);
        storage.write("noteweave-source", "workspace/workspace-1/file.txt", "content".getBytes());

        storage.delete("noteweave-source", "workspace/workspace-1/file.txt");

        assertThat(storage.exists("noteweave-source", "workspace/workspace-1/file.txt")).isFalse();
        assertThatThrownBy(() -> storage.delete("noteweave-source", "../outside.txt"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
