package com.noteweave.retrieval.projection;

import com.noteweave.config.NoteWeaveProperties;
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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class SourceRetrievalProjectionService {
    public static final String QA_SCHEMA_VERSION = "qa-chunk-index-v1";
    public static final String NOTE_SCHEMA_VERSION = "note-source-index-v1";
    private static final String EMBEDDING_PROVIDER = "openai-compatible";

    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingClient embeddingClient;
    private final RetrievalProjectionRepository projectionRepository;
    private final RetrievalIndexManager indexManager;
    private final RetrievalProjectionWriter projectionWriter;
    private final NoteWeaveProperties properties;
    private final SourceTagCodec sourceTagCodec;

    public SourceRetrievalProjectionService(
            JdbcTemplate jdbcTemplate,
            EmbeddingClient embeddingClient,
            RetrievalProjectionRepository projectionRepository,
            RetrievalIndexManager indexManager,
            RetrievalProjectionWriter projectionWriter,
            NoteWeaveProperties properties,
            SourceTagCodec sourceTagCodec
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.embeddingClient = embeddingClient;
        this.projectionRepository = projectionRepository;
        this.indexManager = indexManager;
        this.projectionWriter = projectionWriter;
        this.properties = properties;
        this.sourceTagCodec = sourceTagCodec;
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
        return writeIndexes(workspaceId, sourceId, snapshotId, qaIndex, noteIndex,
                computeEmbeddings(workspaceId, sourceId, snapshotId));
    }

    /** 向量化：为每个片段和整份资料计算向量，不写索引。 */
    public SnapshotEmbeddings computeEmbeddings(String workspaceId, String sourceId, String snapshotId) {
        if (!embeddingClient.isEnabled()) {
            throw new RetrievalProviderException(
                    "EMBEDDING_PROVIDER_DISABLED", "Source retrieval projection requires embedding");
        }
        SourceRow source = loadSource(workspaceId, sourceId, snapshotId);
        List<ChunkRow> chunks = loadChunks(workspaceId, sourceId, snapshotId);
        if (chunks.isEmpty()) {
            throw new IllegalStateException("Source snapshot has no chunks to project");
        }
        int dimensions = properties.embedding().dimensions();
        String model = properties.embedding().model();
        List<String> qaTexts = chunks.stream().map(this::qaEmbeddingText).toList();
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

    private String qaEmbeddingText(ChunkRow chunk) {
        return (text(chunk.heading()) + "\n" + text(chunk.content())).trim();
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
