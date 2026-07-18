package com.noteweave.retrieval.eval;

import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;

/** Minimal JDBC-only runtime for exporting already-persisted QA AnswerRun retrieval artifacts. */
@Configuration(proxyBeanMethods = false)
@Profile("qa-answer-run-shadow-export-cli")
@ImportAutoConfiguration({
        DataSourceAutoConfiguration.class,
        JdbcTemplateAutoConfiguration.class,
        DataSourceTransactionManagerAutoConfiguration.class,
        TransactionAutoConfiguration.class,
        JacksonAutoConfiguration.class
})
@Import({
        RetrievalSnapshotSanitizer.class,
        RetrievalExecutionShadowExporter.class,
        QaAnswerRunShadowExportService.class
})
public class QaAnswerRunShadowExportCliConfiguration {
}
