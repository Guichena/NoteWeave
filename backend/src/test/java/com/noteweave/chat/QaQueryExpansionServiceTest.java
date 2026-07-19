package com.noteweave.chat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class QaQueryExpansionServiceTest {
    private final QaQueryExpansionService service = new QaQueryExpansionService();

    @Test
    void expandsOrdinaryQuestionWithoutDisplayPrefixes() {
        assertThat(service.expand("How does durable checkpoint recovery work?"))
                .isNotEmpty()
                .contains("durable checkpoint recovery work");
    }

    @Test
    void preservesStructuredCurrentQuestionAndAnchorAsAdditionalVariant() {
        String query = "\u5f53\u524d\u95ee\u9898\uff1a\u5982\u4f55\u6062\u590d\u68c0\u67e5\u70b9\uff1f\n"
                + "\u4e3b\u9898\u951a\u70b9\uff1a\u6301\u4e45\u5316";

        assertThat(service.expand(query)).contains("\u6301\u4e45\u5316 \u5982\u4f55\u6062\u590d\u68c0\u67e5\u70b9\uff1f");
    }
}
