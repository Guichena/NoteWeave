package com.noteweave.answer.strategy;

public interface EvidenceRetriever {

    String channel();

    EvidenceRetrievalResult retrieve(
            AnswerContext context,
            RetrievalPlan plan,
            RetrievalPlan.Step step
    );
}
