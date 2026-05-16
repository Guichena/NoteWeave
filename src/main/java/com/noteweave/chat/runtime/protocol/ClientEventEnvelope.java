package com.noteweave.chat.runtime.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ClientEventEnvelope {

    private String event;
    private String requestId;
    private String streamId;
    private Long sessionId;
    private Long ack;
    private JsonNode payload;
}
