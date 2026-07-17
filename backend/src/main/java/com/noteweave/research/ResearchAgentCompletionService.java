package com.noteweave.research;

import com.noteweave.common.BusinessException;
import io.micrometer.core.instrument.Timer;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Non-transactional completion facade: validates canonical bytes, serves an
 * immutable fast replay, invokes exactly one REQUIRED DB transaction, and
 * hosts the post-commit/response-loss test boundary.
 */
@Service
public class ResearchAgentCompletionService {
    private final ResearchAgentCompletionCanonicalizer canonicalizer;
    private final ResearchAgentCompletionReplayRepository replayRepository;
    private final ResearchAgentCompletionCommitter committer;
    private final ObjectProvider<ResearchAgentCompletionFaultInjector> faultInjectors;
    private final ResearchAgentCompletionMetrics completionMetrics;

    public ResearchAgentCompletionService(
            ResearchAgentCompletionCanonicalizer canonicalizer,
            ResearchAgentCompletionReplayRepository replayRepository,
            ResearchAgentCompletionCommitter committer,
            ObjectProvider<ResearchAgentCompletionFaultInjector> faultInjectors,
            ResearchAgentCompletionMetrics completionMetrics
    ) {
        this.canonicalizer = canonicalizer;
        this.replayRepository = replayRepository;
        this.committer = committer;
        this.faultInjectors = faultInjectors;
        this.completionMetrics = completionMetrics;
    }

    public ResearchAgentCompletionReceipt complete(String pathTaskId, ResearchAgentCompletionEnvelope envelope) {
        Timer.Sample latency = completionMetrics.startLatency();
        boolean transactionInFlight = false;
        try {
            ResearchAgentCompletionCanonicalizer.ValidatedEnvelope validated = canonicalizer.validateAndVerify(envelope);
            completionMetrics.recordValidatedPayload(
                    validated.canonicalJson().getBytes(StandardCharsets.UTF_8).length,
                    validated.envelope().candidates().size());
            if (pathTaskId == null || !pathTaskId.equals(validated.envelope().taskId())) {
                throw new BusinessException("RESEARCH_AGENT_COMPLETION_INVALID",
                        "Path task id does not match completion task identity");
            }
            ResearchAgentCompletionReplayRepository.CompletionRow existing =
                    replayRepository.findByTask(pathTaskId, false);
            if (existing != null) {
                ResearchAgentCompletionReceipt replay = replayRepository.resolve(existing, validated);
                completionMetrics.recordReplay();
                return replay;
            }

            completionMetrics.beginTransaction();
            transactionInFlight = true;
            ResearchAgentCompletionReceipt receipt = committer.commit(validated);
            transactionInFlight = false;
            if (receipt.idempotentReplay()) {
                completionMetrics.recordReplay();
            } else {
                completionMetrics.recordCommit(receipt);
                checkpoint(ResearchAgentCompletionFaultInjector.Stage.AFTER_COMMIT_BEFORE_HTTP_RESPONSE, 0);
            }
            return receipt;
        } catch (BusinessException exception) {
            completionMetrics.recordConflict(exception.code());
            if (transactionInFlight) completionMetrics.recordRollback();
            throw exception;
        } catch (RuntimeException exception) {
            if (transactionInFlight) completionMetrics.recordRollback();
            throw exception;
        } finally {
            completionMetrics.stopLatency(latency);
            completionMetrics.clearTransaction();
        }
    }

    private void checkpoint(ResearchAgentCompletionFaultInjector.Stage stage, int ordinal) {
        faultInjectors.orderedStream().forEach(injector -> injector.checkpoint(stage, ordinal));
    }
}
