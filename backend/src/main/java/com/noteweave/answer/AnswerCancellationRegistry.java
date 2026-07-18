package com.noteweave.answer;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** Local fast-path plus optional Redis propagation; MySQL terminal CAS remains authoritative. */
@Component
public class AnswerCancellationRegistry {

    private final Set<String> cancelledRuns = ConcurrentHashMap.newKeySet();
    private final AnswerRealtimeBridge realtimeBridge;

    public AnswerCancellationRegistry(ObjectProvider<AnswerRealtimeBridge> bridgeProvider) {
        this.realtimeBridge = bridgeProvider.getIfAvailable();
    }

    public void cancel(String runId) {
        cancelledRuns.add(runId);
        if (realtimeBridge != null) {
            realtimeBridge.signalCancellation(runId);
        }
    }

    public boolean isCancelled(String runId) {
        return cancelledRuns.contains(runId)
                || (realtimeBridge != null && realtimeBridge.isCancellationRequested(runId));
    }

    public void clear(String runId) {
        cancelledRuns.remove(runId);
    }
}
