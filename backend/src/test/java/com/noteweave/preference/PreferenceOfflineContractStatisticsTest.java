package com.noteweave.preference;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * F5-01/02 合成离线契约统计：不是质量评测，只验证同一输入下的分层与参数编译行为稳定。
 * 数据全部合成，不冒充真实用户流量；真实模型质量评测仍未完成。
 */
class PreferenceOfflineContractStatisticsTest {

    @Test
    void twentySyntheticScenariosKeepTheSameLayeringContract() {
        int scenarios = 20;
        int overrides = 0;
        int terminologyPairsRetained = 0;
        int unsupportedReported = 0;
        for (int index = 0; index < scenarios; index++) {
            PreferenceEntry longTermLanguage = entry("answer.language", PreferenceSlotFamily.LANGUAGE,
                    "English", PreferenceLayer.LONG_TERM, "user-a", index * 10L + 1);
            PreferenceEntry turnLanguage = entry("answer.language", PreferenceSlotFamily.LANGUAGE,
                    "中文", PreferenceLayer.THIS_TURN, "user-a", index * 10L + 2);
            PreferenceEntry terminologyA = entry("terminology.a", PreferenceSlotFamily.TERMINOLOGY,
                    "术语甲", PreferenceLayer.LONG_TERM, "user-a", index * 10L + 3);
            PreferenceEntry terminologyB = entry("terminology.b", PreferenceSlotFamily.TERMINOLOGY,
                    "术语乙", PreferenceLayer.THIS_TURN, "user-a", index * 10L + 4);
            PreferenceEntry unsupported = entry("format.unknown", PreferenceSlotFamily.FORMAT,
                    "x", PreferenceLayer.THIS_TURN, "user-a", index * 10L + 5);

            PreferenceLayeringService.Resolution resolution = PreferenceLayeringService.resolve(
                    List.of(longTermLanguage, turnLanguage, terminologyA, terminologyB, unsupported));
            PreferenceLayeringService.TaskParameters parameters =
                    PreferenceLayeringService.compile(resolution.active());

            assertThat(parameters.parameters()).containsEntry("language", "中文");
            assertThat(parameters.parameters().get("terminology")).contains("术语甲", "术语乙");
            assertThat(parameters.unsupported()).singleElement()
                    .satisfies(item -> assertThat(item.reason()).isEqualTo("UNSUPPORTED_TARGET"));
            assertThat(resolution.superseded())
                    .anySatisfy(item -> assertThat(item.reason()).isEqualTo("OVERRIDDEN_BY_THIS_TURN"));
            overrides++;
            if (parameters.parameters().getOrDefault("terminology", "").contains("术语甲")
                    && parameters.parameters().get("terminology").contains("术语乙")) {
                terminologyPairsRetained++;
            }
            unsupportedReported += parameters.unsupported().size();
        }
        assertThat(overrides).isEqualTo(scenarios);
        assertThat(terminologyPairsRetained).isEqualTo(scenarios);
        assertThat(unsupportedReported).isEqualTo(scenarios);
        List<String> report = new ArrayList<>();
        report.add("synthetic_scenarios=" + scenarios);
        report.add("quality_evaluation=NOT_RUN");
        assertThat(report).contains("quality_evaluation=NOT_RUN");
    }

    private PreferenceEntry entry(String key, PreferenceSlotFamily family, String value,
                                  PreferenceLayer layer, String scope, long sequence) {
        return new PreferenceEntry(key, family, value, "REQUIRE", layer,
                "USER", scope, "source-" + sequence, "v1", sequence);
    }
}
