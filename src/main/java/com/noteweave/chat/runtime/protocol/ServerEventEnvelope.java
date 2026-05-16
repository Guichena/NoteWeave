package com.noteweave.chat.runtime.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ServerEventEnvelope {

    private String event;
    private String requestId;
    private String streamId;
    private Long sessionId;
    private Long messageId;
    private Long seq;
    private Long ack;
    private JsonNode payload;
    private ErrorPayload error;

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ErrorPayload {
        private String code;
        private String message;
    }
}
