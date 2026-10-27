package com.noteweave.memory;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * MEM-03：记忆信号的主键由业务引用派生，重复投递靠主键冲突去重。
 *
 * <p>旧实现是「先 count 再 insert」，两个并发投递都能通过检查，于是同一条消息写出两条信号、
 * 两个候选、两条记忆。主键派生让第二条插入必然撞车。
 */
class MemorySignalIdentityTest {

    @Test
    void sameSourceRefAlwaysYieldsTheSameSignalId() {
        String first = MemoryConversationExtractionService.derivedSignalId("conversation-message:m-1:0");
        String second = MemoryConversationExtractionService.derivedSignalId("conversation-message:m-1:0");

        assertThat(first).isEqualTo(second).hasSize(36);
    }

    @Test
    void differentSequenceOrSourceYieldsADifferentSignalId() {
        String base = MemoryConversationExtractionService.derivedSignalId("conversation-message:m-1:0");

        assertThat(MemoryConversationExtractionService.derivedSignalId("conversation-message:m-1:1"))
                .isNotEqualTo(base);
        assertThat(MemoryConversationExtractionService.derivedSignalId("conversation-message:m-2:0"))
                .isNotEqualTo(base);
        assertThat(MemoryConversationExtractionService.derivedSignalId("artifact-edit:e-1:0"))
                .isNotEqualTo(base);
    }
}
