package com.noteweave.config;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "noteweave")
public record NoteWeaveProperties(
        Storage storage,
        Document document,
        Worker worker,
        Kafka kafka,
        Elasticsearch elasticsearch,
        Llm llm
) {

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
        if (kafka == null) {
            kafka = new Kafka(true, new Topics(
                    "noteweave.source.parse",
                    "noteweave.source.chunk",
                    "noteweave.source.index",
                    "noteweave.wiki.ingest",
                    "noteweave.wiki.retract",
                    "noteweave.generated.ingest",
                    "noteweave.conversation.summary"));
        }
        if (elasticsearch == null) {
            elasticsearch = new Elasticsearch(true, "localhost", 9200, "http", "noteweave_chunk");
        }
        if (llm == null) {
            llm = new Llm(false, "", "", "", 60L);
        }
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
            String wikiIngest,
            String wikiRetract,
            String generatedIngest,
            String conversationSummary
    ) {
    }

    public record Elasticsearch(
            boolean enabled,
            String host,
            int port,
            String scheme,
            String indexPrefix
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
