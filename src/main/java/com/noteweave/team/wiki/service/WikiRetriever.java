package com.noteweave.team.wiki.service;

import com.noteweave.team.rag.retriever.RetrievalHit;
import com.noteweave.team.rag.retriever.Retriever;
import com.noteweave.team.rag.retriever.TeamRetrievalQuery;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class WikiRetriever implements Retriever {

    private final SearchIndexWikiSupport searchIndexWikiSupport;

    @Override
    public String name() {
        return "Wiki";
    }

    @Override
    public List<RetrievalHit> retrieve(TeamRetrievalQuery query) {
        if (query == null || !query.includeWiki()) {
            return List.of();
        }
        return searchIndexWikiSupport.search(query.spaceId(), query.query(), query.topK()).stream()
                .map(hit -> RetrievalHit.builder()
                        .retrieverName(name())
                        .chunkId(-hit.wikiPageId())
                        .documentId(hit.wikiPageId())
                        .knowledgeBaseId(0L)
                        .spaceId(query.spaceId())
                        .chunkIndex(0)
                        .documentTitle(hit.title())
                        .content(hit.content())
                        .score(hit.score())
                        .metadata(Map.of(
                                "indexVersion", 0,
                                "sourceType", "WIKI_PAGE",
                                "sourceId", hit.wikiPageId(),
                                "publishedVersionId", hit.publishedVersionId()
                        ))
                        .build())
                .toList();
    }
}
