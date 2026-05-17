package com.noteweave.team.rag.retriever;

import java.util.List;

public interface Retriever {

    String name();

    List<RetrievalHit> retrieve(TeamRetrievalQuery query);
}
