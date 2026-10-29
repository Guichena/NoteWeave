package com.noteweave.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class ArtifactSkillCatalogPresentationTest {
    @Test
    void canonicalSkillsAreListedWithKindAndAliasesResolveToTheSameDefinition() {
        var catalog = new ArtifactSkillCatalogService();
        assertThat(catalog.listSkills()).hasSize(14);
        assertThat(catalog.listSkills()).allSatisfy(skill -> {
            assertThat(skill.displayName()).isNotBlank();
            assertThat(skill.description()).isNotBlank();
            assertThat(skill.defaultInputHints()).isNotEmpty();
            assertThat(skill.kind()).isIn("DOC", "QUIZ", "CARDS", "TREE", "TABLE", "DECK");
        });
        assertThat(catalog.listSkills()).extracting(ArtifactSkillSummaryResponse::skillKey)
                .doesNotContain("audio_minutes", "wiki_page", "resume_highlight");
        assertThat(catalog.resolveSkill("audio_minutes")).isEqualTo(catalog.resolveSkill("briefing"));
        assertThat(catalog.resolveSkill("knowledge_blog")).isEqualTo(catalog.resolveSkill("blog-post"));
        assertThat(catalog.resolveActionKey("video_learning_deck")).isEqualTo("DECK");
    }
}
