package com.noteweave.source;

import com.noteweave.config.NoteWeaveProperties;
import org.springframework.stereotype.Component;

/**
 * 资料流水线 Kafka 主题名的唯一来源，全部取自配置 {@code noteweave.kafka.topics}。
 * <p>
 * 生产者和消费者必须使用同一份主题名：一旦某处退化成硬编码字面量或固定常量，
 * 改了配置之后按主题比较的分支（死信收尾、outbox 投递耗尽收尾、死信重驱）就会失效，
 * 资料和任务会一直停在处理中。{@link SourcePipelineStages} 中的常量只保留为缺省值。
 */
@Component
public class SourceTopicNames {

    /** 同步检索投影主题不是四个异步阶段之一，缺省值只作为配置缺失时的兜底。 */
    private static final String DEFAULT_RETRIEVAL_PROJECTION = "noteweave.retrieval.projection";

    private final String parse;
    private final String chunk;
    private final String embed;
    private final String index;
    private final String retrievalProjection;

    public SourceTopicNames(NoteWeaveProperties properties) {
        NoteWeaveProperties.Topics topics = properties.kafka().topics();
        this.parse = value(topics.sourceParse(), SourcePipelineStages.TOPIC_PARSE);
        this.chunk = value(topics.sourceChunk(), SourcePipelineStages.TOPIC_CHUNK);
        this.embed = value(topics.sourceEmbed(), SourcePipelineStages.TOPIC_EMBED);
        this.index = value(topics.sourceIndex(), SourcePipelineStages.TOPIC_INDEX);
        this.retrievalProjection = value(topics.retrievalProjection(), DEFAULT_RETRIEVAL_PROJECTION);
    }

    /** 解析阶段。 */
    public String parse() {
        return parse;
    }

    /** 切片阶段。 */
    public String chunk() {
        return chunk;
    }

    /** 向量化阶段。 */
    public String embed() {
        return embed;
    }

    /** 索引阶段。 */
    public String index() {
        return index;
    }

    /** Kafka 关闭时使用的单条同步检索投影主题。 */
    public String retrievalProjection() {
        return retrievalProjection;
    }

    /** 解析或切片主题：失败收尾把资料标记为解析失败。 */
    public boolean isParseStage(String topic) {
        return parse.equals(topic) || chunk.equals(topic);
    }

    /** 检索投影相关主题（向量化、索引、同步投影）：失败收尾把快照和任务标记为索引失败。 */
    public boolean isRetrievalProjection(String topic) {
        return topic != null
                && (retrievalProjection.equals(topic) || embed.equals(topic) || index.equals(topic));
    }

    private static String value(String configured, String fallback) {
        return configured == null || configured.isBlank() ? fallback : configured;
    }
}
