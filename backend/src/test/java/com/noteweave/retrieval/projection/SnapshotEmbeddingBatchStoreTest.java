package com.noteweave.retrieval.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.noteweave.retrieval.projection.SourceRetrievalProjectionService.BatchEmbeddings;
import com.noteweave.source.SourcePipelineStages;
import com.noteweave.storage.ObjectStorage;
import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SnapshotEmbeddingBatchStoreTest {
    private final Map<String, byte[]> objects = new HashMap<>();
    private final ObjectStorage storage = mock(ObjectStorage.class);
    private final SnapshotEmbeddingStore store = new SnapshotEmbeddingStore(storage);

    SnapshotEmbeddingBatchStoreTest() {
        when(storage.write(anyString(), anyString(), any(byte[].class))).thenAnswer(call -> {
            objects.put(call.getArgument(1), call.getArgument(2)); return null;
        });
        when(storage.exists(anyString(), anyString())).thenAnswer(call -> objects.containsKey(call.getArgument(1)));
        when(storage.openStream(anyString(), anyString())).thenAnswer(call -> new ByteArrayInputStream(objects.get(call.getArgument(1))));
    }

    @Test
    void batchRoundTripKeepsContextsCallCountHashesAndFiniteVectors() throws Exception {
        BatchEmbeddings batch = embeddings(true);
        assertThat(SnapshotEmbeddingStore.decodeBatch(SnapshotEmbeddingStore.encodeBatch(batch))).isEqualTo(batch);
    }

    @Test
    void manifestRoundTripUsesBoundedStreamAndAuthenticatesEveryBatch() {
        var claimed = row("lease-1", null, null);
        var saved = store.saveBatch(claimed, embeddings(true));
        String key = store.saveManifest(List.of(row("lease-1", saved.objectKey(), saved.sha256())));
        var manifest = store.loadManifest(key, "workspace-1", "source-1", "snapshot-1");
        assertThat(store.loadBatch(manifest, manifest.batches().get(0))).isEqualTo(embeddings(true));
        verify(storage, never()).read(anyString(), anyString());
        objects.get(saved.objectKey())[10] ^= 1;
        assertThatThrownBy(() -> store.loadBatch(manifest, manifest.batches().get(0))).hasMessageContaining("MANIFEST_INVALID");
    }

    @Test
    void lateLeaseUsesSeparateObjectAndCannotOverwriteACommittedBatch() {
        var first = store.saveBatch(row("lease-first", null, null), embeddings(true));
        var second = store.saveBatch(row("lease-second", null, null), embeddings(true));
        assertThat(first.objectKey()).isNotEqualTo(second.objectKey());
        assertThat(objects).containsKeys(first.objectKey(), second.objectKey());
    }

    @Test
    void unknownFieldsDuplicateFieldsTrailingJsonAndForeignIdentityAreRejected() throws Exception {
        var saved = store.saveBatch(row("lease", null, null), embeddings(true));
        String key = store.saveManifest(List.of(row("lease", saved.objectKey(), saved.sha256())));
        byte[] original = objects.get(key);
        String json = new String(original, java.nio.charset.StandardCharsets.UTF_8);
        for (String invalid : List.of(json.replace("\"schemaVersion\":2", "\"schemaVersion\":2,\"schemaVersion\":2"),
                json.replace("\"schemaVersion\":2", "\"schemaVersion\":2,\"unknown\":true"), json + "{}")) {
            objects.put(key, invalid.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertThatThrownBy(() -> store.loadManifest(key, "workspace-1", "source-1", "snapshot-1")).hasMessageContaining("MANIFEST_INVALID");
        }
        objects.put(key, original);
        assertThatThrownBy(() -> store.loadManifest(key, "other-workspace", "source-1", "snapshot-1")).hasMessageContaining("MANIFEST_INVALID");
    }

    @Test
    void oversizedOrTruncatedBinaryAndAppendedBytesCannotBeLoaded() throws Exception {
        byte[] valid = SnapshotEmbeddingStore.encodeBatch(embeddings(true));
        assertThatThrownBy(() -> SnapshotEmbeddingStore.decodeBatch(java.util.Arrays.copyOf(valid, valid.length - 1))).isInstanceOf(java.io.IOException.class);
        assertThatThrownBy(() -> SnapshotEmbeddingStore.decodeBatch(java.util.Arrays.copyOf(valid, valid.length + 1))).isInstanceOf(java.io.IOException.class);
        String key = "workspace/workspace-1/source/source-1/snapshot/snapshot-1/embeddings-" + "a".repeat(64) + "/manifest.json";
        objects.put(key, new byte[4 * 1024 * 1024 + 1]);
        assertThatThrownBy(() -> store.loadManifest(key, "workspace-1", "source-1", "snapshot-1")).hasMessageContaining("MANIFEST_INVALID");
    }

    @Test
    void invalidFloatOrDimensionsAreRejectedBeforeVectorAllocation() throws Exception {
        byte[] valid = SnapshotEmbeddingStore.encodeBatch(embeddings(true));
        var bytes = new java.io.ByteArrayOutputStream();
        try (var out = new java.io.DataOutputStream(bytes)) {
            out.writeInt(0x4E574232); out.writeInt(0); out.writeUTF("model:3"); out.writeUTF("chunk-v1");
            out.writeUTF("context-v1"); out.writeUTF("model"); out.writeInt(Integer.MAX_VALUE); out.writeInt(1); out.writeInt(0);
        }
        assertThatThrownBy(() -> SnapshotEmbeddingStore.decodeBatch(bytes.toByteArray())).isInstanceOf(java.io.IOException.class);
        valid[valid.length - 4] = 0x7f; valid[valid.length - 3] = (byte) 0x80;
        valid[valid.length - 2] = 0; valid[valid.length - 1] = 0;
        assertThatThrownBy(() -> SnapshotEmbeddingStore.decodeBatch(valid)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void manifestCannotReferenceOutsideNamespaceOrSkipBatchOrder() {
        var saved = store.saveBatch(row("lease", null, null), embeddings(true));
        assertThatThrownBy(() -> store.saveManifest(List.of(row("lease", "foreign/batch-0.bin", saved.sha256()))))
                .hasMessageContaining("MANIFEST_INVALID");
    }

    private BatchEmbeddings embeddings(boolean note) {
        return new BatchEmbeddings(0, "model:3", "chunk-v1", "context-v1", "model", 3, List.of("chunk-1"),
                List.of("sha256:" + "a".repeat(64)), List.of("sha256:" + "b".repeat(64)), List.of(List.of(0.1f, 0.2f, 0.3f)),
                List.of("真实上下文"), List.of("MODEL"), 1, note ? "sha256:" + "c".repeat(64) : "", note ? List.of(1f, 2f, 3f) : List.of());
    }
    private SourceEmbeddingBatchRepository.Batch row(String lease, String key, String sha) {
        return new SourceEmbeddingBatchRepository.Batch("batch-id", "workspace-1", "source-1", "snapshot-1", "task-1",
                "a".repeat(64), "model:3", "chunk-v1", "context-v1", 0, 0, 1, 1, 1, "d".repeat(64), "e".repeat(64),
                "COMPLETED", lease, java.time.Instant.now().plusSeconds(7200), 1, 2, key, sha);
    }
}
