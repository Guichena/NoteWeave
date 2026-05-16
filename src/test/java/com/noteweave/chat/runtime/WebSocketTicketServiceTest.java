package com.noteweave.chat.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.noteweave.chat.runtime.service.WebSocketTicketService;
import com.noteweave.common.error.BusinessException;
import com.noteweave.common.error.ErrorCode;
import com.noteweave.support.ContainerizedIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class WebSocketTicketServiceTest extends ContainerizedIntegrationTest {

    @Autowired
    private WebSocketTicketService webSocketTicketService;

    @Test
    void shouldCreateAndConsumeTicketOnlyOnce() {
        String ticket = webSocketTicketService.createTicket(42L);

        assertThat(webSocketTicketService.consumeTicket(ticket)).isEqualTo(42L);
        assertThatThrownBy(() -> webSocketTicketService.consumeTicket(ticket))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode()).isEqualTo(ErrorCode.WS_TICKET_INVALID));
    }
}
