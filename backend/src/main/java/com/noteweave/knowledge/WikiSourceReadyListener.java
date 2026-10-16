package com.noteweave.knowledge;

import com.noteweave.source.SourceRetrievalReadyEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 资料索引完成并提交后再投递 Wiki 编译。上传完成时资料仍在解析，异步模式下提前投递的编译会因资料未就绪而失败。
 */
@Component
public class WikiSourceReadyListener {
    private final WikiIngestService wikiIngestService;

    public WikiSourceReadyListener(WikiIngestService wikiIngestService) {
        this.wikiIngestService = wikiIngestService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onSourceReady(SourceRetrievalReadyEvent event) {
        wikiIngestService.enqueueSourceIngestForReadySource(event.workspaceId(), event.sourceId());
    }
}
