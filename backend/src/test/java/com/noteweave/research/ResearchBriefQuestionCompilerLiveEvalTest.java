package com.noteweave.research;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.chat.ChatLlmClient;
import com.noteweave.chat.LiveLlmEvalSupport;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * R0 独立问题编译的 live 评测：真实模型需要把“继续”补成带主语的研究问题。
 * 需要 DEEPSEEK_API_KEY 非空且 -Dnoteweave.live.llm=true。
 */
@EnabledIfEnvironmentVariable(named = "DEEPSEEK_API_KEY", matches = ".+")
@EnabledIfSystemProperty(named = "noteweave.live.llm", matches = "true")
class ResearchBriefQuestionCompilerLiveEvalTest {

    @Test
    void liveModelResolvesPronounIntoAnIndependentResearchQuestion() {
        ChatLlmClient client = LiveLlmEvalSupport.client();
        System.out.println("[r0 live] " + LiveLlmEvalSupport.describeTarget());
        List<Map<String, Object>> messages = List.of(
                Map.of("message_id", "m1", "role", "USER",
                        "content", "我们在比较 OpenSERP 和托管搜索的索引延迟"),
                Map.of("message_id", "m2", "role", "ASSISTANT",
                        "content", "OpenSERP 自建索引，托管搜索依赖第三方索引。"),
                Map.of("message_id", "m3", "role", "USER",
                        "content", "继续研究部署权衡"));

        ResearchBriefQuestionCompiler.Result result = ResearchBriefQuestionCompiler.compile(
                client, new ObjectMapper(), "w", "run-live",
                "继续研究部署权衡", messages);

        int passed = 0;
        int total = 3;
        if (result.researchQuestion().contains("OpenSERP") || result.researchQuestion().contains("部署")) {
            passed++;
        } else {
            System.out.println("[r0 live] question miss: " + result.researchQuestion());
        }
        if (!result.keyPoints().isEmpty()) {
            passed++;
        } else {
            System.out.println("[r0 live] no key points: " + result);
        }
        if (result.keyPoints().stream().allMatch(point ->
                point.get("source_message_id") != null && !String.valueOf(point.get("source_message_id")).isBlank())) {
            passed++;
        } else {
            System.out.println("[r0 live] key point without source: " + result.keyPoints());
        }

        System.out.println(LiveLlmEvalSupport.scoreLine("r0 live eval", passed, total));
        assertThat(passed).as("live R0 至少通过 2/3").isGreaterThanOrEqualTo(2);
    }
}
