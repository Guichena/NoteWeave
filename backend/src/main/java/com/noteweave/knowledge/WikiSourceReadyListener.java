package com.noteweave.knowledge;

import com.noteweave.source.SourceRetrievalReadyEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 资料索引完成并提交后唤醒 Wiki 编译。上传完成时资料仍在解析，异步模式下提前投递的编译会因资料未就绪而失败。
 *
 * <p>WIKI-04：READY 事务已经把摄取意图持久化到 source_ready_intent，这里只做唤醒。
 * 原来直接调 {@code enqueueSourceIngestForReadySource} 会绕过意图路径再建一个任务，
 * 同一份资料在意图调度器和监听器之间出现两个任务，互相取消、重复灌库。</p>
 */
@Component
public class WikiSourceReadyListener {
    private final WikiIngestService wikiIngestService;
    private WikiReadyIntentWakeupPort wakeup;

    public WikiSourceReadyListener(WikiIngestService wikiIngestService) {
        this.wikiIngestService = wikiIngestService;
    }

    @Autowired(required = false)
    void setWakeup(WikiReadyIntentWakeupPort wakeup) {
        this.wakeup = wakeup;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onSourceReady(SourceRetrievalReadyEvent event) {
        if (wakeup != null) {
            // 有意图通道时只唤醒：没有可唤醒的意图说明已经处理过或正在处理，
            // 历史缺口由调度器的回填兜底，不再另建任务
            wakeup.wake(event.workspaceId(), event.sourceId());
            return;
        }
        // 未装配意图通道（精简部署/单测）：退回既有的直接入队行为
        wikiIngestService.enqueueSourceIngestForReadySource(event.workspaceId(), event.sourceId());
    }
}
