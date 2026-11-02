package com.noteweave.preference;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** F5-01/F5-02：同维度覆盖、不同维度并存、同层冲突与参数编译。 */
class PreferenceLayeringServiceTest {

    @Test
    void thisTurnOverridesSameTargetButKeepsOtherTerminology() {
        PreferenceEntry english = entry("answer.language", PreferenceSlotFamily.LANGUAGE,
                "English", "REQUIRE", PreferenceLayer.LONG_TERM, "acme", 1);
        PreferenceEntry chinese = entry("answer.language", PreferenceSlotFamily.LANGUAGE,
                "中文", "REQUIRE", PreferenceLayer.THIS_TURN, "acme", 2);
        PreferenceEntry termA = entry("terminology.a", PreferenceSlotFamily.TERMINOLOGY,
                "术语甲", "REQUIRE", PreferenceLayer.LONG_TERM, "acme", 3);
        PreferenceEntry termB = entry("terminology.b", PreferenceSlotFamily.TERMINOLOGY,
                "术语乙", "REQUIRE", PreferenceLayer.THIS_TURN, "acme", 4);

        PreferenceLayeringService.Resolution resolution = PreferenceLayeringService.resolve(
                List.of(english, chinese, termA, termB));

        assertThat(resolution.active()).extracting(PreferenceEntry::targetKey)
                .containsExactlyInAnyOrder("answer.language", "terminology.a", "terminology.b");
        assertThat(resolution.active()).filteredOn(entry -> "answer.language".equals(entry.targetKey()))
                .singleElement().satisfies(entry -> assertThat(entry.value()).isEqualTo("中文"));
        assertThat(resolution.superseded()).singleElement().satisfies(superseded -> {
            assertThat(superseded.entry().value()).isEqualTo("English");
            assertThat(superseded.reason()).isEqualTo("OVERRIDDEN_BY_THIS_TURN");
        });
    }

    @Test
    void sameLayerConflictKeepsLatestAndRecordsReason() {
        PreferenceEntry first = entry("format.table", PreferenceSlotFamily.FORMAT,
                "", "FORBID", PreferenceLayer.CONVERSATION_TOPIC, "acme", 1);
        PreferenceEntry latest = entry("format.table", PreferenceSlotFamily.FORMAT,
                "", "REQUIRE", PreferenceLayer.CONVERSATION_TOPIC, "acme", 2);

        PreferenceLayeringService.Resolution resolution = PreferenceLayeringService.resolve(
                List.of(first, latest));

        assertThat(resolution.active()).singleElement()
                .satisfies(entry -> assertThat(entry.polarity()).isEqualTo("REQUIRE"));
        assertThat(resolution.superseded()).singleElement()
                .satisfies(superseded -> assertThat(superseded.reason()).isEqualTo("SAME_LAYER_CONFLICT"));
    }

    @Test
    void compileEmitsSupportedParametersAndExplicitUnsupportedReasons() {
        List<PreferenceEntry> active = List.of(
                entry("answer.language", PreferenceSlotFamily.LANGUAGE, "中文", "REQUIRE",
                        PreferenceLayer.THIS_TURN, "acme", 1),
                entry("format.length.max", PreferenceSlotFamily.LENGTH, "300", "REQUIRE",
                        PreferenceLayer.LONG_TERM, "acme", 2),
                entry("format.table", PreferenceSlotFamily.FORMAT, "", "FORBID",
                        PreferenceLayer.THIS_TURN, "acme", 3),
                entry("terminology.a", PreferenceSlotFamily.TERMINOLOGY, "术语甲", "REQUIRE",
                        PreferenceLayer.LONG_TERM, "acme", 4),
                entry("format.unknown", PreferenceSlotFamily.FORMAT, "x", "REQUIRE",
                        PreferenceLayer.LONG_TERM, "acme", 5));

        PreferenceLayeringService.TaskParameters compiled = PreferenceLayeringService.compile(active);

        assertThat(compiled.parameters())
                .containsEntry("language", "中文")
                .containsEntry("max_chars", "300")
                .containsEntry("table", "false")
                .containsEntry("terminology", "术语甲");
        assertThat(compiled.unsupported()).singleElement().satisfies(unsupported -> {
            assertThat(unsupported.targetKey()).isEqualTo("format.unknown");
            assertThat(unsupported.reason()).isEqualTo("UNSUPPORTED_TARGET");
        });
    }

    @Test
    void invalidNumericPreferenceIsNotSilentlyDropped() {
        PreferenceEntry invalid = entry("format.length.max", PreferenceSlotFamily.LENGTH,
                "很多", "REQUIRE", PreferenceLayer.THIS_TURN, "acme", 1);

        PreferenceLayeringService.TaskParameters compiled = PreferenceLayeringService.compile(
                List.of(invalid));

        assertThat(compiled.parameters()).doesNotContainKey("max_chars");
        assertThat(compiled.unsupported()).singleElement()
                .satisfies(unsupported -> assertThat(unsupported.reason()).isEqualTo("INVALID_VALUE"));
    }

    private PreferenceEntry entry(String targetKey, PreferenceSlotFamily family, String value,
                                  String polarity, PreferenceLayer layer, String scopeKey, long sequence) {
        return new PreferenceEntry(targetKey, family, value, polarity, layer,
                "USER", scopeKey, "source-" + sequence, "v1", sequence);
    }
}
