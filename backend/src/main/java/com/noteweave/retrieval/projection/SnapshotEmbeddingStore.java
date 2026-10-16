package com.noteweave.retrieval.projection;

import com.noteweave.retrieval.projection.SourceRetrievalProjectionService.SnapshotEmbeddings;
import com.noteweave.retrieval.projection.SourceRetrievalProjectionService.BatchEmbeddings;
import com.noteweave.source.SourcePipelineStages;
import com.noteweave.storage.ObjectStorage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.HexFormat;
import java.security.MessageDigest;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * 向量化阶段与索引阶段之间的交接：把一个快照的向量以紧凑的二进制格式写入派生存储。
 * 格式：魔数、向量版本、模型、维度、片段数，逐个片段写入片段 ID、文本哈希和 float32 向量，
 * 最后是整份资料的文本哈希和向量。1024 维向量每个片段约 4KB。
 */
@Component
public class SnapshotEmbeddingStore {

    private static final int MAGIC = 0x4E574531;
    private static final int BATCH_MAGIC = 0x4E574232;
    private static final int MAX_BATCH_BYTES = 8 * 1024 * 1024;
    private static final int MAX_MANIFEST_BYTES = 4 * 1024 * 1024;
    private static final ObjectMapper MANIFEST_MAPPER = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

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

    /** 每次尝试使用独立对象路径，过期租约的晚到写入不会覆盖新结果。 */
    public StoredBatch saveBatch(SourceEmbeddingBatchRepository.Batch claimed, BatchEmbeddings embeddings) {
        if (claimed.batchNo() != embeddings.batchNo() || claimed.chunkCount() != embeddings.chunkIds().size()
                || !claimed.embeddingVersion().equals(embeddings.embeddingVersion())
                || !claimed.chunkerVersion().equals(embeddings.chunkerVersion())
                || !claimed.contextPromptVersion().equals(embeddings.contextPromptVersion()))
            throw invalid("批次结果与冻结计划不一致", null);
        byte[] content = encodeBatch(embeddings);
        String key = batchPrefix(claimed.workspaceId(), claimed.sourceId(), claimed.snapshotId(), claimed.batchVersion())
                + "batch-" + claimed.batchNo() + "-" + claimed.leaseToken() + ".bin";
        storage.write(SourcePipelineStages.BUCKET_DERIVED, key, content);
        return new StoredBatch(key, checksum(content));
    }

    public String saveManifest(List<SourceEmbeddingBatchRepository.Batch> rows) {
        if (rows == null || rows.isEmpty()) throw invalid("缺少批次", null);
        var first = rows.get(0);
        if (rows.stream().anyMatch(row -> !first.workspaceId().equals(row.workspaceId()) || !first.sourceId().equals(row.sourceId())
                || !first.snapshotId().equals(row.snapshotId()) || !first.batchVersion().equals(row.batchVersion())
                || !first.embeddingVersion().equals(row.embeddingVersion()) || !first.chunkerVersion().equals(row.chunkerVersion())
                || !first.contextPromptVersion().equals(row.contextPromptVersion()) || first.totalChunks() != row.totalChunks()
                || !"COMPLETED".equals(row.status()))) throw invalid("批次清单包含不同冻结计划", null);
        List<BatchEntry> entries = rows.stream().map(row -> new BatchEntry(row.batchNo(), row.chunkOffset(),
                row.chunkCount(), row.objectKey(), row.sha256())).toList();
        Manifest manifest = new Manifest(2, first.workspaceId(), first.sourceId(), first.snapshotId(),
                first.batchVersion(), first.embeddingVersion(), first.chunkerVersion(), first.contextPromptVersion(),
                first.totalChunks(), entries);
        validateManifest(manifest);
        try {
            byte[] content = MANIFEST_MAPPER.writeValueAsBytes(manifest);
            if (content.length > MAX_MANIFEST_BYTES) throw invalid("批次清单过大", null);
            String key = batchPrefix(first.workspaceId(), first.sourceId(), first.snapshotId(), first.batchVersion()) + "manifest.json";
            storage.write(SourcePipelineStages.BUCKET_DERIVED, key, content);
            return key;
        } catch (IOException ex) { throw invalid("批次清单写入失败", ex); }
    }

    /** 新清单损坏必须失败，不把全部向量重新计算作为隐式降级。 */
    public Manifest loadManifest(String key, String workspace, String source, String snapshot) {
        try {
            Manifest manifest = MANIFEST_MAPPER.readValue(readBounded(key, MAX_MANIFEST_BYTES), Manifest.class);
            validateManifest(manifest);
            if (!workspace.equals(manifest.workspaceId()) || !source.equals(manifest.sourceId())
                    || !snapshot.equals(manifest.snapshotId())
                    || !key.equals(batchPrefix(workspace, source, snapshot, manifest.batchVersion()) + "manifest.json"))
                throw invalid("批次清单身份不符", null);
            return manifest;
        } catch (IOException | RuntimeException ex) { throw invalid("批次清单无效", ex); }
    }

    public BatchEmbeddings loadBatch(Manifest manifest, BatchEntry entry) {
        try {
            byte[] bytes = readBounded(entry.objectKey(), MAX_BATCH_BYTES);
            if (!entry.sha256().equals(checksum(bytes))) throw invalid("批次摘要不符", null);
            BatchEmbeddings batch = decodeBatch(bytes);
            if (batch.batchNo() != entry.batchNo() || batch.chunkIds().size() != entry.chunkCount()
                    || !manifest.embeddingVersion().equals(batch.embeddingVersion())
                    || !manifest.chunkerVersion().equals(batch.chunkerVersion())
                    || !manifest.contextPromptVersion().equals(batch.contextPromptVersion())
                    || (entry.batchNo() != manifest.batches().size() - 1 && !batch.noteVector().isEmpty())
                    || (entry.batchNo() == manifest.batches().size() - 1 && batch.noteVector().isEmpty()))
                throw invalid("批次结果与清单不符", null);
            return batch;
        } catch (IOException | RuntimeException ex) { throw invalid("批次派生文件无效", ex); }
    }

    public static boolean isManifest(String key) { return key != null && key.endsWith("/manifest.json"); }

    private byte[] readBounded(String key, int maxBytes) throws IOException {
        if (key == null || key.isBlank() || !storage.exists(SourcePipelineStages.BUCKET_DERIVED, key))
            throw new IOException("派生对象不存在");
        try (var input = storage.openStream(SourcePipelineStages.BUCKET_DERIVED, key)) {
            byte[] bytes = input.readNBytes(maxBytes + 1);
            if (bytes.length > maxBytes) throw new IOException("派生对象超过读取上限");
            return bytes;
        }
    }

    private static void validateManifest(Manifest value) {
        if (value == null || value.schemaVersion() != 2 || !identifier(value.workspaceId()) || !identifier(value.sourceId())
                || !identifier(value.snapshotId()) || value.batchVersion() == null || !value.batchVersion().matches("[a-f0-9]{64}")
                || value.totalChunks() < 1 || value.totalChunks() > 1_000_000 || value.batches() == null
                || value.batches().size() != (value.totalChunks() + 63) / 64
                || !version(value.embeddingVersion()) || !version(value.chunkerVersion()) || !version(value.contextPromptVersion()))
            throw invalid("批次清单头部无效", null);
        String prefix = batchPrefix(value.workspaceId(), value.sourceId(), value.snapshotId(), value.batchVersion());
        for (int index = 0; index < value.batches().size(); index++) {
            BatchEntry entry = value.batches().get(index);
            if (entry == null || entry.batchNo() != index || entry.chunkOffset() != index * 64
                    || entry.chunkCount() != Math.min(64, value.totalChunks() - index * 64)
                    || entry.sha256() == null || !entry.sha256().matches("[a-f0-9]{64}")
                    || entry.objectKey() == null || !entry.objectKey().startsWith(prefix + "batch-" + index + "-")
                    || !entry.objectKey().matches("[A-Za-z0-9/._-]+") || entry.objectKey().contains("..")
                    || !entry.objectKey().endsWith(".bin")) throw invalid("批次清单条目无效", null);
        }
    }

    static byte[] encodeBatch(BatchEmbeddings value) {
        validateBatch(value);
        try (var bytes = new ByteArrayOutputStream(); var output = new DataOutputStream(bytes)) {
            output.writeInt(BATCH_MAGIC); output.writeInt(value.batchNo());
            output.writeUTF(value.embeddingVersion()); output.writeUTF(value.chunkerVersion()); output.writeUTF(value.contextPromptVersion());
            output.writeUTF(value.model()); output.writeInt(value.dimensions()); output.writeInt(value.chunkIds().size());
            output.writeInt(value.modelCalls());
            for (int i = 0; i < value.chunkIds().size(); i++) {
                output.writeUTF(value.chunkIds().get(i)); output.writeUTF(value.qaTextHashes().get(i));
                output.writeUTF(value.qaFullTextHashes().get(i)); output.writeUTF(value.contexts().get(i));
                output.writeUTF(value.contextReasons().get(i)); writeVector(output, value.qaVectors().get(i), value.dimensions());
            }
            output.writeUTF(value.noteTextHash()); output.writeInt(value.noteVector().size());
            if (!value.noteVector().isEmpty()) writeVector(output, value.noteVector(), value.dimensions());
            output.flush();
            if (bytes.size() > MAX_BATCH_BYTES) throw invalid("批次派生文件过大", null);
            return bytes.toByteArray();
        } catch (IOException ex) { throw invalid("批次派生文件编码失败", ex); }
    }

    static BatchEmbeddings decodeBatch(byte[] content) throws IOException {
        if (content.length > MAX_BATCH_BYTES) throw new IOException("批次派生文件过大");
        try (var input = new DataInputStream(new ByteArrayInputStream(content))) {
            if (input.readInt() != BATCH_MAGIC) throw new IOException("批次格式不符");
            int batchNo = input.readInt(); String embedding = input.readUTF(); String chunker = input.readUTF(); String context = input.readUTF();
            String model = input.readUTF(); int dimensions = input.readInt(); int count = input.readInt(); int calls = input.readInt();
            if (dimensions < 1 || dimensions > 8192 || count < 1 || count > 64 || batchNo < 0 || calls < 0 || calls > 2)
                throw new IOException("批次维度或数量无效");
            List<String> ids = new ArrayList<>(), hashes = new ArrayList<>(), fullHashes = new ArrayList<>(), contexts = new ArrayList<>(), reasons = new ArrayList<>();
            List<List<Float>> vectors = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                ids.add(input.readUTF()); hashes.add(input.readUTF()); fullHashes.add(input.readUTF());
                contexts.add(input.readUTF()); reasons.add(input.readUTF()); vectors.add(readVector(input, dimensions));
            }
            String noteHash = input.readUTF(); int noteDimensions = input.readInt();
            if (noteDimensions != 0 && noteDimensions != dimensions) throw new IOException("资料向量维度不符");
            List<Float> note = noteDimensions == 0 ? List.of() : readVector(input, dimensions);
            if (input.read() != -1) throw new IOException("批次文件包含多余内容");
            var value = new BatchEmbeddings(batchNo, embedding, chunker, context, model, dimensions, ids, hashes, fullHashes,
                    vectors, contexts, reasons, calls, noteHash, note);
            validateBatch(value); return value;
        }
    }

    private static void validateBatch(BatchEmbeddings value) {
        if (value == null || value.batchNo() < 0 || value.dimensions() < 1 || value.dimensions() > 8192
                || value.chunkIds().isEmpty() || value.chunkIds().size() > 64 || value.modelCalls() < 0 || value.modelCalls() > 2
                || !version(value.embeddingVersion()) || !version(value.chunkerVersion()) || !version(value.contextPromptVersion())
                || !version(value.model()) || new java.util.HashSet<>(value.chunkIds()).size() != value.chunkIds().size())
            throw invalid("批次头部无效", null);
        int count = value.chunkIds().size();
        if (value.qaTextHashes().size() != count || value.qaFullTextHashes().size() != count || value.qaVectors().size() != count
                || value.contexts().size() != count || value.contextReasons().size() != count) throw invalid("批次字段数量不符", null);
        for (int i = 0; i < count; i++) {
            if (!identifier(value.chunkIds().get(i)) || !textHash(value.qaTextHashes().get(i)) || !textHash(value.qaFullTextHashes().get(i))
                    || value.contexts().get(i).length() > 4096 || value.contextReasons().get(i).length() > 300
                    || value.qaVectors().get(i).size() != value.dimensions()
                    || value.qaVectors().get(i).stream().anyMatch(f -> f == null || !Float.isFinite(f)))
                throw invalid("批次片段或向量无效", null);
        }
        if (value.noteVector().isEmpty() ? !value.noteTextHash().isEmpty()
                : (!textHash(value.noteTextHash()) || value.noteVector().size() != value.dimensions()
                    || value.noteVector().stream().anyMatch(f -> f == null || !Float.isFinite(f))))
            throw invalid("批次资料向量无效", null);
    }

    private static boolean identifier(String value) { return value != null && value.matches("[A-Za-z0-9_-]{1,128}"); }
    private static boolean version(String value) { return value != null && !value.isBlank() && value.length() <= 300; }
    private static boolean textHash(String value) { return value != null && value.matches("sha256:[a-f0-9]{64}"); }
    private static String batchPrefix(String workspace, String source, String snapshot, String version) {
        return "workspace/%s/source/%s/snapshot/%s/embeddings-%s/".formatted(workspace, source, snapshot, version);
    }
    private static String checksum(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    private static IllegalStateException invalid(String message, Throwable cause) {
        return new IllegalStateException("SOURCE_EMBEDDING_MANIFEST_INVALID: " + message, cause);
    }
    public record StoredBatch(String objectKey, String sha256) { }
    public record Manifest(int schemaVersion, String workspaceId, String sourceId, String snapshotId, String batchVersion,
            String embeddingVersion, String chunkerVersion, String contextPromptVersion, int totalChunks, List<BatchEntry> batches) { }
    public record BatchEntry(int batchNo, int chunkOffset, int chunkCount, String objectKey, String sha256) { }

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
