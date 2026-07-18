package com.noteweave.answer;

import com.noteweave.answer.strategy.EvidenceBundle;
import com.noteweave.answer.strategy.RetrievalPlan;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AnswerSubmissionService {

    private final AnswerRunService answerRunService;
    private final AnswerGenerationGateway generationGateway;

    public AnswerSubmissionService(
            AnswerRunService answerRunService,
            AnswerGenerationGateway generationGateway
    ) {
        this.answerRunService = answerRunService;
        this.generationGateway = generationGateway;
    }

    @Transactional
    public String createPreparedRun(
            String workspaceId,
            String conversationId,
            String mode,
            String queryMessageId,
            String answerMessageId,
            String assistantRequestId,
            String initialContent,
            RetrievalPlan retrievalPlan,
            EvidenceBundle evidenceBundle,
            String promptVersion,
            int maximumOutputTokens
    ) {
        String runId = answerRunService.createAndStartRetrieval(
                workspaceId, conversationId, mode, queryMessageId, retrievalPlan);
        answerRunService.prepareGeneration(
                workspaceId,
                runId,
                answerMessageId,
                assistantRequestId,
                initialContent,
                generationGateway.configuredModel(),
                evidenceBundle,
                promptVersion,
                maximumOutputTokens
        );
        return runId;
    }

    @Transactional
    public void prepareExistingRun(
            String workspaceId,
            String runId,
            String answerMessageId,
            String assistantRequestId,
            String initialContent,
            RetrievalPlan retrievalPlan,
            EvidenceBundle evidenceBundle,
            String promptVersion,
            int maximumOutputTokens
    ) {
        answerRunService.startPreparedRetrieval(workspaceId, runId, retrievalPlan);
        answerRunService.prepareGeneration(
                workspaceId,
                runId,
                answerMessageId,
                assistantRequestId,
                initialContent,
                generationGateway.configuredModel(),
                evidenceBundle,
                promptVersion,
                maximumOutputTokens
        );
    }
}
