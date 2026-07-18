package com.noteweave.infra;

import com.noteweave.config.NoteWeaveProperties;
import com.noteweave.source.SourceDeletedEvent;
import com.noteweave.storage.ObjectStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class SourceDeletionCleanupListener {

    private static final Logger log = LoggerFactory.getLogger(SourceDeletionCleanupListener.class);

    private final ObjectStorage objectStorage;
    private final ObjectProvider<ElasticsearchIndexer> elasticsearchIndexer;
    private final String sourceBucket;

    public SourceDeletionCleanupListener(
            ObjectStorage objectStorage,
            ObjectProvider<ElasticsearchIndexer> elasticsearchIndexer,
            NoteWeaveProperties properties
    ) {
        this.objectStorage = objectStorage;
        this.elasticsearchIndexer = elasticsearchIndexer;
        this.sourceBucket = properties.storage().minio().bucketSource();
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void cleanup(SourceDeletedEvent event) {
        ElasticsearchIndexer indexer = elasticsearchIndexer.getIfAvailable();
        if (indexer != null) {
            indexer.deleteBySourceId(event.workspaceId(), event.sourceId());
        }
        if (event.objectKey() == null || event.objectKey().isBlank()) {
            return;
        }
        try {
            objectStorage.delete(sourceBucket, event.objectKey());
        } catch (RuntimeException ex) {
            log.warn("Source object cleanup failed after delete: sourceId={}, objectKey={}, reason={}",
                    event.sourceId(), event.objectKey(), ex.getMessage());
        }
    }
}
