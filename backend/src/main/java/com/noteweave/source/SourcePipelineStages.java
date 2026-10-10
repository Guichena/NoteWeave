package com.noteweave.source;

/**
 * 资料处理的四个异步阶段及其 Kafka 主题。
 * <p>
 * 解析：读取原件并提取文本，写入派生存储；切片：按文本生成片段和阅读窗口；
 * 向量化：为片段和整份资料计算向量，写入派生存储；索引：把片段和资料写入检索索引并完成收尾。
 * 每个阶段只在快照处于对应阶段时执行，重复投递的消息直接确认，各阶段失败后可单独重试。
 */
public final class SourcePipelineStages {

    public static final String TOPIC_PARSE = "noteweave.source.parse";
    public static final String TOPIC_CHUNK = "noteweave.source.chunk";
    public static final String TOPIC_EMBED = "noteweave.source.embed";
    public static final String TOPIC_INDEX = "noteweave.source.index";

    public static final String STAGE_EXTRACTING = "EXTRACTING";
    /** 音视频资料在解析阶段交给 Worker 转写，等待回调文字稿。 */
    public static final String STAGE_TRANSCRIBING = "TRANSCRIBING";
    public static final String STAGE_CHUNKING = "CHUNKING";
    public static final String STAGE_EMBEDDING = "EMBEDDING";
    public static final String STAGE_INDEXING = "INDEXING";
    public static final String STAGE_READY = "READY";

    public static final String BUCKET_DERIVED = "noteweave-derived";

    private SourcePipelineStages() {
    }

    /** 解析阶段提取出的文本在派生存储中的位置。 */
    public static String extractedTextKey(String workspaceId, String sourceId, String snapshotId) {
        return "workspace/%s/source/%s/snapshot/%s/extracted-text.txt".formatted(workspaceId, sourceId, snapshotId);
    }

    /** 向量化阶段的结果在派生存储中的位置，按向量版本区分。 */
    public static String embeddingsKey(String workspaceId, String sourceId, String snapshotId, String embeddingVersion) {
        String version = embeddingVersion.replaceAll("[^A-Za-z0-9._-]", "_");
        return "workspace/%s/source/%s/snapshot/%s/embeddings-%s.bin".formatted(workspaceId, sourceId, snapshotId, version);
    }
}
