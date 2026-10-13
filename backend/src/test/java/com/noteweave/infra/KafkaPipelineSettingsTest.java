package com.noteweave.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.noteweave.config.NoteWeaveProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.config.KafkaListenerEndpoint;
import org.springframework.kafka.core.KafkaTemplate;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KafkaPipelineSettingsTest {
    @Test
    void consumerHasOneRecordAndFinitePollBudget() {
        KafkaConfig config = configured();
        var values = config.consumerFactory().getConfigurationProperties();
        assertThat(values.get(ConsumerConfig.MAX_POLL_RECORDS_CONFIG)).isEqualTo(1);
        assertThat(values.get(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG)).isEqualTo(1_800_000);
        assertThat(values).doesNotContainKey(ConsumerConfig.GROUP_INSTANCE_ID_CONFIG);
    }

    @Test
    void listenersAndDeploymentInstancesHaveDifferentStableMembers() {
        KafkaConfig first = configured();
        KafkaConfig second = configured();
        ReflectionTestUtils.setField(second, "instanceId", "backend-second");
        assertThat(first.staticMemberId("source-embed")).isEqualTo("backend-first-source-embed");
        assertThat(first.staticMemberId("source-index")).isNotEqualTo(first.staticMemberId("source-embed"));
        assertThat(second.staticMemberId("source-embed")).isNotEqualTo(first.staticMemberId("source-embed"));
        ReflectionTestUtils.setField(first, "instanceId", " ");
        assertThatThrownBy(() -> first.staticMemberId("source-embed")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void listenerFactoryAppliesUniqueStaticMembersToActualContainers() {
        KafkaConfig config = configured();
        ObjectProvider<MeterRegistry> meters = mock(ObjectProvider.class);
        when(meters.getIfAvailable(any())).thenReturn(new SimpleMeterRegistry());
        var factory = config.kafkaListenerContainerFactory(config.consumerFactory(), mock(KafkaTemplate.class),
                meters, mock(ObjectProvider.class));
        for (String id : new String[]{"source-embed", "source-index"}) {
            KafkaListenerEndpoint endpoint = mock(KafkaListenerEndpoint.class);
            when(endpoint.getId()).thenReturn(id);
            when(endpoint.getGroupId()).thenReturn("shared-backend-group");
            when(endpoint.getConcurrency()).thenReturn(1);
            when(endpoint.getTopics()).thenReturn(java.util.List.of("test." + id));
            when(endpoint.getConsumerProperties()).thenReturn(new java.util.Properties());
            var container = factory.createListenerContainer(endpoint);
            assertThat(container.getContainerProperties().getKafkaConsumerProperties()
                    .getProperty(ConsumerConfig.GROUP_INSTANCE_ID_CONFIG)).isEqualTo("backend-first-" + id);
        }
    }

    @Test
    void pollBudgetCoversConfiguredRequestBoundsAndRejectsOverflow() {
        KafkaConfig config = configured();
        NoteWeaveProperties properties = mock(NoteWeaveProperties.class);
        when(properties.llm()).thenReturn(new NoteWeaveProperties.Llm(true, "http://localhost", "test", "", 60));
        when(properties.embedding()).thenReturn(new NoteWeaveProperties.Embedding(
                true, "http://localhost", "test", "", 3, 1, 10, 60, 12000, 3));
        ReflectionTestUtils.setField(config, "pipelineProperties", properties);
        assertThat(config.effectiveMaxPollIntervalMs()).isEqualTo(12_915_000);
        assertThat(com.noteweave.source.SourcePipelineProcessingBudget.embeddingLeaseSeconds(
                properties, 1_800_000, 7200)).isEqualTo(12_975);
        when(properties.embedding()).thenReturn(new NoteWeaveProperties.Embedding(
                true, "http://localhost", "test", "", 3, 1, 10, Long.MAX_VALUE, 12000, 3));
        assertThatThrownBy(config::effectiveMaxPollIntervalMs).isInstanceOf(IllegalArgumentException.class);
    }

    private KafkaConfig configured() {
        KafkaConfig config = new KafkaConfig();
        ReflectionTestUtils.setField(config, "bootstrapServers", "localhost:9092");
        ReflectionTestUtils.setField(config, "groupId", "backend");
        ReflectionTestUtils.setField(config, "securityProtocol", "PLAINTEXT");
        ReflectionTestUtils.setField(config, "instanceId", "backend-first");
        return config;
    }
}
