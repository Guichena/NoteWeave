package com.noteweave.research;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.chat.ChatLlmClient;
import com.noteweave.chat.LiveLlmEvalSupport;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * 第七期增量采集的 live 评测：只对重开的一个单元格调用一次真实模型，
 * 要求返回可解析的结构化值，并记录本次调用耗时。同一份四格矩阵全量重跑需要按格调用，
 * 这里用真实模型分别跑 4 格全量和 1 格增量，记录调用数与墙钟时间。
 * 需要 DEEPSEEK_API_KEY 非空且 -Dnoteweave.live.llm=true。
 */
@EnabledIfEnvironmentVariable(named = "DEEPSEEK_API_KEY", matches = ".+")
@EnabledIfSystemProperty(named = "noteweave.live.llm", matches = "true")
class ResearchIncrementalCellLiveEvalTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SYSTEM_PROMPT =
            "你是研究单元格采集器。只输出一个 JSON 对象，不要 Markdown："
                    + "{\"value\":\"...\",\"confidence\":0.0,\"quote\":\"...\"}。"
                    + "value 不超过 80 字；quote 必须是给定资料里的原句。";

    @Test
    void liveModelFillsOneReopenedCellWithStructuredValue() throws Exception {
        ChatLlmClient client = LiveLlmEvalSupport.client();
        System.out.println("[incremental cell live] " + LiveLlmEvalSupport.describeTarget());

        long started = System.currentTimeMillis();
        JsonNode node = callCell(client, "AWS", "部署方式",
                "AWS 使用托管容器服务，用户不直接管理服务器。");
        long elapsedMs = System.currentTimeMillis() - started;

        String value = node.path("value").asText("").strip();
        double confidence = node.path("confidence").asDouble(-1.0);
        String quote = node.path("quote").asText("").strip();

        assertThat(value).as("单元格值不能为空").isNotBlank();
        assertThat(value).as("应回答部署方式").containsAnyOf("容器", "托管", "服务器");
        assertThat(confidence).as("置信度在 0..1").isBetween(0.0, 1.0);
        assertThat(quote).as("引用不能为空").isNotBlank();

        System.out.println("[incremental cell live] mode=incremental calls=1 elapsed_ms=" + elapsedMs
                + " value=" + value + " confidence=" + confidence);
    }

    @Test
    void liveFullRerunCallsModelOncePerCell() throws Exception {
        ChatLlmClient client = LiveLlmEvalSupport.client();
        List<String[]> cells = List.of(
                new String[]{"AWS", "部署方式", "AWS 使用托管容器服务，用户不直接管理服务器。"},
                new String[]{"Azure", "部署方式", "Azure 提供托管应用服务，也支持虚拟机自管。"},
                new String[]{"AWS", "成本", "AWS 按用量计费，预留实例可降低长期成本。"},
                new String[]{"Azure", "成本", "Azure 按用量计费，预留容量可降低长期成本。"});

        long started = System.currentTimeMillis();
        for (String[] cell : cells) {
            JsonNode node = callCell(client, cell[0], cell[1], cell[2]);
            assertThat(node.path("value").asText("")).isNotBlank();
        }
        long elapsedMs = System.currentTimeMillis() - started;

        System.out.println("[incremental cell live] mode=full_rerun calls=" + cells.size()
                + " elapsed_ms=" + elapsedMs);
        assertThat(cells.size()).isEqualTo(4);
    }

    private JsonNode callCell(ChatLlmClient client, String object, String dimension, String material)
            throws Exception {
        String output = client.streamChat(
                SYSTEM_PROMPT,
                "研究问题：比较 AWS 和 Azure 的部署方式与成本。\n"
                        + "对象：" + object + "\n维度：" + dimension + "\n"
                        + "资料：" + material + "\n只输出 JSON。",
                200, token -> { });
        return MAPPER.readTree(stripFence(output));
    }

    private static String stripFence(String output) {
        String text = output == null ? "" : output.strip();
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            int lastFence = text.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                return text.substring(firstNewline + 1, lastFence).strip();
            }
        }
        return text;
    }
}
