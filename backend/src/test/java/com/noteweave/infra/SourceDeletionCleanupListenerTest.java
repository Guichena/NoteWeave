package com.noteweave.infra;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.noteweave.config.NoteWeaveProperties;
import com.noteweave.source.SourceDeletedEvent;
import com.noteweave.storage.ObjectStorage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class SourceDeletionCleanupListenerTest {

    @Test
    void shouldCleanElasticsearchAndUnreferencedObjectAfterCommit() {
        ObjectStorage storage = mock(ObjectStorage.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ElasticsearchIndexer> provider = mock(ObjectProvider.class);
        ElasticsearchIndexer indexer = mock(ElasticsearchIndexer.class);
        when(provider.getIfAvailable()).thenReturn(indexer);
        SourceDeletionCleanupListener listener = new SourceDeletionCleanupListener(
                storage, provider, new NoteWeaveProperties(null, null, null, null, null, null));

        listener.cleanup(new SourceDeletedEvent(
                "workspace-1", "source-1", "workspace/workspace-1/file.txt"));

        verify(indexer).deleteBySourceId("workspace-1", "source-1");
        verify(storage).delete("noteweave-source", "workspace/workspace-1/file.txt");
    }
}
