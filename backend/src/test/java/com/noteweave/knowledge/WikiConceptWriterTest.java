package com.noteweave.knowledge;

import com.noteweave.chat.ChatLlmClient;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 概念页摘要只接受引用给定资料的一段正文；已有摘要的页面能读回摘要正文。 */
class WikiConceptWriterTest {

    @Test
    void summaryCitingGivenSourcesIsAcceptedAndOtherOutputIsDropped() {
        ChatLlmClient llm = mock(ChatLlmClient.class);
        when(llm.isEnabled()).thenReturn(true);
        when(llm.streamChat(anyString(), anyString(), anyInt(), any()))
                .thenReturn("Control Pack 是按运行类型编译出的记忆约束集合 [[memory.md]]。");
        WikiConceptWriter writer = new WikiConceptWriter(llm);

        String summary = writer.summarize("Control Pack",
                List.of(new WikiConceptWriter.Excerpt("memory.md", "最终产物是按运行类型编译的 Control Pack")));

        assertThat(summary).isEqualTo("Control Pack 是按运行类型编译出的记忆约束集合 [[memory.md]]。");
        assertThat(WikiConceptWriter.accepted("说明 [[不存在的资料]]。", List.of("memory.md"))).isEmpty();
        assertThat(WikiConceptWriter.accepted("## 标题\n正文", List.of("memory.md"))).isEmpty();
        assertThat(WikiConceptWriter.disabled().summarize("x", List.of(new WikiConceptWriter.Excerpt("a", "b"))))
                .isEmpty();
    }

    @Test
    void conceptSummaryIsReadBackButTheFixedMarkerIsNotTreatedAsASummary() {
        String written = "# Control Pack\n\n## 概念摘要\n\nControl Pack 是记忆约束集合。\n\n## 相关资料\n\n- [[memory.md]]\n";
        String legacy = "# Control Pack\n\n## 概念摘要\n\n" + WikiIngestService.CONCEPT_PAGE_MARKER
                + "，与 `Control Pack` 相关的资料页和片段会持续回流到这里。\n\n## 相关资料\n";

        assertThat(WikiIngestService.conceptSummary(written)).isEqualTo("Control Pack 是记忆约束集合。");
        assertThat(WikiIngestService.conceptSummary(legacy)).isEmpty();
    }
}
