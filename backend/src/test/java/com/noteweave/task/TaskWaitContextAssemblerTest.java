package com.noteweave.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.common.BusinessException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TaskWaitContextAssemblerTest {

    private final TaskWaitContextAssembler assembler =
            new TaskWaitContextAssembler(new ObjectMapper());

    @Test
    void shouldProjectProviderWaitPayloadAndDerivedDeliveryCounts() {
        WaitContextResponse context = assembler.build(
                "WAITING_FOR_PROVIDER",
                """
                {
                  "provider_job": {
                    "provider_id": "p1",
                    "provider_delivery_attempts": [
                      {"ack_status": "FAILED", "dispatch_count": 1},
                      {"ack_status": "ACKNOWLEDGED", "dispatch_count": 2}
                    ]
                  },
                  "wait_reason": {
                    "status": "WAITING_FOR_PROVIDER",
                    "capabilities": ["web"],
                    "unavailable_capabilities": ["search"]
                  }
                }
                """
        );

        assertThat(context.status()).isEqualTo("WAITING_FOR_PROVIDER");
        assertThat(context.providerJob().providerId()).isEqualTo("p1");
        assertThat(context.providerJob().dispatchCount()).isEqualTo(2);
        assertThat(context.providerJob().previousFailedDeliveryCount()).isEqualTo(1);
        assertThat(context.providerJob().hasPreviousFailedDelivery()).isTrue();
        assertThat(context.waitReason().capabilities()).containsExactly("web");
        assertThat(context.waitReason().unavailableCapabilities()).containsExactly("search");
    }

    @Test
    void shouldFailClosedOnMalformedPayloadAndKeepMissingPayloadEmpty() {
        WaitContextResponse empty = assembler.build("WAITING_FOR_PROVIDER", null);
        assertThat(empty.providerJob().providerDeliveryAttempts()).isEmpty();
        assertThat(empty.waitReason().capabilities()).isEmpty();

        assertThatThrownBy(() -> assembler.build("WAITING_FOR_PROVIDER", "not-json"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("任务事件载荷解析失败");
    }

    @Test
    void shouldFailClosedOnMalformedNestedPayloadShapes() {
        assertThatThrownBy(() -> assembler.build("WAITING", "{\"provider_job\":\"not-an-object\"}"))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).code())
                .isEqualTo("TASK_EVENT_PAYLOAD_SHAPE_INVALID");
        assertThatThrownBy(() -> assembler.build("WAITING", """
                {"provider_job":{"provider_delivery_attempts":["not-an-object"]}}
                """))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).code())
                .isEqualTo("TASK_EVENT_PAYLOAD_SHAPE_INVALID");
        assertThatThrownBy(() -> assembler.build("WAITING", """
                {"provider_job":{"dispatch_count":"not-a-number"}}
                """))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).code())
                .isEqualTo("TASK_EVENT_PAYLOAD_SHAPE_INVALID");
    }

    @Test
    void shouldFillMissingWaitingContextsInBatchOrder() {
        Map<String, WaitContextResponse> contexts = assembler.buildAll(
                Map.of("task-1", "WAITING", "task-2", "WAITING"),
                List.of("task-1", "task-2"),
                Map.of("task-1", """
                        {"wait_reason":{"status":"WAITING"}}
                        """)
        );

        assertThat(contexts).containsKeys("task-1", "task-2");
        assertThat(contexts.get("task-2").providerJob().providerDeliveryAttempts()).isEmpty();
    }
}
