package com.noteweave.retrieval.projection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.config.NoteWeaveProperties;
import com.noteweave.common.BusinessException;
import com.noteweave.retrieval.index.RetrievalIndexManager;
import com.noteweave.retrieval.index.RetrievalIndexNames;
import com.noteweave.retrieval.index.RetrievalProjectionWriter;
import com.noteweave.retrieval.index.RetrievalProjectionWriter.NoteSourceDocument;
import com.noteweave.retrieval.index.RetrievalProjectionWriter.QaChunkDocument;
import com.noteweave.retrieval.projection.RetrievalProjectionRepository.CreateProjection;
import com.noteweave.retrieval.projection.RetrievalProjectionRepository.Projection;
import com.noteweave.retrieval.projection.RetrievalProjectionRepository.ProjectionStatus;
import com.noteweave.retrieval.projection.RetrievalProjectionRepository.ProjectionType;
import com.noteweave.retrieval.provider.EmbeddingClient;
import com.noteweave.retrieval.provider.RetrievalProviderException;
import com.noteweave.source.SourceTagCodec;
import com.noteweave.source.SourceChunkContextEnhancer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class SourceRetrievalProjectionService {
    public static final String QA_SCHEMA_VERSION = "qa-chunk-index-v3";
    public static final String NOTE_SCHEMA_VERSION = "note-source-index-v2";
    private static final String EMBEDDING_PROVIDER = "openai-compatible";
    public static final int EMBEDDING_BATCH_SIZE = 64;

    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingClient embeddingClient;
    private final RetrievalProjectionRepository projectionRepository;
    private final RetrievalIndexManager indexManager;
    private final RetrievalProjectionWriter projectionWriter;
    private final NoteWeaveProperties properties;
    private final SourceTagCodec sourceTagCodec;
    private final SourceEmbeddingCacheRepository embeddingCache;
    private final SourceChunkContextEnhancer contextEnhancer;

    public SourceRetrievalProjectionService(
            JdbcTemplate jdbcTemplate,
            EmbeddingClient embeddingClient,
            RetrievalProjectionRepository projectionRepository,
            RetrievalIndexManager indexManager,
            RetrievalProjectionWriter projectionWriter,
            NoteWeaveProperties properties,
            SourceTagCodec sourceTagCodec
    ) {
        this(jdbcTemplate, embeddingClient, projectionRepository, indexManager, projectionWriter,
                properties, sourceTagCodec, null, null);
    }

    @Autowired
    public SourceRetrievalProjectionService(
            JdbcTemplate jdbcTemplate, EmbeddingClient embeddingClient,
            RetrievalProjectionRepository projectionRepository, RetrievalIndexManager indexManager,
            RetrievalProjectionWriter projectionWriter, NoteWeaveProperties properties,
            SourceTagCodec sourceTagCodec, SourceEmbeddingCacheRepository embeddingCache,
            SourceChunkContextEnhancer contextEnhancer
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.embeddingClient = embeddingClient;
        this.projectionRepository = projectionRepository;
        this.indexManager = indexManager;
        this.projectionWriter = projectionWriter;
        this.properties = properties;
        this.sourceTagCodec = sourceTagCodec;
        this.embeddingCache = embeddingCache;
        this.contextEnhancer = contextEnhancer;
    }

    /** 新批次冻结实际输入格式、截断配置和提供方地址，令牌不进入版本或派生存储。 */
    public String currentBatchEmbeddingVersion() {
        NoteWeaveProperties.Embedding config = properties.embedding();
        NoteWeaveProperties.Document document = properties.document();
        String model = text(config.model()).trim();
        String fingerprint = sha256(String.join("\n", model, Integer.toString(config.dimensions()),
                text(config.endpoint()).trim(), "input-v1",
                Integer.toString(Math.max(1, config.maxInputCharacters())), "document-chunker-v3",
                Integer.toString(Math.max(64, document.chunkMaxTokens())),
                Integer.toString(Math.max(0, Math.min(document.chunkOverlapTokens(), Math.max(64, document.chunkMaxTokens()) / 4))),
                currentContextPromptVersion())).substring(7);
        String version = "embedding-batch-v5:" + config.dimensions() + ":" + fingerprint;
        if (model.isBlank() || config.dimensions() <= 0) {
            throw new IllegalStateException("向量版本配置无效");
        }
        return version;
    }

    public String currentContextPromptVersion() {
        return contextEnhancer == null ? "context-legacy-disabled-v1" : contextEnhancer.currentContextPromptVersion();
    }

    /** 重建审计采用本实例实际使用的路径版本，兼容旧构造器的同步投影。 */
    public String effectiveProjectionEmbeddingVersion() {
        return contextEnhancer == null ? currentEmbeddingVersion() : currentBatchEmbeddingVersion();
    }

    /** 使用切片时保存的真实版本；旧资料缺少版本时明确隔离，不能猜测历史配置。 */
    public String currentChunkerVersion(String workspaceId, String sourceId, String snapshotId) {
        SourceRow source = loadSource(workspaceId, sourceId, snapshotId);
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode metadata = mapper.readTree(source.metadataJson());
            if (metadata != null && metadata.isTextual()) {
                metadata = mapper.readTree(metadata.textValue());
            }
            JsonNode stored = metadata == null ? null : metadata.get("chunker_version");
            if (stored != null && stored.isTextual()) {
                String version = stored.textValue();
                if (version.matches("document-chunker-v3:[0-9]{1,10}:[0-9]{1,10}")) {
                    String[] components = version.split(":");
                    int maxTokens = Integer.parseInt(components[1]);
                    int overlap = Integer.parseInt(components[2]);
                    if (maxTokens >= 64 && overlap >= 0 && overlap <= maxTokens / 4) {
                        return version;
                    }
                }
            }
        } catch (Exception invalidHistoricalMetadata) {
            // 没有可信的历史切片版本时不沿用当前运行配置。
        }
        return "document-chunker-legacy-unknown";
    }

    /** SDK 内部请求和重试不可观测，这里只记录有明确上界的请求预算。 */
    public int currentEmbeddingRequestBudget(int chunkCount, boolean includeNote) {
        if (chunkCount < 0 || chunkCount > EMBEDDING_BATCH_SIZE) {
            throw new IllegalArgumentException("片段批次最多为 64 条");
        }
        int size = Math.max(1, Math.min(EMBEDDING_BATCH_SIZE, properties.embedding().documentBatchSize()));
        int requests = (chunkCount + size - 1) / size + (includeNote ? 1 : 0);
        return Math.multiplyExact(requests, Math.max(1, properties.embedding().maxAttempts()));
    }

    /** 只加载本批片段，完成上下文检查点后再计算向量；回调仅记录真实上下文模型调用。 */
    public BatchEmbeddings computeEmbeddingBatch(String workspaceId, String sourceId, String snapshotId,
                                                   int batchNo, IntConsumer beforeModelCall) {
        if (!embeddingClient.isEnabled()) {
            throw new RetrievalProviderException("EMBEDDING_PROVIDER_DISABLED", "资料向量服务未启用");
        }
        if (batchNo < 0 || beforeModelCall == null) {
            throw new IllegalArgumentException("批次序号或调用预算回调无效");
        }
        SourceRow source = loadSource(workspaceId, sourceId, snapshotId);
        int total = chunkCount(workspaceId, sourceId, snapshotId);
        List<ChunkRow> chunks = loadChunkBatch(workspaceId, sourceId, snapshotId, batchNo);
        if (chunks.isEmpty()) {
            throw new IllegalStateException("快照中不存在该片段批次");
        }
        String version = currentBatchEmbeddingVersion();
        String chunkerVersion = currentChunkerVersion(workspaceId, sourceId, snapshotId);
        String contextVersion = currentContextPromptVersion();
        List<String> contexts;
        List<String> reasons;
        int calls;
        if (contextEnhancer == null) {
            contexts = chunks.stream().map(ignored -> "").toList();
            reasons = chunks.stream().map(ignored -> "CONTEXT_LEGACY_CONSTRUCTOR_DISABLED").toList();
            calls = 0;
        } else {
            SourceChunkContextEnhancer.EnhancementResult result = contextEnhancer.enhance(
                    workspaceId, sourceId, snapshotId, source.title(), source.summary(),
                    chunks.stream().map(chunk -> new SourceChunkContextEnhancer.ChunkInput(
                            chunk.chunkId(), chunk.heading(), chunk.content())).toList(), beforeModelCall);
            if (!result.chunkIds().equals(chunks.stream().map(ChunkRow::chunkId).toList())
                    || !contextVersion.equals(result.contextPromptVersion())
                    || result.contexts().size() != chunks.size() || result.reasons().size() != chunks.size()) {
                throw new IllegalStateException("上下文增强结果与本批片段不一致");
            }
            contexts = result.contexts();
            reasons = result.reasons();
            calls = result.modelCalls();
        }
        int dimensions = properties.embedding().dimensions();
        String model = properties.embedding().model().trim();
        List<String> fullHashes = new ArrayList<>(chunks.size());
        List<String> hashes = new ArrayList<>(chunks.size());
        List<List<Float>> vectors = new ArrayList<>(chunks.size());
        Map<EmbeddingInput, List<Integer>> misses = new LinkedHashMap<>();
        for (int i = 0; i < chunks.size(); i++) {
            ChunkRow chunk = chunks.get(i);
            String fullInput = contextualEmbeddingText(source, chunk, contexts.get(i));
            String effectiveInput = limitEmbeddingInput(fullInput);
            String fullHash = sha256(fullInput);
            String hash = sha256(effectiveInput);
            SourceEmbeddingCacheRepository.CacheKey key = new SourceEmbeddingCacheRepository.CacheKey(
                    workspaceId, version, chunkerVersion, contextVersion, sha256(chunk.content()));
            List<Float> cached = embeddingCache == null ? null
                    : embeddingCache.find(key, fullHash, hash, model, dimensions).orElse(null);
            vectors.add(cached);
            fullHashes.add(fullHash);
            hashes.add(hash);
            if (cached == null) {
                misses.computeIfAbsent(new EmbeddingInput(key, fullHash, hash, effectiveInput),
                        ignored -> new ArrayList<>()).add(i);
            }
        }
        if (!misses.isEmpty()) {
            List<EmbeddingInput> inputs = List.copyOf(misses.keySet());
            EmbeddingClient.EmbeddingResult result = embeddingClient.embedDocuments(
                    inputs.stream().map(EmbeddingInput::text).toList());
            verifyBatchEmbeddingContract(result, inputs.size(), dimensions, model);
            for (int i = 0; i < inputs.size(); i++) {
                EmbeddingInput input = inputs.get(i);
                List<Float> vector = result.vectors().get(i);
                for (int index : misses.get(input)) {
                    vectors.set(index, vector);
                }
                if (embeddingCache != null) {
                    embeddingCache.put(input.key(), input.fullHash(), input.effectiveHash(), model, dimensions, vector);
                }
            }
        }
        String noteHash = "";
        List<Float> noteVector = List.of();
        boolean lastBatch = (long) (batchNo + 1) * EMBEDDING_BATCH_SIZE >= total;
        if (lastBatch) {
            String noteText = limitEmbeddingInput(noteEmbeddingText(source, loadHeadings(workspaceId, sourceId, snapshotId)));
            EmbeddingClient.EmbeddingResult note = embeddingClient.embedDocuments(List.of(noteText));
            verifyBatchEmbeddingContract(note, 1, dimensions, model);
            noteHash = sha256(noteText);
            noteVector = note.singleVector();
        }
        return new BatchEmbeddings(batchNo, version, chunkerVersion, contextVersion, model, dimensions,
                chunks.stream().map(ChunkRow::chunkId).toList(), hashes, fullHashes, vectors, contexts, reasons,
                calls, noteHash, noteVector);
    }

    private void verifyBatchEmbeddingContract(EmbeddingClient.EmbeddingResult result, int count,
                                               int dimensions, String model) {
        verifyEmbeddingContract(result, count, dimensions);
        if (!model.equals(result.model()) || result.vectors().stream().flatMap(List::stream)
                .anyMatch(value -> value == null || !Float.isFinite(value))) {
            throw new RetrievalProviderException("EMBEDDING_PROVIDER_RESPONSE_INVALID", "向量模型或有限数值契约不一致");
        }
    }

    private String limitEmbeddingInput(String value) {
        String input = text(value).trim();
        // 预先截断后客户端仍会 trim，因此缓存摘要必须与最终发送字符串一致。
        return input.substring(0, Math.min(input.length(), Math.max(1, properties.embedding().maxInputCharacters()))).trim();
    }

    private String contextualEmbeddingText(SourceRow source, ChunkRow chunk, String context) {
        String prefix = text(source.title());
        if (!text(chunk.heading()).isBlank()) {
            prefix = prefix.isBlank() ? chunk.heading() : prefix + " > " + chunk.heading();
        }
        return (prefix + "\n" + (text(context).isBlank() ? "" : context + "\n") + text(chunk.content())).trim();
    }

    private record EmbeddingInput(SourceEmbeddingCacheRepository.CacheKey key,
                                   String fullHash, String effectiveHash, String text) { }

    private int chunkCount(String workspaceId, String sourceId, String snapshotId) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*) from source_chunk where workspace_id = ? and source_id = ? and source_snapshot_id = ?
                """, Integer.class, workspaceId, sourceId, snapshotId);
        return count == null ? 0 : count;
    }

    private List<ChunkRow> loadChunkBatch(String workspaceId, String sourceId, String snapshotId, int batchNo) {
        return jdbcTemplate.query("""
                select id, chunk_no, coalesce(heading, '') as heading, content from source_chunk
                where workspace_id = ? and source_id = ? and source_snapshot_id = ?
                order by chunk_no, id limit ? offset ?
                """, (rs, index) -> new ChunkRow(rs.getString("id"), rs.getInt("chunk_no"),
                        rs.getString("heading"), rs.getString("content")), workspaceId, sourceId, snapshotId,
                EMBEDDING_BATCH_SIZE, Math.multiplyExact(batchNo, EMBEDDING_BATCH_SIZE));
    }

    /** 资料向量只需标题与章节路径，独立读取轻量字段，避免在末批读回全部正文。 */
    private List<ChunkRow> loadHeadings(String workspaceId, String sourceId, String snapshotId) {
        return jdbcTemplate.query("""
                select coalesce(heading, '') as heading, min(chunk_no) as first_no from source_chunk
                where workspace_id = ? and source_id = ? and source_snapshot_id = ?
                group by heading order by first_no, heading
                """, (rs, index) -> new ChunkRow("", rs.getInt("first_no"), rs.getString("heading"), ""),
                workspaceId, sourceId, snapshotId);
    }

    /** 批次惰性读取并写入索引，完整核对所有批次后才发布当前快照。 */
    public ProjectionResult projectSnapshotBatches(String workspaceId, String sourceId, String snapshotId,
                                                     Iterable<BatchEmbeddings> batches) {
        String version = currentBatchEmbeddingVersion();
        String qaIndex = indexManager.resolveWriteIndex(RetrievalIndexNames.alias(ProjectionType.QA_CHUNK, workspaceId),
                RetrievalIndexNames.physical(ProjectionType.QA_CHUNK, QA_SCHEMA_VERSION, version, workspaceId));
        String noteIndex = indexManager.resolveWriteIndex(RetrievalIndexNames.alias(ProjectionType.NOTE_SOURCE, workspaceId),
                RetrievalIndexNames.physical(ProjectionType.NOTE_SOURCE, NOTE_SCHEMA_VERSION, version, workspaceId));
        return projectSnapshotBatches(workspaceId, sourceId, snapshotId, batches, qaIndex, noteIndex);
    }

    /** 重建目标由调用方指定，仍沿用同样的批次资格与完整性检查。 */
    public ProjectionResult projectSnapshotBatches(String workspaceId, String sourceId, String snapshotId,
                                                     Iterable<BatchEmbeddings> batches, String qaIndex, String noteIndex) {
        int dimensions = properties.embedding().dimensions();
        String version = currentBatchEmbeddingVersion();
        try {
            SourceRow source = loadSource(workspaceId, sourceId, snapshotId);
            int total = chunkCount(workspaceId, sourceId, snapshotId);
            if (total == 0) {
                throw new IllegalStateException("快照没有可索引的片段");
            }
            String chunkerVersion = currentChunkerVersion(workspaceId, sourceId, snapshotId);
            String contextVersion = currentContextPromptVersion();
            indexManager.createIndex(ProjectionType.QA_CHUNK, qaIndex, dimensions);
            indexManager.createIndex(ProjectionType.NOTE_SOURCE, noteIndex, dimensions);
            int expectedBatch = 0;
            int ready = 0;
            BatchEmbeddings last = null;
            for (BatchEmbeddings batch : batches) {
                if (batch.batchNo() != expectedBatch || !batch.embeddingVersion().equals(version)
                        || !batch.chunkerVersion().equals(chunkerVersion)
                        || !batch.contextPromptVersion().equals(contextVersion)
                        || !batch.model().equals(properties.embedding().model().trim()) || batch.dimensions() != dimensions) {
                    throw new IllegalStateException("向量批次的顺序或冻结版本不一致");
                }
                List<ChunkRow> chunks = loadChunkBatch(workspaceId, sourceId, snapshotId, expectedBatch);
                if (chunks.isEmpty() || !batch.chunkIds().equals(chunks.stream().map(ChunkRow::chunkId).toList())) {
                    throw new IllegalStateException("向量批次与当前片段列表不一致");
                }
                boolean lastBatch = (long) (expectedBatch + 1) * EMBEDDING_BATCH_SIZE >= total;
                if (lastBatch != !batch.noteVector().isEmpty()) {
                    throw new IllegalStateException("只有末批可以携带完整资料向量");
                }
                for (int index = 0; index < chunks.size(); index++) {
                    ChunkRow chunk = chunks.get(index);
                    String fullInput = contextualEmbeddingText(source, chunk, batch.contexts().get(index));
                    if (!sha256(fullInput).equals(batch.qaFullTextHashes().get(index))
                            || !sha256(limitEmbeddingInput(fullInput)).equals(batch.qaTextHashes().get(index))) {
                        throw new IllegalStateException("向量批次输入摘要与原文或上下文不一致");
                    }
                    ready += writeQaBatchProjection(workspaceId, sourceId, snapshotId, qaIndex, source, chunk,
                            batch, index);
                }
                last = batch;
                expectedBatch++;
            }
            if (ready != total || last == null || last.noteVector().isEmpty()
                    || expectedBatch != (total + EMBEDDING_BATCH_SIZE - 1) / EMBEDDING_BATCH_SIZE) {
                throw new IllegalStateException("向量批次不完整，不能发布快照");
            }
            List<ChunkRow> headingRows = loadHeadings(workspaceId, sourceId, snapshotId);
            String noteText = limitEmbeddingInput(noteEmbeddingText(source, headingRows));
            if (!last.noteTextHash().equals(sha256(noteText))) {
                throw new IllegalStateException("资料向量输入摘要与当前资料信息不一致");
            }
            writeNoteBatchProjection(workspaceId, sourceId, snapshotId, noteIndex, source, headingRows, total, last);
            projectionWriter.markSnapshotCurrent(qaIndex, snapshotId);
            projectionWriter.markSnapshotCurrent(noteIndex, snapshotId);
            return new ProjectionResult(ready, total, true, qaIndex, noteIndex, version);
        } catch (RuntimeException ex) {
            deactivatePartialSnapshot(qaIndex, noteIndex, snapshotId, ex);
            throw ex;
        }
    }

    private int writeQaBatchProjection(String workspaceId, String sourceId, String snapshotId, String qaIndex,
                                        SourceRow source, ChunkRow chunk, BatchEmbeddings batch, int index) {
        Projection projection = projectionRepository.createPending(new CreateProjection(workspaceId,
                ProjectionType.QA_CHUNK, chunk.chunkId(), sourceId, snapshotId, sha256(chunk.content()),
                batch.qaTextHashes().get(index), EMBEDDING_PROVIDER, batch.model(), batch.dimensions(),
                batch.embeddingVersion(), QA_SCHEMA_VERSION, qaIndex));
        projection = projectionRepository.prepareForProjection(projection);
        if (projection.status() == ProjectionStatus.READY) {
            return 1;
        }
        try {
            projectionRepository.markEmbedding(projection.id());
            projectionRepository.markIndexing(projection.id());
            projectionWriter.writeQaChunk(qaIndex, new QaChunkDocument(workspaceId, sourceId, snapshotId,
                    chunk.chunkId(), chunk.chunkNo(), chunk.heading(), source.title(), source.sourceType(),
                    chunk.content(), batch.qaTextHashes().get(index), batch.model(), batch.dimensions(),
                    batch.embeddingVersion(), QA_SCHEMA_VERSION, false, batch.qaVectors().get(index),
                    batch.contexts().get(index)));
            projectionRepository.markReady(projection.id());
            return 1;
        } catch (RuntimeException ex) {
            markFailed(projection, ex);
            throw ex;
        }
    }

    private void writeNoteBatchProjection(String workspaceId, String sourceId, String snapshotId, String noteIndex,
                                          SourceRow source, List<ChunkRow> headings, int count, BatchEmbeddings batch) {
        Projection projection = projectionRepository.createPending(new CreateProjection(workspaceId,
                ProjectionType.NOTE_SOURCE, sourceId, sourceId, snapshotId, source.sha256(), batch.noteTextHash(),
                EMBEDDING_PROVIDER, batch.model(), batch.dimensions(), batch.embeddingVersion(), NOTE_SCHEMA_VERSION, noteIndex));
        projection = projectionRepository.prepareForProjection(projection);
        if (projection.status() == ProjectionStatus.READY) {
            return;
        }
        try {
            projectionRepository.markEmbedding(projection.id());
            projectionRepository.markIndexing(projection.id());
            projectionWriter.writeNoteSource(noteIndex, new NoteSourceDocument(workspaceId, sourceId, snapshotId,
                    source.title(), source.sourceType(), source.summary(), tags(source.tagsJson()), source.metadataJson(),
                    headings(headings), List.of(), count, windowCount(snapshotId), batch.noteTextHash(), batch.model(),
                    batch.dimensions(), batch.embeddingVersion(), NOTE_SCHEMA_VERSION, false, batch.noteVector()));
            projectionRepository.markReady(projection.id());
        } catch (RuntimeException ex) {
            markFailed(projection, ex);
            throw ex;
        }
    }

    public ProjectionResult projectSnapshot(String workspaceId, String sourceId, String snapshotId) {
        return projectSnapshot(workspaceId, sourceId, snapshotId, null);
    }

    /**
     * 写入检索索引。embeddings 为空时当场计算向量（重建索引、同步模式）；
     * 四阶段流水线的索引阶段传入向量化阶段已经算好的结果。
     */
    public ProjectionResult projectSnapshot(String workspaceId, String sourceId, String snapshotId,
                                            SnapshotEmbeddings embeddings) {
        if (embeddings == null && contextEnhancer != null) {
            return projectSnapshotBatches(workspaceId, sourceId, snapshotId,
                    computingBatches(workspaceId, sourceId, snapshotId));
        }
        int dimensions = properties.embedding().dimensions();
        String model = properties.embedding().model();
        String embeddingVersion = model + ":" + dimensions;
        String qaIndex = RetrievalIndexNames.physical(
                ProjectionType.QA_CHUNK, QA_SCHEMA_VERSION, embeddingVersion, workspaceId);
        String noteIndex = RetrievalIndexNames.physical(
                ProjectionType.NOTE_SOURCE, NOTE_SCHEMA_VERSION, embeddingVersion, workspaceId);
        qaIndex = indexManager.resolveWriteIndex(
                RetrievalIndexNames.alias(ProjectionType.QA_CHUNK, workspaceId), qaIndex);
        noteIndex = indexManager.resolveWriteIndex(
                RetrievalIndexNames.alias(ProjectionType.NOTE_SOURCE, workspaceId), noteIndex);
        try {
            return embeddings == null
                    ? projectSnapshotToIndexes(workspaceId, sourceId, snapshotId, qaIndex, noteIndex)
                    : writeIndexes(workspaceId, sourceId, snapshotId, qaIndex, noteIndex, embeddings);
        } catch (RuntimeException ex) {
            deactivatePartialSnapshot(qaIndex, noteIndex, snapshotId, ex);
            throw ex;
        }
    }

    private void deactivatePartialSnapshot(
            String qaIndex,
            String noteIndex,
            String snapshotId,
            RuntimeException original
    ) {
        for (String index : List.of(qaIndex, noteIndex)) {
            try {
                projectionWriter.markSnapshotNotCurrent(index, snapshotId);
            } catch (RuntimeException cleanupFailure) {
                original.addSuppressed(cleanupFailure);
            }
        }
    }

    public ProjectionResult projectSnapshotToIndexes(
            String workspaceId,
            String sourceId,
            String snapshotId,
            String qaIndex,
            String noteIndex
    ) {
        if (contextEnhancer != null) {
            return projectSnapshotBatches(workspaceId, sourceId, snapshotId,
                    computingBatches(workspaceId, sourceId, snapshotId), qaIndex, noteIndex);
        }
        return writeIndexes(workspaceId, sourceId, snapshotId, qaIndex, noteIndex,
                computeEmbeddings(workspaceId, sourceId, snapshotId));
    }

    private Iterable<BatchEmbeddings> computingBatches(String workspaceId, String sourceId, String snapshotId) {
        int total = chunkCount(workspaceId, sourceId, snapshotId);
        int count = total == 0 ? 0 : (total - 1) / EMBEDDING_BATCH_SIZE + 1;
        return () -> new Iterator<>() {
            private int nextBatch;

            @Override
            public boolean hasNext() {
                return nextBatch < count;
            }

            @Override
            public BatchEmbeddings next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                // 同步与重建没有 Kafka 批次租约，各批仍有独立的两次调用上限。
                // 已持久化的上下文由增强器直接恢复，不因重建重复调用模型。
                AtomicInteger calls = new AtomicInteger();
                return computeEmbeddingBatch(workspaceId, sourceId, snapshotId, nextBatch++, increment -> {
                    if (increment != 1 || calls.get() >= 2) {
                        throw new BusinessException("SOURCE_CONTEXT_CALL_BUDGET_EXHAUSTED", "本批上下文调用预算已耗尽");
                    }
                    calls.incrementAndGet();
                });
            }
        };
    }

    /** 向量化：为每个片段和整份资料计算向量，不写索引。 */
    public SnapshotEmbeddings computeEmbeddings(String workspaceId, String sourceId, String snapshotId) {
        if (!embeddingClient.isEnabled()) {
            throw new RetrievalProviderException(
                    "EMBEDDING_PROVIDER_DISABLED", "Source retrieval projection requires embedding");
        }
        if (contextEnhancer != null) {
            List<String> ids = new ArrayList<>();
            List<String> hashes = new ArrayList<>();
            List<List<Float>> vectors = new ArrayList<>();
            BatchEmbeddings last = null;
            for (BatchEmbeddings batch : computingBatches(workspaceId, sourceId, snapshotId)) {
                ids.addAll(batch.chunkIds());
                hashes.addAll(batch.qaTextHashes());
                vectors.addAll(batch.qaVectors());
                last = batch;
            }
            if (last == null) {
                throw new IllegalStateException("Source snapshot has no chunks to project");
            }
            // 仅旧公开返回类型要求聚合，实际计算和新索引入口始终按 64 条执行。
            return new SnapshotEmbeddings(currentEmbeddingVersion(), last.model(), last.dimensions(),
                    ids, hashes, vectors, last.noteTextHash(), last.noteVector());
        }
        SourceRow source = loadSource(workspaceId, sourceId, snapshotId);
        List<ChunkRow> chunks = loadChunks(workspaceId, sourceId, snapshotId);
        if (chunks.isEmpty()) {
            throw new IllegalStateException("Source snapshot has no chunks to project");
        }
        int dimensions = properties.embedding().dimensions();
        String model = properties.embedding().model();
        List<String> qaTexts = chunks.stream().map(chunk -> qaEmbeddingText(source, chunk)).toList();
        EmbeddingClient.EmbeddingResult qaEmbeddings = embeddingClient.embedDocuments(qaTexts);
        verifyEmbeddingContract(qaEmbeddings, chunks.size(), dimensions);
        String noteText = noteEmbeddingText(source, chunks);
        EmbeddingClient.EmbeddingResult noteEmbedding = embeddingClient.embedDocuments(List.of(noteText));
        verifyEmbeddingContract(noteEmbedding, 1, dimensions);
        return new SnapshotEmbeddings(model + ":" + dimensions, model, dimensions,
                chunks.stream().map(ChunkRow::chunkId).toList(),
                qaTexts.stream().map(this::sha256).toList(),
                qaEmbeddings.vectors(), sha256(noteText), noteEmbedding.singleVector());
    }

    /** 当前配置下的向量版本，用来判断派生存储里的向量是否还能使用。 */
    public String currentEmbeddingVersion() {
        return properties.embedding().model() + ":" + properties.embedding().dimensions();
    }

    /** 索引：用给定的向量写入 QA 片段索引和 Note 资料索引。 */
    public ProjectionResult writeIndexes(
            String workspaceId,
            String sourceId,
            String snapshotId,
            String qaIndex,
            String noteIndex,
            SnapshotEmbeddings embeddings
    ) {
        SourceRow source = loadSource(workspaceId, sourceId, snapshotId);
        List<ChunkRow> chunks = loadChunks(workspaceId, sourceId, snapshotId);
        if (chunks.isEmpty()) {
            throw new IllegalStateException("Source snapshot has no chunks to project");
        }
        int dimensions = properties.embedding().dimensions();
        String model = properties.embedding().model();
        String embeddingVersion = model + ":" + dimensions;
        if (!embeddings.matches(embeddingVersion, chunks.stream().map(ChunkRow::chunkId).toList())) {
            throw new IllegalStateException("Snapshot embeddings do not match the current chunks or embedding version");
        }
        indexManager.createIndex(ProjectionType.QA_CHUNK, qaIndex, dimensions);
        indexManager.createIndex(ProjectionType.NOTE_SOURCE, noteIndex, dimensions);

        int qaReady = 0;
        for (int index = 0; index < chunks.size(); index++) {
            ChunkRow chunk = chunks.get(index);
            String textHash = embeddings.qaTextHashes().get(index);
            Projection projection = projectionRepository.createPending(new CreateProjection(
                    workspaceId, ProjectionType.QA_CHUNK, chunk.chunkId(), sourceId, snapshotId,
                    sha256(chunk.content()), textHash, EMBEDDING_PROVIDER,
                    model, dimensions, embeddingVersion, QA_SCHEMA_VERSION, qaIndex));
            projection = projectionRepository.prepareForProjection(projection);
            if (projection.status() == ProjectionStatus.READY) {
                qaReady++;
                continue;
            }
            try {
                projectionRepository.markEmbedding(projection.id());
                projectionRepository.markIndexing(projection.id());
                projectionWriter.writeQaChunk(qaIndex, new QaChunkDocument(
                        workspaceId, sourceId, snapshotId, chunk.chunkId(), chunk.chunkNo(),
                        chunk.heading(), source.title(), source.sourceType(), chunk.content(),
                        textHash, model, dimensions, embeddingVersion,
                        QA_SCHEMA_VERSION, false, embeddings.qaVectors().get(index)));
                projectionRepository.markReady(projection.id());
                qaReady++;
            } catch (RuntimeException ex) {
                markFailed(projection, ex);
                throw ex;
            }
        }

        Projection noteProjection = projectionRepository.createPending(new CreateProjection(
                workspaceId, ProjectionType.NOTE_SOURCE, sourceId, sourceId, snapshotId,
                source.sha256(), embeddings.noteTextHash(), EMBEDDING_PROVIDER, model, dimensions,
                embeddingVersion, NOTE_SCHEMA_VERSION, noteIndex));
        noteProjection = projectionRepository.prepareForProjection(noteProjection);
        boolean noteReady = noteProjection.status() == ProjectionStatus.READY;
        if (!noteReady) {
            try {
                projectionRepository.markEmbedding(noteProjection.id());
                projectionRepository.markIndexing(noteProjection.id());
                projectionWriter.writeNoteSource(noteIndex, new NoteSourceDocument(
                        workspaceId, sourceId, snapshotId, source.title(), source.sourceType(),
                        source.summary(), tags(source.tagsJson()), source.metadataJson(),
                        headings(chunks), List.of(), chunks.size(), windowCount(snapshotId),
                        embeddings.noteTextHash(), model, dimensions, embeddingVersion,
                        NOTE_SCHEMA_VERSION, false, embeddings.noteVector()));
                projectionRepository.markReady(noteProjection.id());
                noteReady = true;
            } catch (RuntimeException ex) {
                markFailed(noteProjection, ex);
                throw ex;
            }
        }
        if (qaReady == chunks.size() && noteReady) {
            projectionWriter.markSnapshotCurrent(qaIndex, snapshotId);
            projectionWriter.markSnapshotCurrent(noteIndex, snapshotId);
        }
        return new ProjectionResult(qaReady, chunks.size(), noteReady, qaIndex, noteIndex, embeddingVersion);
    }

    private void verifyEmbeddingContract(
            EmbeddingClient.EmbeddingResult result,
            int expectedCount,
            int dimensions
    ) {
        if (result.vectors().size() != expectedCount
                || result.dimensions() != dimensions
                || result.vectors().stream().anyMatch(vector -> vector.size() != dimensions)) {
            throw new RetrievalProviderException(
                    "EMBEDDING_DIMENSION_MISMATCH", "Embedding result does not match projection contract");
        }
    }

    private void markFailed(Projection projection, RuntimeException ex) {
        String errorCode = ex instanceof RetrievalProviderException provider
                ? provider.errorCode()
                : "RETRIEVAL_PROJECTION_WRITE_FAILED";
        try {
            projectionRepository.markFailed(projection.id(), errorCode, Instant.now().plusSeconds(60));
        } catch (IllegalStateException ignored) {
            // The projection may already be terminal after a write acknowledgement race.
        }
    }

    private SourceRow loadSource(String workspaceId, String sourceId, String snapshotId) {
        return jdbcTemplate.queryForObject("""
                select s.title, s.source_type, coalesce(s.summary, '') as summary,
                       coalesce(s.tags_json, '[]') as tags_json,
                       coalesce(s.metadata_json, '{}') as metadata_json,
                       ss.sha256
                from source s
                join source_snapshot ss on ss.source_id = s.id
                where s.id = ? and s.workspace_id = ? and ss.id = ?
                  and s.status <> 'DELETED' and ss.parse_status = 'PARSED'
                """, (rs, rowNum) -> new SourceRow(
                rs.getString("title"), rs.getString("source_type"), rs.getString("summary"),
                rs.getString("tags_json"), rs.getString("metadata_json"), rs.getString("sha256")),
                sourceId, workspaceId, snapshotId);
    }

    private List<ChunkRow> loadChunks(String workspaceId, String sourceId, String snapshotId) {
        return jdbcTemplate.query("""
                select id, chunk_no, coalesce(heading, '') as heading, content
                from source_chunk
                where workspace_id = ? and source_id = ? and source_snapshot_id = ?
                order by chunk_no
                """, (rs, rowNum) -> new ChunkRow(
                rs.getString("id"), rs.getInt("chunk_no"),
                rs.getString("heading"), rs.getString("content")),
                workspaceId, sourceId, snapshotId);
    }

    private int windowCount(String snapshotId) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*)
                from source_window w
                join source_chunk c on c.id = w.source_chunk_id
                where c.source_snapshot_id = ?
                """, Integer.class, snapshotId);
        return count == null ? 0 : count;
    }

    /**
     * 片段向量的输入：资料标题与章节路径作为上下文前缀，再接正文。
     * 片段单独看时常常缺少主语和所属章节，加上前缀能让向量召回按"哪份资料的哪一节"区分同类内容。
     */
    private String qaEmbeddingText(SourceRow source, ChunkRow chunk) {
        String context = text(source.title());
        String heading = text(chunk.heading());
        if (!heading.isBlank()) {
            context = context.isBlank() ? heading : context + " > " + heading;
        }
        return (context + "\n" + text(chunk.content())).trim();
    }

    private String noteEmbeddingText(SourceRow source, List<ChunkRow> chunks) {
        return String.join("\n",
                text(source.title()),
                text(source.sourceType()),
                text(source.summary()),
                String.join(" ", tags(source.tagsJson())),
                text(source.metadataJson()),
                String.join("\n", headings(chunks)));
    }

    private List<String> tags(String json) {
        return sourceTagCodec.decode(json);
    }

    private List<String> headings(List<ChunkRow> chunks) {
        LinkedHashSet<String> headings = new LinkedHashSet<>();
        chunks.stream().map(ChunkRow::heading).filter(value -> value != null && !value.isBlank())
                .forEach(headings::add);
        return new ArrayList<>(headings);
    }

    private String sha256(String value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text(value).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private String text(String value) {
        return value == null ? "" : value;
    }

    private record SourceRow(
            String title,
            String sourceType,
            String summary,
            String tagsJson,
            String metadataJson,
            String sha256
    ) {
    }

    private record ChunkRow(String chunkId, int chunkNo, String heading, String content) {
    }

    /** 单个检查点最多保留 64 条片段向量，末批额外保留资料向量。 */
    public record BatchEmbeddings(
            int batchNo, String embeddingVersion, String chunkerVersion, String contextPromptVersion,
            String model, int dimensions, List<String> chunkIds, List<String> qaTextHashes,
            List<String> qaFullTextHashes, List<List<Float>> qaVectors, List<String> contexts,
            List<String> contextReasons, int modelCalls, String noteTextHash, List<Float> noteVector
    ) {
        public BatchEmbeddings {
            chunkIds = List.copyOf(chunkIds);
            qaTextHashes = List.copyOf(qaTextHashes);
            qaFullTextHashes = List.copyOf(qaFullTextHashes);
            qaVectors = qaVectors.stream().map(List::copyOf).toList();
            contexts = List.copyOf(contexts);
            contextReasons = List.copyOf(contextReasons);
            noteVector = List.copyOf(noteVector);
            int size = chunkIds.size();
            if (batchNo < 0 || embeddingVersion == null || embeddingVersion.isBlank()
                    || chunkerVersion == null || chunkerVersion.isBlank()
                    || contextPromptVersion == null || contextPromptVersion.isBlank()
                    || model == null || model.isBlank() || dimensions <= 0 || modelCalls < 0 || modelCalls > 2
                    || size == 0 || size > EMBEDDING_BATCH_SIZE
                    || new LinkedHashSet<>(chunkIds).size() != size || chunkIds.stream().anyMatch(String::isBlank)
                    || qaTextHashes.size() != size || qaFullTextHashes.size() != size || qaVectors.size() != size
                    || contexts.size() != size || contextReasons.size() != size
                    || qaTextHashes.stream().anyMatch(hash -> !validHash(hash))
                    || qaFullTextHashes.stream().anyMatch(hash -> !validHash(hash))
                    || qaVectors.stream().anyMatch(vector -> !validVector(vector, dimensions))
                    || noteTextHash == null || noteVector.isEmpty() != noteTextHash.isEmpty()
                    || (!noteVector.isEmpty() && (!validHash(noteTextHash) || !validVector(noteVector, dimensions)))) {
                throw new IllegalArgumentException("向量批次的结构、摘要或数值无效");
            }
        }

        private static boolean validHash(String value) {
            return value != null && value.matches("sha256:[0-9a-f]{64}");
        }

        private static boolean validVector(List<Float> vector, int dimensions) {
            return vector.size() == dimensions && vector.stream().allMatch(value -> value != null && Float.isFinite(value));
        }
    }

    /** 一个快照的全部向量：每个片段一条，另有一条代表整份资料。 */
    public record SnapshotEmbeddings(
            String embeddingVersion,
            String model,
            int dimensions,
            List<String> chunkIds,
            List<String> qaTextHashes,
            List<List<Float>> qaVectors,
            String noteTextHash,
            List<Float> noteVector
    ) {
        public SnapshotEmbeddings {
            chunkIds = List.copyOf(chunkIds);
            qaTextHashes = List.copyOf(qaTextHashes);
            qaVectors = qaVectors.stream().map(List::copyOf).toList();
            noteVector = List.copyOf(noteVector);
            if (chunkIds.size() != qaTextHashes.size() || chunkIds.size() != qaVectors.size()) {
                throw new IllegalArgumentException("snapshot embeddings are misaligned");
            }
        }

        /** 片段没有变化且向量版本与当前配置一致时，向量可以直接使用。 */
        public boolean matches(String currentEmbeddingVersion, List<String> currentChunkIds) {
            return embeddingVersion.equals(currentEmbeddingVersion) && chunkIds.equals(currentChunkIds);
        }
    }

    public record ProjectionResult(
            int qaReadyCount,
            int qaExpectedCount,
            boolean noteReady,
            String qaIndex,
            String noteIndex,
            String embeddingVersion
    ) {
        public boolean complete() {
            return qaExpectedCount > 0 && qaReadyCount == qaExpectedCount && noteReady;
        }
    }
}
