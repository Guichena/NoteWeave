package com.noteweave.search;

import java.util.List;

/**
 * Workspace-scoped full-text chunk search capability.
 *
 * <p>The application layer depends on this contract rather than a concrete
 * search engine. An unavailable implementation returns no hits so callers can
 * apply their explicit fallback strategy.</p>
 */
public interface ChunkSearchPort {

    List<ChunkSearchHit> search(String workspaceId, String query, int topK);
}
