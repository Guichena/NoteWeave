package com.noteweave.artifact;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** A disabled intake still drains already accepted parent requests. */
@Component
@ConditionalOnProperty(name = "noteweave.video-learning.child-coordinator.enabled",
        havingValue = "true", matchIfMissing = true)
public class VideoLearningChildCoordinatorScheduler {
    private static final Logger log = LoggerFactory.getLogger(
            VideoLearningChildCoordinatorScheduler.class);
    private final VideoLearningChildCoordinator coordinator;

    public VideoLearningChildCoordinatorScheduler(VideoLearningChildCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    @Scheduled(fixedDelayString = "${noteweave.video-learning.child-reconcile-delay-ms:5000}")
    public void reconcile() {
        for (var choice : coordinator.missingChoices(50)) {
            try {
                coordinator.reconcileChoice(choice);
            } catch (Exception failure) {
                String detail = failure instanceof com.noteweave.common.BusinessException business
                        ? business.code() + " " + business.getMessage()
                        : failure.getClass().getSimpleName();
                log.warn("Video learning child reconciliation failed for request {} skill {}: {}",
                        choice.requestId(), choice.skillKey(), detail);
                log.debug("Video learning child reconciliation stack", failure);
            }
        }
    }
}
