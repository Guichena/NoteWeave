package com.noteweave.knowledge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * C3 READY 摄取意图的消费端（A 侧实现）。
 *
 * <p>B 的持久意图调度器认领到到期意图后调用本处理器；处理本身幂等，
 * 同快照同策略重复投递只产生一个有效 Wiki 任务。旧 AFTER_COMMIT 监听
 * WikiSourceReadyListener 保留为唤醒提示，不再是唯一持久机制。</p>
 */
@Component
public class WikiReadyIngestIntentHandler {

    private static final Logger log = LoggerFactory.getLogger(WikiReadyIngestIntentHandler.class);

    private final WikiIngestService wikiIngestService;

    public WikiReadyIngestIntentHandler(WikiIngestService wikiIngestService) {
        this.wikiIngestService = wikiIngestService;
    }

    public WikiIngestIntentOutcome handle(WikiReadySourceIntent intent) {
        if (intent == null || !intent.isValid()) {
            log.warn("Wiki ready intent ignored: invalid payload");
            return WikiIngestIntentOutcome.IGNORED_STALE;
        }
        try {
            return wikiIngestService.enqueueSourceIngestForReadySnapshot(intent);
        } catch (RuntimeException ex) {
            log.warn("Wiki ready intent retryable failure: sourceId={}, snapshotId={}, reason={}",
                    intent.sourceId(), intent.snapshotId(),
                    ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
            return WikiIngestIntentOutcome.RETRYABLE_FAILURE;
        }
    }
}
