package com.noteweave.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.answer.AnswerRunService;
import com.noteweave.chat.ChatService;
import com.noteweave.research.ResearchRunService;
import com.noteweave.security.AuditActorProvider;
import com.noteweave.security.WorkspaceAccessGuard;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

class ConversationTurnModuleFrozenCommandTest {

    private final ConversationTurnModule module = new ConversationTurnModule(
            mock(JdbcTemplate.class),
            new ObjectMapper(),
            mock(ChatService.class),
            mock(ResearchRunService.class),
            mock(ConversationMessageSequence.class),
            mock(AuditActorProvider.class),
            mock(AnswerRunService.class),
            mock(WorkspaceAccessGuard.class),
            mock(RunInputSnapshotService.class),
            mock(ConversationSegmentBuildService.class),
            mock(PlatformTransactionManager.class)
    );

    @Test
    void shouldRejectMissingTurnModeInsteadOfDefaultingToQa() {
        assertThatThrownBy(() -> invoke("{\"content\":\"original\",\"source_scope\":[]}"))
                .hasCauseInstanceOf(com.noteweave.common.BusinessException.class)
                .hasRootCauseMessage("Frozen turn preparation is invalid");
    }

    @Test
    void shouldRejectNonArraySourceScopeInsteadOfDefaultingToEmpty() {
        assertThatThrownBy(() -> invoke(
                "{\"content\":\"original\",\"requested_turn_mode\":\"NOTE\","
                        + "\"source_scope\":\"source-1\"}"))
                .hasCauseInstanceOf(com.noteweave.common.BusinessException.class)
                .hasRootCauseMessage("Frozen turn preparation is invalid");
    }

    @Test
    void shouldPreserveCompleteFrozenCommand() throws Exception {
        SubmitTurnCommand command = (SubmitTurnCommand) invoke(
                "{\"content\":\"original\",\"requested_turn_mode\":\"NOTE\","
                        + "\"source_scope\":[\"source-1\"],\"retrieval_strategy\":\"EXPLICIT\","
                        + "\"retrieval_channels\":[\"WORKSPACE\"],\"grounding_refs\":[\"ref-1\"]}"
        );

        assertThat(command.content()).isEqualTo("original");
        assertThat(command.requestedTurnMode()).isEqualTo("NOTE");
        assertThat(command.sourceScope()).containsExactly("source-1");
        assertThat(command.retrievalStrategy()).isEqualTo("EXPLICIT");
        assertThat(command.retrievalChannels()).containsExactly("WORKSPACE");
        assertThat(command.groundingRefs()).containsExactly("ref-1");
    }

    private Object invoke(String preparationJson) throws Exception {
        Method method = ConversationTurnModule.class.getDeclaredMethod(
                "readFrozenCommand", String.class, String.class);
        method.setAccessible(true);
        try {
            return method.invoke(module, "conversation-1", preparationJson);
        } catch (InvocationTargetException ex) {
            throw ex;
        }
    }
}
