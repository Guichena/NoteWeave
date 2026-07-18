package com.noteweave.answer.strategy;

public interface AnswerModeStrategy {

    AnswerMode supports();

    RetrievalPlan plan(AnswerContext context);

    PromptSpec compose(AnswerContext context, EvidenceBundle evidenceBundle);

    AnswerPolicy policy();
}
