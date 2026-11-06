package com.noteweave.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 话题感知上下文开启时的后台准备：发消息接口不再冻结会话上下文，冻结在后台准备开始时完成，
 * 每一轮回答仍然有 READY 的冻结快照。父类的用例也在这个配置下再跑一遍。
 */
@SpringBootTest(properties = {
        "noteweave.answer.async-preparation=true",
        "noteweave.context.v2.shadow-enabled=true",
        "noteweave.context.v2.active-enabled=true",
        "noteweave.context.v2.default-mode=ACTIVE"
})
class AnswerAsyncPreparationContextV2ContractTest extends AnswerAsyncPreparationContractTest {

    @Test
    void contextIsFrozenInTheBackgroundForEveryTurn() throws Exception {
        String workspaceId = createWorkspace();
        String conversationId = createConversation(workspaceId);

        JsonNode first = submit(conversationId, Map.of(
                "content", "后台冻结上下文的第一个问题",
                "answer_mode", "QA",
                "client_request_id", "async-v2-1"), 200);
        awaitSubmissionLeavesPreparing(workspaceId, conversationId, "async-v2-1");
        JsonNode second = submit(conversationId, Map.of(
                "content", "接着上面的问题再展开一点",
                "answer_mode", "QA",
                "client_request_id", "async-v2-2"), 200);
        awaitSubmissionLeavesPreparing(workspaceId, conversationId, "async-v2-2");

        for (JsonNode receipt : new JsonNode[] {first, second}) {
            String runId = receipt.path("answer_run_id").asText();
            assertThat(jdbcTemplate.queryForObject(
                    "select status from answer_run where id = ?", String.class, runId)).isEqualTo("COMPLETED");
            assertThat(jdbcTemplate.queryForList(
                    "select status from context_v2_shadow_snapshot where answer_run_id = ?", String.class, runId))
                    .containsExactly("READY");
        }
    }
}
