package com.noteweave.artifact;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class VideoLearningRolloutPolicyTest {
    @Test
    void workspaceAllowlistControlsNewIntake() {
        var grey = new VideoLearningRolloutPolicy(true, "workspace-a, workspace-b");
        assertThat(grey.allows("workspace-a")).isTrue();
        assertThat(grey.allows("workspace-b")).isTrue();
        assertThat(grey.allows("workspace-c")).isFalse();
        assertThat(grey.allows("")).isFalse();
        assertThat(new VideoLearningRolloutPolicy(false, "workspace-a")
                .allows("workspace-a")).isFalse();
        assertThat(new VideoLearningRolloutPolicy(true, "")
                .allows("workspace-c")).isTrue();
    }
}
