package com.noteweave.retrieval.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.config.NoteWeaveProperties;
import com.noteweave.infra.ElasticsearchChunkSearchAdapter;
import com.noteweave.infra.ElasticsearchConfig;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("retrieval-evaluation-cli")
@EnableConfigurationProperties(NoteWeaveProperties.class)
@Import({
        ElasticsearchConfig.class,
        ElasticsearchChunkSearchAdapter.class,
        RetrievalSnapshotSanitizer.class,
        QaGoldAnnotationDraftCapture.class,
        QaGoldAnnotationCompiler.class
})
public class RetrievalEvaluationCliConfiguration {

    @Bean
    ObjectMapper retrievalEvaluationObjectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }
}
