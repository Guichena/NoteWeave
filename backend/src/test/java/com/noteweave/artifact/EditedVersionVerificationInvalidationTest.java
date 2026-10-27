package com.noteweave.artifact;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * NR23：用户编辑过的版本不得继承原正文的校验通过与引用指标。
 * 读模型对 verification_state=USER_EDITED_UNVERIFIED 的载荷返回显式失效状态；
 * 历史版本（无该标记）保留原诊断。
 */
class EditedVersionVerificationInvalidationTest {

    // 生产装配的 ObjectMapper 使用 snake_case 命名策略，测试保持同构
    private final ArtifactPayloadReadModelAssembler assembler =
            new ArtifactPayloadReadModelAssembler(new ObjectMapper()
                    .setPropertyNamingStrategy(com.fasterxml.jackson.databind.PropertyNamingStrategy.SNAKE_CASE));

    @Test
    void editedVersionLosesInheritedVerificationVerdict() {
        String payload = """
                {
                  "verification_state": "USER_EDITED_UNVERIFIED",
                  "edited_by_user": true,
                  "verification": {
                    "status": "PASS",
                    "contract_status": "PASS",
                    "citation_recall": 0.98,
                    "citation_precision": 0.95,
                    "sections": [
                      {"heading": "第一章", "support": "SUPPORTED"}
                    ]
                  }
                }
                """;
        var trace = assembler.readRuntimeTrace(payload);
        assertThat(trace).isNotNull();
        assertThat(trace.verification()).isNotNull();
        assertThat(trace.verification().status()).isEqualTo("EDITED_UNVERIFIED");
        assertThat(trace.verification().contractStatus()).isEqualTo("USER_EDITED_UNVERIFIED");
        // 旧指标（召回率/精确率/章节支撑）不得随编辑版本继续展示
        assertThat(trace.verification().citationRecall()).isNull();
        assertThat(trace.verification().citationPrecision()).isNull();
        assertThat(trace.verification().sections()).isEmpty();
        assertThat(trace.verification().passedChecks()).isEmpty();
    }

    @Test
    void historicalVersionsKeepTheirOriginalDiagnostics() {
        String payload = """
                {
                  "verification": {
                    "status": "PASS",
                    "contract_status": "PASS",
                    "citation_recall": 0.98,
                    "sections": [
                      {"heading": "第一章", "support": "SUPPORTED"}
                    ]
                  }
                }
                """;
        var trace = assembler.readRuntimeTrace(payload);
        assertThat(trace).isNotNull();
        assertThat(trace.verification().contractStatus()).isEqualTo("PASS");
        assertThat(trace.verification().citationRecall()).isEqualTo(0.98);
        assertThat(trace.verification().sections()).hasSize(1);
    }
}
