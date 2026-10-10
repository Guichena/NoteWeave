package com.noteweave.artifact;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ArtifactSkillCatalogPresentationTest {

    private final ArtifactSkillCatalogService catalog = new ArtifactSkillCatalogService();

    @Test
    void descriptionsHintsAndPresentationComeFromTheCatalog() {
        ArtifactSkillSummaryResponse minutes = catalog.listSkills().stream()
                .filter(skill -> skill.skillKey().equals("audio_minutes")).findFirst().orElseThrow();

        assertThat(minutes.description()).isEqualTo("生成音频纪要");
        assertThat(minutes.defaultInputHints()).containsExactly("总结会议结论", "补充行动项", "标注待确认问题");
        assertThat(minutes.presentation()).containsEntry("group", "media").containsKey("summary");
    }

    @Test
    void skillDeclaredOnlyInTheCatalogIsListedAndBoundToItsAction() {
        // 「精读摘要」只在技能目录里声明，Java 没有任何针对它的代码
        ArtifactSkillSummaryResponse digest = catalog.listSkills().stream()
                .filter(skill -> skill.skillKey().equals("reading_digest")).findFirst().orElseThrow();

        assertThat(digest.displayName()).isEqualTo("精读摘要");
        assertThat(digest.defaultInputHints()).contains("论点先行");
        assertThat(digest.presentation()).containsEntry("group", "sources");
        assertThat(catalog.resolveActionKey("reading_digest")).isEqualTo("READING_DIGEST");
        assertThat(catalog.requiredFileRoles("reading_digest")).containsExactly("PRIMARY_MARKDOWN");
    }
}
