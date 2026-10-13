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
    void composeKeepsSourceOrderAndUsesStreamingEvenIfByteArrayReadIsDisabled() throws Exception {
        NoteWeaveProperties properties = new NoteWeaveProperties(
                new NoteWeaveProperties.Storage("local", tempDir, null), null, null, null, null, null);
        LocalObjectStorage storage = new LocalObjectStorage(properties) {
            @Override public byte[] read(String bucket, String key) {
                throw new AssertionError("流式上传合并不能调用整体读取");
            }
        };
        storage.write("source", "part-0", "first".getBytes());
        storage.write("source", "part-1", "second".getBytes());
        storage.compose("source", java.util.List.of("part-0", "part-1"), "source", "merged");
        try (var stream = storage.openStream("source", "merged")) {
            assertThat(new String(stream.readNBytes(11))).isEqualTo("firstsecond");
        }
        assertThatThrownBy(() -> storage.compose("source", java.util.List.of("../outside"), "source", "bad"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.compose("source", java.util.List.of("merged"), "source", "merged"))
                .isInstanceOf(IllegalArgumentException.class);
    }

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

    @Test
    void everyOperationShouldRejectObjectEscape() {
        NoteWeaveProperties properties = new NoteWeaveProperties(
                new NoteWeaveProperties.Storage("local", tempDir, null),
                null, null, null, null, null);
        LocalObjectStorage storage = new LocalObjectStorage(properties);

        assertThatThrownBy(() -> storage.write("noteweave-source", "../outside.txt", new byte[]{1}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.read("noteweave-source", "../outside.txt"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.exists("noteweave-source", "../outside.txt"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void stagedInventoryPaginatesWithinPrefix() {
        NoteWeaveProperties properties = new NoteWeaveProperties(
                new NoteWeaveProperties.Storage("local", tempDir, null),
                null, null, null, null, null);
        LocalObjectStorage storage = new LocalObjectStorage(properties);
        storage.write("noteweave-export", "artifacts/staged/task/a.md", new byte[]{1});
        storage.write("noteweave-export", "artifacts/staged/task/b.md", new byte[]{2});
        storage.write("noteweave-export", "artifacts/other/c.md", new byte[]{3});

        var first = storage.list("noteweave-export", "artifacts/staged/", "", 1);
        var second = storage.list("noteweave-export", "artifacts/staged/", first.get(0).key(), 1);
        assertThat(first).extracting(com.noteweave.storage.ObjectStorage.StoredObject::key)
                .containsExactly("artifacts/staged/task/a.md");
        assertThat(second).extracting(com.noteweave.storage.ObjectStorage.StoredObject::key)
                .containsExactly("artifacts/staged/task/b.md");
        assertThatThrownBy(() -> storage.list("noteweave-export", "../", "", 1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
