package com.noteweave.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.noteweave.retrieval.note.NoOpNoteSourceSearchAdapter;
import com.noteweave.retrieval.index.NoOpRetrievalProjectionWriter;
import com.noteweave.retrieval.provider.RetrievalProviderException;
import com.noteweave.retrieval.qa.NoOpQaHybridSearchAdapter;
import com.noteweave.retrieval.qa.QaHybridSearchPort.QaKeywordQuery;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DisabledRetrievalAdaptersTest {

    @Test
    void disabledQaSearchMustReportProviderUnavailable() {
        NoOpQaHybridSearchAdapter adapter = new NoOpQaHybridSearchAdapter();

        assertThatThrownBy(() -> adapter.keywordRetrieve(
                new QaKeywordQuery("workspace", "query", Set.of(), 10, 0)))
                .isInstanceOfSatisfying(RetrievalProviderException.class, error ->
                        assertThat(error.errorCode()).isEqualTo("QA_SEARCH_PROVIDER_DISABLED"));
    }

    @Test
    void disabledNoteSearchMustReportProviderUnavailable() {
        NoOpNoteSourceSearchAdapter adapter = new NoOpNoteSourceSearchAdapter();

        assertThatThrownBy(() -> adapter.metadataRetrieve("workspace", "query", 10))
                .isInstanceOfSatisfying(RetrievalProviderException.class, error ->
                        assertThat(error.errorCode())
                                .isEqualTo("NOTE_SOURCE_SEARCH_PROVIDER_DISABLED"));
    }

    @Test
    void disabledProjectionWriterMustNotAcknowledgeWritesOrLifecycleChanges() {
        NoOpRetrievalProjectionWriter writer = new NoOpRetrievalProjectionWriter();

        assertDisabled(() -> writer.writeQaChunk("index", null));
        assertDisabled(() -> writer.writeNoteSource("index", null));
        assertDisabled(() -> writer.markSnapshotNotCurrent("index", "snapshot"));
        assertDisabled(() -> writer.markSnapshotCurrent("index", "snapshot"));
        assertDisabled(() -> writer.deleteSource("index", "source"));
    }

    private void assertDisabled(org.junit.jupiter.api.function.Executable action) {
        assertThatThrownBy(() -> action.execute())
                .isInstanceOfSatisfying(RetrievalProviderException.class, error ->
                        assertThat(error.errorCode())
                                .isEqualTo("RETRIEVAL_PROJECTION_PROVIDER_DISABLED"));
    }
}
