package com.noteweave.answer;

import com.noteweave.answer.generation.GenerationContract;
import java.util.List;
import java.util.function.Consumer;

/** Chat-facing data/model port used by the AnswerRun application flow. */
public interface AnswerGenerationGateway {

    AnswerGenerationMaterial load(String assistantRequestId);

    String prepareDraft(String storedContent);

    /**
     * 按冻结的生成契约生成正文。
     *
     * @param contract 冻结的生成契约；历史运行没有契约时为 null，按旧协议处理
     */
    String generate(
            GenerationContract contract,
            String draft,
            int maximumOutputTokens,
            Consumer<String> tokenConsumer
    );

    default String generateInConversation(
            String workspaceId, String conversationId, GenerationContract contract, String draft,
            int maximumOutputTokens, Consumer<String> tokenConsumer
    ) {
        return generate(contract, draft, maximumOutputTokens, tokenConsumer);
    }

    List<String> replayChunks(String content);

    void persistContent(String workspaceId, String messageId, String content);

    String configuredModel();
}
