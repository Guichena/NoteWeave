package com.noteweave.answer;

import java.util.List;
import java.util.function.Consumer;

/** Chat-facing data/model port used by the AnswerRun application flow. */
public interface AnswerGenerationGateway {

    AnswerGenerationMaterial load(String assistantRequestId);

    String prepareDraft(String storedContent);

    String generate(String draft, int maximumOutputTokens, Consumer<String> tokenConsumer);

    List<String> replayChunks(String content);

    void persistContent(String workspaceId, String messageId, String content);

    String configuredModel();
}
