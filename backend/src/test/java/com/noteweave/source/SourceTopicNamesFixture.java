package com.noteweave.source;

import com.noteweave.config.NoteWeaveProperties;

/**
 * 构造 {@link SourceTopicNames} 的测试夹具：一套与 application.yml 缺省值一致，
 * 一套模拟部署侧改名，用来验证改配置之后按主题分支的逻辑仍然命中。
 */
public final class SourceTopicNamesFixture {

    private SourceTopicNamesFixture() {
    }

    /** 与缺省配置一致的主题名。 */
    public static SourceTopicNames defaults() {
        return new SourceTopicNames(new NoteWeaveProperties(null, null, null, null, null, null));
    }

    /** 只改资料流水线相关主题，其余主题保持自定义前缀，模拟部署侧整体改名。 */
    public static SourceTopicNames custom(String parse, String chunk, String embed, String index,
                                          String retrievalProjection) {
        return new SourceTopicNames(new NoteWeaveProperties(null, null, null,
                new NoteWeaveProperties.Kafka(true, new NoteWeaveProperties.Topics(
                        parse, retrievalProjection, "custom.wiki.ingest", "custom.wiki.retract",
                        "custom.conversation.summary", "custom.memory.extraction", chunk, embed, index)),
                null, null));
    }
}
