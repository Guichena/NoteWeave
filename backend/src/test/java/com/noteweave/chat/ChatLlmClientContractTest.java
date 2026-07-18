package com.noteweave.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Modifier;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class ChatLlmClientContractTest {

    @Test
    void providersMustImplementOutputTokenBudgetAwareMethod() throws Exception {
        var method = ChatLlmClient.class.getMethod(
                "streamChat", String.class, String.class, int.class, Consumer.class);

        assertThat(method.isDefault()).isFalse();
        assertThat(Modifier.isAbstract(method.getModifiers())).isTrue();
    }
}
