package com.noteweave.context;

import static org.assertj.core.api.Assertions.assertThat;

import com.noteweave.chat.ChatLlmClient;
import com.noteweave.chat.LiveLlmEvalSupport;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * 会话前提抽取的 live 评测：同一抽取器换真实模型，看是否只抽用户自己的陈述。
 * 需要 DEEPSEEK_API_KEY 非空且 -Dnoteweave.live.llm=true；模型与地址沿用 LiveLlmEvalSupport。
 */
@EnabledIfEnvironmentVariable(named = "DEEPSEEK_API_KEY", matches = ".+")
@EnabledIfSystemProperty(named = "noteweave.live.llm", matches = "true")
class LlmConversationPremiseExtractorLiveEvalTest {

    @Test
    void liveModelKeepsUserStatementsAndDropsThirdPartyClaims() {
        ChatLlmClient client = LiveLlmEvalSupport.client();
        LlmConversationPremiseExtractor extractor = new LlmConversationPremiseExtractor(client);
        System.out.println("[premise live] " + LiveLlmEvalSupport.describeTarget());

        int passed = 0;
        int total = 3;

        List<PremiseOp> assertion = extractor.extract(
                "w", "c", "m1", 1, "我们团队用 Java 17，回答控制在三句以内");
        if (assertion.stream().anyMatch(op ->
                op.value().contains("Java") || op.value().contains("17")
                        || op.subjectKey().toLowerCase().contains("java"))) {
            passed++;
        } else {
            System.out.println("[premise live] assertion miss: " + assertion);
        }

        List<PremiseOp> thirdParty = extractor.extract(
                "w", "c", "m2", 2, "同事说他们团队用 Rust，我们不用管");
        if (thirdParty.stream().noneMatch(op -> op.value().contains("Rust"))) {
            passed++;
        } else {
            System.out.println("[premise live] third-party leaked: " + thirdParty);
        }

        List<PremiseOp> correction = extractor.extract(
                "w", "c", "m3", 3, "刚才说的语言改成 Kotlin");
        if (correction.stream().anyMatch(op -> op.value().contains("Kotlin"))) {
            passed++;
        } else {
            System.out.println("[premise live] correction miss: " + correction);
        }

        System.out.println(LiveLlmEvalSupport.scoreLine("premise live eval", passed, total));
        assertThat(passed).as("live 前提抽取至少通过 2/3").isGreaterThanOrEqualTo(2);
    }
}
