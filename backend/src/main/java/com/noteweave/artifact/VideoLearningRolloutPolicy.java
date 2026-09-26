package com.noteweave.artifact;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Controls only new parent creation; accepted work continues through rollback. */
@Component
public class VideoLearningRolloutPolicy {
    private final boolean enabled;
    private final Set<String> workspaceAllowlist;
    private final Set<String> blockedSkillVersions;
    private final ArtifactSkillCatalogService catalog;

    public VideoLearningRolloutPolicy(
            @Value("${noteweave.video-learning.enabled:false}") boolean enabled,
            @Value("${noteweave.video-learning.workspace-allowlist:}") String allowlist,
            @Value("${noteweave.video-learning.blocked-skill-versions:}") String blockedSkillVersions,
            ArtifactSkillCatalogService catalog) {
        this.enabled = enabled;
        this.workspaceAllowlist = parse(allowlist);
        this.blockedSkillVersions = parse(blockedSkillVersions);
        this.catalog = catalog;
    }

    private static Set<String> parse(String csv) {
        return Arrays.stream(csv.split(","))
                .map(String::trim).filter(value -> !value.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    public boolean allows(String workspaceId) {
        return enabled && workspaceId != null && !workspaceId.isBlank()
                && (workspaceAllowlist.isEmpty() || workspaceAllowlist.contains(workspaceId));
    }

    public List<String> availableSkills(String workspaceId) {
        if (!allows(workspaceId)) return List.of();
        return VideoLearningRequestDraft.supportedSkills().stream()
                .filter(skill -> !blockedSkillVersions.contains(skill + "@" + catalog.publishedVersion(skill)))
                .toList();
    }

    public boolean allows(String workspaceId, List<String> selectedSkills) {
        return selectedSkills != null && availableSkills(workspaceId).containsAll(selectedSkills);
    }
}
