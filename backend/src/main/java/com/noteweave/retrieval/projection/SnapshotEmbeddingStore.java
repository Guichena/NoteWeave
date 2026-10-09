package com.noteweave.retrieval.projection;

import com.noteweave.retrieval.projection.SourceRetrievalProjectionService.SnapshotEmbeddings;
import com.noteweave.source.SourcePipelineStages;
import com.noteweave.storage.ObjectStorage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 向量化阶段与索引阶段之间的交接：把一个快照的向量以紧凑的二进制格式写入派生存储。
 * 格式：魔数、向量版本、模型、维度、片段数，逐个片段写入片段 ID、文本哈希和 float32 向量，
 * 最后是整份资料的文本哈希和向量。1024 维向量每个片段约 4KB。
 */
@Component
public class SnapshotEmbeddingStore {

    private static final int MAGIC = 0x4E574531;

    private final ObjectStorage storage;

    public SnapshotEmbeddingStore(ObjectStorage storage) {
        this.storage = storage;
    }

    public String save(String workspaceId, String sourceId, String snapshotId, SnapshotEmbeddings embeddings) {
        String key = SourcePipelineStages.embeddingsKey(workspaceId, sourceId, snapshotId, embeddings.embeddingVersion());
        storage.write(SourcePipelineStages.BUCKET_DERIVED, key, encode(embeddings));
        return key;
    }

    /** 读取向量；对象不存在或格式不对时返回 null，由调用方重新计算。 */
    public SnapshotEmbeddings load(String objectKey) {
        if (objectKey == null || objectKey.isBlank()
                || !storage.exists(SourcePipelineStages.BUCKET_DERIVED, objectKey)) {
            return null;
        }
        try {
            return decode(storage.read(SourcePipelineStages.BUCKET_DERIVED, objectKey));
        } catch (IOException | RuntimeException ex) {
            return null;
        }
    }

    static byte[] encode(SnapshotEmbeddings embeddings) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(
                64 + embeddings.chunkIds().size() * (embeddings.dimensions() * 4 + 160));
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(MAGIC);
            output.writeUTF(embeddings.embeddingVersion());
            output.writeUTF(embeddings.model());
            output.writeInt(embeddings.dimensions());
            output.writeInt(embeddings.chunkIds().size());
            for (int index = 0; index < embeddings.chunkIds().size(); index++) {
                output.writeUTF(embeddings.chunkIds().get(index));
                output.writeUTF(embeddings.qaTextHashes().get(index));
                writeVector(output, embeddings.qaVectors().get(index), embeddings.dimensions());
            }
            output.writeUTF(embeddings.noteTextHash());
            writeVector(output, embeddings.noteVector(), embeddings.dimensions());
        } catch (IOException ex) {
            throw new IllegalStateException("encode snapshot embeddings failed", ex);
        }
        return bytes.toByteArray();
    }

    static SnapshotEmbeddings decode(byte[] content) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(content))) {
            if (input.readInt() != MAGIC) {
                throw new IOException("unexpected snapshot embedding format");
            }
            String version = input.readUTF();
            String model = input.readUTF();
            int dimensions = input.readInt();
            int count = input.readInt();
            List<String> chunkIds = new ArrayList<>(count);
            List<String> hashes = new ArrayList<>(count);
            List<List<Float>> vectors = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                chunkIds.add(input.readUTF());
                hashes.add(input.readUTF());
                vectors.add(readVector(input, dimensions));
            }
            String noteHash = input.readUTF();
            List<Float> noteVector = readVector(input, dimensions);
            return new SnapshotEmbeddings(version, model, dimensions, chunkIds, hashes, vectors, noteHash, noteVector);
        }
    }

    private static void writeVector(DataOutputStream output, List<Float> vector, int dimensions) throws IOException {
        if (vector.size() != dimensions) {
            throw new IOException("vector dimension mismatch");
        }
        for (Float value : vector) {
            output.writeFloat(value);
        }
    }

    private static List<Float> readVector(DataInputStream input, int dimensions) throws IOException {
        List<Float> vector = new ArrayList<>(dimensions);
        for (int index = 0; index < dimensions; index++) {
            vector.add(input.readFloat());
        }
        return vector;
    }
}
