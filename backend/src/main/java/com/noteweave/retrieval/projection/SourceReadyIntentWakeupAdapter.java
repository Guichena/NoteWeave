package com.noteweave.retrieval.projection;

import com.noteweave.knowledge.WikiReadyIntentWakeupPort;
import org.springframework.stereotype.Component;

/**
 * READY 意图唤醒端口的实现（WIKI-04）：只把待处理意图拉到立即到期，
 * 真正的建任务仍由 {@link SourceReadyIntentDispatcher} 认领后经 A 侧处理器完成。
 */
@Component
public class SourceReadyIntentWakeupAdapter implements WikiReadyIntentWakeupPort {

    private final SourceReadyIntentRepository intents;

    public SourceReadyIntentWakeupAdapter(SourceReadyIntentRepository intents) {
        this.intents = intents;
    }

    @Override
    public boolean wake(String workspaceId, String sourceId) {
        return intents.wakeDue(workspaceId, sourceId);
    }
}
