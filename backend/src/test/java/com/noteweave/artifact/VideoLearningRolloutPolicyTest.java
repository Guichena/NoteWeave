package com.noteweave.artifact;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class VideoLearningRolloutPolicyTest {
    @Test
    void workspaceAllowlistControlsNewIntake() {
        var catalog = new ArtifactSkillCatalogService();
        var grey = new VideoLearningRolloutPolicy(true, "workspace-a, workspace-b", "", catalog);
        assertThat(grey.allows("workspace-a")).isTrue();
        assertThat(grey.allows("workspace-b")).isTrue();
        assertThat(grey.allows("workspace-c")).isFalse();
        assertThat(grey.allows("")).isFalse();
        assertThat(new VideoLearningRolloutPolicy(false, "workspace-a", "", catalog)
                .allows("workspace-a")).isFalse();
        assertThat(new VideoLearningRolloutPolicy(true, "", "", catalog)
                .allows("workspace-c")).isTrue();
    }

    @Test
    void blocksOnlyNamedPublishedSkillVersionsFromNewSelections() {
        var catalog = new ArtifactSkillCatalogService();
        var policy = new VideoLearningRolloutPolicy(true, "",
                "knowledge_blog@1.0.0, video_learning_deck@0.9.0", catalog);
        assertThat(policy.availableSkills("workspace-a"))
                .doesNotContain("knowledge_blog")
                .contains("video_learning_deck", "interview_qa", "bilibili_course_note_pdf");
        assertThat(policy.allows("workspace-a", java.util.List.of("knowledge_blog"))).isFalse();
        assertThat(policy.allows("workspace-a", java.util.List.of("interview_qa"))).isTrue();
    }
}
