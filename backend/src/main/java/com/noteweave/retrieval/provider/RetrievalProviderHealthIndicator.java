package com.noteweave.retrieval.provider;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;
import com.noteweave.config.NoteWeaveProperties;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;

@Component("retrievalProviders")
public class RetrievalProviderHealthIndicator implements HealthIndicator {
    private final EmbeddingClient embeddingClient;
    private final RerankClient rerankClient;
    private final int expectedDimensions;

    @Autowired
    public RetrievalProviderHealthIndicator(
            EmbeddingClient embeddingClient,
            RerankClient rerankClient,
            NoteWeaveProperties properties
    ) {
        this.embeddingClient = embeddingClient;
        this.rerankClient = rerankClient;
        this.expectedDimensions = properties.embedding().dimensions();
    }

    public RetrievalProviderHealthIndicator(EmbeddingClient embeddingClient, RerankClient rerankClient) {
        this.embeddingClient = embeddingClient;
        this.rerankClient = rerankClient;
        this.expectedDimensions = -1;
    }

    @Override
    public Health health() {
        boolean embeddingEnabled = embeddingClient.isEnabled();
        boolean rerankEnabled = rerankClient.isEnabled();
        boolean embeddingProbe = false;
        boolean rerankProbe = false;
        String errorCode = "";
        if (embeddingEnabled && rerankEnabled) {
            try {
                EmbeddingClient.EmbeddingResult embedding = embeddingClient.embedQuery("noteweave-health-probe");
                int required = expectedDimensions > 0 ? expectedDimensions : embedding.dimensions();
                embeddingProbe = embedding.vectors().size() == 1
                        && embedding.dimensions() == required
                        && embedding.singleVector().size() == required;
                RerankClient.RerankResult rerank = rerankClient.rerank(
                        "noteweave-health-probe", List.of("noteweave-health-probe"), 1);
                rerankProbe = rerank.hits().size() == 1
                        && rerank.hits().get(0).documentIndex() == 0;
            } catch (RuntimeException ex) {
                errorCode = ex instanceof RetrievalProviderException provider
                        ? provider.errorCode() : "RETRIEVAL_PROVIDER_PROBE_FAILED";
            }
        }
        Health.Builder builder = embeddingEnabled && rerankEnabled && embeddingProbe && rerankProbe
                ? Health.up() : Health.outOfService();
        return builder
                .withDetail("embedding_configured", embeddingEnabled)
                .withDetail("rerank_configured", rerankEnabled)
                .withDetail("embedding_probe", embeddingProbe)
                .withDetail("rerank_probe", rerankProbe)
                .withDetail("probe_error_code", errorCode)
                .build();
    }
}
