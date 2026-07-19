package com.noteweave.config;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties(prefix = "noteweave")
public record NoteWeaveProperties(
        Storage storage,
        Document document,
        Worker worker,
        Kafka kafka,
        Elasticsearch elasticsearch,
        Embedding embedding,
        Rerank rerank,
        Llm llm
) {

    public NoteWeaveProperties(
            Storage storage,
            Document document,
            Worker worker,
            Kafka kafka,
            Elasticsearch elasticsearch,
            Llm llm
    ) {
        this(storage, document, worker, kafka, elasticsearch, null, null, llm);
    }

    @ConstructorBinding
    public NoteWeaveProperties {
        if (storage == null) {
            storage = new Storage("local", Path.of("target/noteweave-storage"),
                    new Minio("http://localhost:9000", "minioadmin", "minioadmin",
                            "us-east-1", "noteweave-source", "noteweave-derived", "noteweave-export"));
        }
        if (document == null) {
            document = new Document(900, 120);
        }
        if (worker == null) {
            worker = new Worker("http://localhost:18092");
        }
        if (kafka == null || kafka.topics() == null) {
            kafka = new Kafka(kafka == null || kafka.enabled(), defaultTopics());
        }
        if (elasticsearch == null) {
            elasticsearch = new Elasticsearch(true, "localhost", 9200, "http", "noteweave_chunk");
        }
        if (embedding == null) {
            embedding = new Embedding(false, "", "", "", 1024, 32, 10L, 60L, 12_000, 3);
        }
        if (rerank == null) {
            rerank = new Rerank(false, "", "", "", 80, 1800, 20L, 3);
        }
        if (llm == null) {
            llm = new Llm(false, "", "", "", 60L);
        }
    }

    private static Topics defaultTopics() {
        return new Topics(
                "noteweave.source.parse",
                "noteweave.source.chunk",
                "noteweave.source.index",
                "noteweave.retrieval.projection",
                "noteweave.wiki.ingest",
                "noteweave.wiki.retract",
                "noteweave.generated.ingest",
                "noteweave.conversation.summary");
    }

    public record Storage(String backend, Path localRoot, Minio minio) {
    }

    public record Minio(
            String endpoint,
            String accessKey,
            String secretKey,
            String region,
            String bucketSource,
            String bucketDerived,
            String bucketExport
    ) {
    }

    public record Document(int chunkSize, int chunkOverlap) {
    }

    public record Worker(String artifactBaseUrl) {
    }

    public record Kafka(boolean enabled, Topics topics) {
    }

    public record Topics(
            String sourceParse,
            String sourceChunk,
            String sourceIndex,
            String retrievalProjection,
            String wikiIngest,
            String wikiRetract,
            String generatedIngest,
            String conversationSummary
    ) {
        public Topics(
                String sourceParse,
                String sourceChunk,
                String sourceIndex,
                String wikiIngest,
                String wikiRetract,
                String generatedIngest,
                String conversationSummary
        ) {
            this(sourceParse, sourceChunk, sourceIndex, "noteweave.retrieval.projection",
                    wikiIngest, wikiRetract, generatedIngest, conversationSummary);
        }
    }

    public record Elasticsearch(
            boolean enabled,
            String host,
            int port,
            String scheme,
            String indexPrefix
    ) {
    }

    public record Embedding(
            boolean enabled,
            String endpoint,
            String model,
            String apiKey,
            int dimensions,
            int documentBatchSize,
            long queryTimeoutSeconds,
            long batchTimeoutSeconds,
            int maxInputCharacters,
            int maxAttempts
    ) {
    }

    public record Rerank(
            boolean enabled,
            String endpoint,
            String model,
            String apiKey,
            int batchSize,
            int maxDocumentCharacters,
            long timeoutSeconds,
            int maxAttempts
    ) {
    }

    public record Llm(
            boolean enabled,
            String endpoint,
            String model,
            String apiKey,
            long timeoutSeconds
    ) {
    }
}
