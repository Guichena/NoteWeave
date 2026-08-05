package com.noteweave.infra;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.RetryListener;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.BackOffExecution;

/**
 * Kafka 基础配置。
 * <p>
 * 启用条件：{@code noteweave.kafka.enabled=true}（默认 true）。
 * 当设为 false 时，整个异步管道退回为"任务表 + 同步调用"，保持原行为。
 */
@Configuration
@EnableKafka
@ConditionalOnProperty(name = "noteweave.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class KafkaConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${spring.kafka.consumer.group-id:noteweave-backend}")
    private String groupId;

    @Value("${spring.kafka.properties.security.protocol:PLAINTEXT}")
    private String securityProtocol;

    @Value("${spring.kafka.properties.sasl.mechanism:PLAIN}")
    private String saslMechanism;

    @Value("${KAFKA_SASL_USERNAME:}")
    private String saslUsername;

    @Value("${KAFKA_SASL_PASSWORD:}")
    private String saslPassword;

    @Bean
    public KafkaAdmin kafkaAdmin() {
        Map<String, Object> configs = new HashMap<>();
        configs.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        applySecurity(configs);
        return new KafkaAdmin(configs);
    }

    @Bean
    public KafkaAdmin.NewTopics kafkaDeadLetterTopics(
            @Value("${noteweave.kafka.topics.source-parse:noteweave.source.parse}") String sourceParseTopic,
            @Value("${noteweave.kafka.topics.retrieval-projection:noteweave.retrieval.projection}") String retrievalProjectionTopic,
            @Value("${noteweave.kafka.topics.wiki-ingest:noteweave.wiki.ingest}") String wikiIngestTopic,
            @Value("${noteweave.kafka.topics.wiki-retract:noteweave.wiki.retract}") String wikiRetractTopic,
            @Value("${noteweave.kafka.topics.conversation-summary:noteweave.conversation.summary}") String conversationSummaryTopic
    ) {
        return new KafkaAdmin.NewTopics(
                deadLetterTopic(sourceParseTopic),
                deadLetterTopic(retrievalProjectionTopic),
                deadLetterTopic(wikiIngestTopic),
                deadLetterTopic(wikiRetractTopic),
                deadLetterTopic(conversationSummaryTopic)
        );
    }

    private NewTopic deadLetterTopic(String sourceTopic) {
        return TopicBuilder.name(sourceTopic + ".DLT").partitions(1).replicas(1).build();
    }

    @Bean
    public ProducerFactory<String, String> producerFactory() {
        Map<String, Object> configs = new HashMap<>();
        configs.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        configs.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        configs.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        configs.put(ProducerConfig.ACKS_CONFIG, "all");
        applySecurity(configs);
        return new DefaultKafkaProducerFactory<>(configs);
    }

    @Bean
    public KafkaTemplate<String, String> kafkaTemplate(ProducerFactory<String, String> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }

    @Bean
    public KafkaMessagePublisher kafkaMessagePublisher(KafkaTemplate<String, String> kafkaTemplate) {
        return (topic, messageKey, payloadJson) -> {
            try {
                kafkaTemplate.send(new ProducerRecord<>(topic, messageKey, payloadJson))
                        .get(10, TimeUnit.SECONDS);
            } catch (Exception exception) {
                throw new IllegalStateException("Kafka publish failed for topic " + topic, exception);
            }
        };
    }

    @Bean
    public ConsumerFactory<String, String> consumerFactory() {
        Map<String, Object> configs = new HashMap<>();
        configs.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        configs.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        configs.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        configs.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        configs.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        configs.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        applySecurity(configs);
        return new DefaultKafkaConsumerFactory<>(configs);
    }

    private void applySecurity(Map<String, Object> configs) {
        configs.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, securityProtocol);
        if (securityProtocol != null && securityProtocol.toUpperCase().startsWith("SASL")) {
            if (saslUsername == null || saslUsername.isBlank() || saslPassword == null || saslPassword.isBlank()) {
                throw new IllegalStateException("SASL Kafka requires KAFKA_SASL_USERNAME and KAFKA_SASL_PASSWORD");
            }
            configs.put(SaslConfigs.SASL_MECHANISM, saslMechanism);
            configs.put(SaslConfigs.SASL_JAAS_CONFIG,
                    "org.apache.kafka.common.security.plain.PlainLoginModule required username=\""
                            + escapeJaas(saslUsername) + "\" password=\"" + escapeJaas(saslPassword) + "\";");
        }
    }

    private String escapeJaas(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory,
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectProvider<MeterRegistry> meterRegistryProvider,
            ObjectProvider<KafkaDeadLetterTaskRecovery> taskRecoveryProvider) {
        MeterRegistry meterRegistry = meterRegistryProvider.getIfAvailable(SimpleMeterRegistry::new);
        ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        DeadLetterPublishingRecoverer publishingRecoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, exception) -> new TopicPartition(record.topic() + ".DLT", -1)
        );
        ConsumerRecordRecoverer recoverer = (record, exception) -> {
            publishingRecoverer.accept(record, exception);
            taskRecoveryProvider.ifAvailable(recovery -> recovery.recover(record, exception));
        };
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, exponentialJitterBackOff());
        errorHandler.setCommitRecovered(true);
        errorHandler.setRetryListeners(new RetryListener() {
            @Override
            public void failedDelivery(ConsumerRecord<?, ?> record, Exception ex, int deliveryAttempt) {
                meterRegistry.counter("noteweave.kafka.consumer.retry", "topic", record.topic()).increment();
            }

            @Override
            public void recovered(ConsumerRecord<?, ?> record, Exception ex) {
                meterRegistry.counter("noteweave.kafka.consumer.dlt", "topic", record.topic()).increment();
            }

            @Override
            public void recoveryFailed(ConsumerRecord<?, ?> record, Exception original, Exception failure) {
                meterRegistry.counter("noteweave.kafka.consumer.dlt_publish_failure", "topic", record.topic()).increment();
            }
        });
        factory.setCommonErrorHandler(errorHandler);
        return factory;
    }

    private BackOff exponentialJitterBackOff() {
        return () -> new BackOffExecution() {
            private int retry;

            @Override
            public long nextBackOff() {
                if (retry >= 2) {
                    return STOP;
                }
                long baseMillis = 1_000L << retry++;
                long maxJitterMillis = Math.max(1L, baseMillis / 5L);
                return baseMillis + ThreadLocalRandom.current().nextLong(maxJitterMillis + 1L);
            }
        };
    }
}
