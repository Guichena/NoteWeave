package com.noteweave.research;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.noteweave.common.BusinessException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** Proves digest/path rejection occurs before any replay or transaction query. */
class ResearchAgentCompletionFacadeTest {
    private final ResearchAgentCompletionCanonicalizer canonicalizer = new ResearchAgentCompletionCanonicalizer();
    private final ResearchAgentCompletionReplayRepository replayRepository =
            mock(ResearchAgentCompletionReplayRepository.class);
    private final ResearchAgentCompletionCommitter committer = mock(ResearchAgentCompletionCommitter.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<ResearchAgentCompletionFaultInjector> injectors = mock(ObjectProvider.class);
    private final ResearchAgentCompletionService service = new ResearchAgentCompletionService(
            canonicalizer, replayRepository, committer, injectors,
            new ResearchAgentCompletionMetrics(new SimpleMeterRegistry()));

    @Test
    void shouldRejectDigestMismatchBeforeAnyRepositoryQuery() {
        ResearchAgentCompletionEnvelope valid = envelope();
        ResearchAgentCompletionEnvelope wrongDigest = valid.withEnvelopeDigest("sha256:" + "f".repeat(64));

        assertThatThrownBy(() -> service.complete(valid.taskId(), wrongDigest))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).code())
                .isEqualTo("RESEARCH_AGENT_COMPLETION_DIGEST_MISMATCH");
        verifyNoInteractions(replayRepository, committer, injectors);
    }

    @Test
    void shouldRejectPathBodyMismatchBeforeAnyRepositoryQuery() {
        ResearchAgentCompletionEnvelope valid = envelope();

        assertThatThrownBy(() -> service.complete("00000000-0000-0000-0000-000000000002", valid))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).code())
                .isEqualTo("RESEARCH_AGENT_COMPLETION_INVALID");
        verifyNoInteractions(replayRepository, committer, injectors);
    }

    private ResearchAgentCompletionEnvelope envelope() {
        ResearchAgentCompletionEnvelope unsigned = new ResearchAgentCompletionEnvelope(
                "research-agent-completion.v1", "00000000-0000-0000-0000-000000000001",
                "worker-a", 1, 1,
                "deep-cell:00000000-0000-0000-0000-000000000001:1:1",
                "sha256:" + "1".repeat(64), "NO_SUPPORTED_CANDIDATE",
                Map.of("llm_calls", 0L, "search_calls", 0L, "fetch_calls", 0L, "read_calls", 0L,
                        "extract_calls", 0L, "evidence_cards", 0L, "candidates_submitted", 0L),
                Map.of("search_hits", 0L, "documents", 0L, "windows", 0L),
                "sha256:" + "2".repeat(64), List.of(), List.of(), null);
        return unsigned.withEnvelopeDigest(canonicalizer.digest(unsigned));
    }
}
