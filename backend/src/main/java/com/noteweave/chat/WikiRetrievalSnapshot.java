package com.noteweave.chat;

import com.noteweave.knowledge.WikiPageContext;
import java.util.List;

public record WikiRetrievalSnapshot(
        List<WikiPageContext> contexts,
        List<String> citationIds
) {
    public WikiRetrievalSnapshot {
        contexts = contexts == null ? List.of() : List.copyOf(contexts);
        citationIds = citationIds == null ? List.of() : List.copyOf(citationIds);
    }
}
