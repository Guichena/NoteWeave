package com.noteweave.artifact;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Controls only new parent creation; accepted work continues through rollback. */
@Component
public class VideoLearningRolloutPolicy {
    private final boolean enabled;
    private final Set<String> workspaceAllowlist;

    public VideoLearningRolloutPolicy(
            @Value("${noteweave.video-learning.enabled:false}") boolean enabled,
            @Value("${noteweave.video-learning.workspace-allowlist:}") String allowlist) {
        this.enabled = enabled;
        this.workspaceAllowlist = Arrays.stream(allowlist.split(","))
                .map(String::trim).filter(value -> !value.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    public boolean allows(String workspaceId) {
        return enabled && workspaceId != null && !workspaceId.isBlank()
                && (workspaceAllowlist.isEmpty() || workspaceAllowlist.contains(workspaceId));
    }
}
