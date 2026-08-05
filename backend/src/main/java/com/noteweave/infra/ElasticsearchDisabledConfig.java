package com.noteweave.infra;

import com.noteweave.retrieval.index.NoOpRetrievalProjectionWriter;
import com.noteweave.retrieval.index.RetrievalProjectionWriter;
import com.noteweave.retrieval.note.NoOpNoteSourceSearchAdapter;
import com.noteweave.retrieval.note.NoteSourceSearchPort;
import com.noteweave.retrieval.qa.NoOpQaHybridSearchAdapter;
import com.noteweave.retrieval.qa.QaHybridSearchPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Provides local-only retrieval stubs when Elasticsearch is disabled. */
@Configuration
@ConditionalOnProperty(name = "noteweave.elasticsearch.enabled", havingValue = "false")
public class ElasticsearchDisabledConfig {

    @Bean
    public RetrievalProjectionWriter retrievalProjectionWriter() {
        return new NoOpRetrievalProjectionWriter();
    }

    @Bean
    public QaHybridSearchPort qaHybridSearchPort() {
        return new NoOpQaHybridSearchAdapter();
    }

    @Bean
    public NoteSourceSearchPort noteSourceSearchPort() {
        return new NoOpNoteSourceSearchAdapter();
    }
}
