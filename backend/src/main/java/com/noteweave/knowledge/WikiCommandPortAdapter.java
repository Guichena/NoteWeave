package com.noteweave.knowledge;

import com.noteweave.source.SourceWikiCommandPort;
import com.noteweave.workspace.WorkspaceWikiCommandPort;
import org.springframework.stereotype.Component;

@Component
public class WikiCommandPortAdapter implements WorkspaceWikiCommandPort, SourceWikiCommandPort {

    private final WikiIngestService wikiIngestService;

    public WikiCommandPortAdapter(WikiIngestService wikiIngestService) {
        this.wikiIngestService = wikiIngestService;
    }

    @Override
    public void requestWorkspaceBackfill(String workspaceId, String reason) {
        wikiIngestService.enqueueAndRunWorkspaceIngestIfEnabled(workspaceId, reason);
    }

    @Override
    public void requestSourceIngest(String workspaceId, String sourceId) {
        wikiIngestService.enqueueAndRunSourceIngestIfEnabled(workspaceId, sourceId);
    }

    @Override
    public String requestSourceRetract(String workspaceId, String sourceId, String sourceTitle) {
        return wikiIngestService.enqueueAndRunSourceRetractIfEnabled(workspaceId, sourceId, sourceTitle);
    }
}
