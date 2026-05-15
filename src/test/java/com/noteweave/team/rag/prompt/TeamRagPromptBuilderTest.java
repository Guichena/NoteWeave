package com.noteweave.team.rag.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import com.noteweave.chat.model.ChatMessage;
import com.noteweave.chat.model.ChatMessageRole;
import com.noteweave.team.rag.evidence.EvidenceItem;
import com.noteweave.team.rag.evidence.EvidenceSource;
import java.util.List;
import org.junit.jupiter.api.Test;

class TeamRagPromptBuilderTest {

    private final TeamRagPromptBuilder builder = new TeamRagPromptBuilder("暂无相关信息");

    @Test
    void shouldBuildPromptWithGuardrailsEvidenceAndHistory() {
        ChatMessage history = new ChatMessage();
        history.setRole(ChatMessageRole.USER);
        history.setContent("上一轮我们确认过要先做回滚演练。");

        EvidenceItem evidence = new EvidenceItem(
                1,
                10L,
                "部署手册",
                2,
                3,
                "部署前需要准备蓝绿环境，并先完成回滚演练。",
                0.97,
                List.of(new EvidenceSource(100L, 3, 12, 20, 45, "部署前需要准备蓝绿环境，并先完成回滚演练。", "2"))
        );

        PromptMessages prompt = builder.build("这个项目的部署流程是什么？", List.of(evidence), List.of(history));

        assertThat(prompt.messages()).hasSize(2);
        assertThat(prompt.messages().get(0).content())
                .contains("你是 NoteWeave 团队知识助手")
                .contains("如果资料不足，请说“暂无相关信息”")
                .contains("不要编造不存在的资料");
        assertThat(prompt.messages().get(1).content())
                .contains("上一轮我们确认过")
                .contains("[来源#1]")
                .contains("文档：部署手册")
                .contains("回滚演练")
                .contains("这个项目的部署流程是什么");
    }
}
