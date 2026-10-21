package com.noteweave.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Properties;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.KafkaListenerEndpoint;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * M3-M09：{@code noteweave.kafka.instance-id} 为空/空白时不得把成员 ID 写入
 * {@code group.instance.id}，否则同一消费组内的实例会被当成同一个静态成员互相替换。
 */
class KafkaGroupInstanceIdTest {

    @Test
    void blankInstanceIdDoesNotSetGroupInstanceId() {
        assertThat(consumerPropertiesFor(null, "source-embed"))
                .doesNotContainKey(ConsumerConfig.GROUP_INSTANCE_ID_CONFIG);
        assertThat(consumerPropertiesFor("", "source-embed"))
                .doesNotContainKey(ConsumerConfig.GROUP_INSTANCE_ID_CONFIG);
        assertThat(consumerPropertiesFor("   ", "source-embed"))
                .doesNotContainKey(ConsumerConfig.GROUP_INSTANCE_ID_CONFIG);
    }

    @Test
    void configuredInstanceIdSetsStableGroupInstanceId() {
        Properties sourceEmbed = consumerPropertiesFor("backend-first", "source-embed");
        Properties sourceIndex = consumerPropertiesFor("backend-first", "source-index");

        assertThat(sourceEmbed.getProperty(ConsumerConfig.GROUP_INSTANCE_ID_CONFIG))
                .isEqualTo("backend-first-source-embed");
        assertThat(sourceIndex.getProperty(ConsumerConfig.GROUP_INSTANCE_ID_CONFIG))
                .isEqualTo("backend-first-source-index");
    }

    @Test
    void staticMemberIdOrNullIsNullForBlankInstanceId() {
        assertThat(configWithInstanceId(null).staticMemberIdOrNull("source-embed")).isNull();
        assertThat(configWithInstanceId(" ").staticMemberIdOrNull("source-embed")).isNull();
        assertThat(configWithInstanceId("backend-first").staticMemberIdOrNull("source-embed"))
                .isEqualTo("backend-first-source-embed");
    }

    @SuppressWarnings("unchecked")
    private Properties consumerPropertiesFor(String instanceId, String listenerId) {
        KafkaConfig config = configWithInstanceId(instanceId);
        ObjectProvider<MeterRegistry> meters = mock(ObjectProvider.class);
        when(meters.getIfAvailable(any())).thenReturn(new SimpleMeterRegistry());
        ConcurrentKafkaListenerContainerFactory<String, String> factory = config.kafkaListenerContainerFactory(
                config.consumerFactory(), mock(KafkaTemplate.class), meters, mock(ObjectProvider.class));

        KafkaListenerEndpoint endpoint = mock(KafkaListenerEndpoint.class);
        when(endpoint.getId()).thenReturn(listenerId);
        when(endpoint.getGroupId()).thenReturn("shared-backend-group");
        when(endpoint.getConcurrency()).thenReturn(1);
        when(endpoint.getTopics()).thenReturn(List.of("test." + listenerId));
        when(endpoint.getConsumerProperties()).thenReturn(new Properties());

        return factory.createListenerContainer(endpoint)
                .getContainerProperties().getKafkaConsumerProperties();
    }

    private KafkaConfig configWithInstanceId(String instanceId) {
        KafkaConfig config = new KafkaConfig();
        ReflectionTestUtils.setField(config, "bootstrapServers", "localhost:9092");
        ReflectionTestUtils.setField(config, "groupId", "backend");
        ReflectionTestUtils.setField(config, "securityProtocol", "PLAINTEXT");
        ReflectionTestUtils.setField(config, "instanceId", instanceId);
        return config;
    }
}
