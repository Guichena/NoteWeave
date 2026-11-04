package com.noteweave.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class UnknownJsonPropertyAuditTest {

    record Payload(String retrievalMode) { }

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    /** 驼峰写法的可选字段仍被忽略（行为不变），但会按字段名计数，便于找出写错字段的调用方。 */
    @Test
    void unknownPropertyInRequestBodyIsCountedButStillIgnored() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.registerSingleton("meterRegistry", registry);
        ObjectMapper mapper = new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .addHandler(new UnknownJsonPropertyAudit.Handler(factory.getBeanProvider(MeterRegistry.class)));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(
                new MockHttpServletRequest("POST", "/api/v2/workspaces/w/research-runs")));

        Payload payload = mapper.readValue("{\"retrievalMode\":\"SOURCES_ONLY\"}", Payload.class);

        assertThat(payload.retrievalMode()).isNull();
        assertThat(registry.counter("noteweave.api.unknown_property", "property", "retrievalMode").count())
                .isEqualTo(1.0);
    }

    @Test
    void deserializationOutsideHttpRequestsIsNotCounted() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.registerSingleton("meterRegistry", registry);
        ObjectMapper mapper = new ObjectMapper()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .addHandler(new UnknownJsonPropertyAudit.Handler(factory.getBeanProvider(MeterRegistry.class)));

        mapper.readValue("{\"other\":1}", Payload.class);

        assertThat(registry.find("noteweave.api.unknown_property").counters()).isEmpty();
    }
}
