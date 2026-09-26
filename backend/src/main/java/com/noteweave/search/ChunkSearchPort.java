package com.noteweave.search;

import java.util.List;

/**
 * Workspace-scoped full-text chunk search capability.
 *
 * <p>The application layer depends on this contract rather than a concrete
 * search engine. An unavailable implementation must raise a provider error;
 * an empty result is reserved for a successful search with no matches.</p>
 */
public interface ChunkSearchPort {

    List<ChunkSearchHit> search(String workspaceId, String query, int topK);
}
