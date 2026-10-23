package com.noteweave.support;

import static org.assertj.core.api.Assertions.assertThat;

import co.elastic.clients.elasticsearch._types.Conflicts;
import co.elastic.clients.elasticsearch.core.DeleteByQueryRequest;
import org.junit.jupiter.api.Test;

/** 校验测试清理对全局 Wiki 索引只按工作区删除文档，不删除整个索引。 */
class RetrievalIndexCleanupTest {

    @Test
    void wikiCleanupScopesDeleteByQueryToWorkspace() {
        RetrievalIndexCleanup cleanup = new RetrievalIndexCleanup(null, "noteweave-wiki");

        DeleteByQueryRequest request = cleanup.wikiDeleteRequest("ws-cleanup-1");

        assertThat(request.index()).containsExactly("noteweave-wiki");
        assertThat(request.refresh()).isTrue();
        assertThat(request.conflicts()).isEqualTo(Conflicts.Proceed);
        assertThat(request.allowNoIndices()).isTrue();
        assertThat(request.ignoreUnavailable()).isTrue();
        assertThat(request.query().isTerm()).isTrue();
        assertThat(request.query().term().field()).isEqualTo("workspace_id");
        assertThat(request.query().term().value().stringValue()).isEqualTo("ws-cleanup-1");
    }

    @Test
    void wikiCleanupUsesConfiguredIndexName() {
        RetrievalIndexCleanup cleanup = new RetrievalIndexCleanup(null, "noteweave-wiki-e85ed52f1faf2");

        DeleteByQueryRequest request = cleanup.wikiDeleteRequest("ws-cleanup-2");

        assertThat(request.index()).containsExactly("noteweave-wiki-e85ed52f1faf2");
    }

    @Test
    void blankWorkspaceIdSkipsCleanup() {
        RetrievalIndexCleanup cleanup = new RetrievalIndexCleanup(null);

        cleanup.deleteWorkspaceIndices(null);
        cleanup.deleteWorkspaceIndices("   ");
    }
}
