package com.noteweave.support;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Conflicts;
import co.elastic.clients.elasticsearch.core.DeleteByQueryRequest;
import co.elastic.clients.elasticsearch.core.DeleteByQueryResponse;
import co.elastic.clients.elasticsearch.indices.GetIndexResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

/** 清理测试工作区写入真实 ES 的检索索引，避免多次运行累积索引造成 ES 内存压力。 */
public class RetrievalIndexCleanup {
    private static final Logger log = LoggerFactory.getLogger(RetrievalIndexCleanup.class);
    private static final String DEFAULT_WIKI_INDEX = "noteweave-wiki";
    private static final String WIKI_INDEX_PROPERTY = "noteweave.knowledge.wiki-index-name";
    private static final String WIKI_INDEX_ENV = "NOTEWEAVE_WIKI_INDEX_NAME";

    private final ElasticsearchClient client;
    private final String wikiIndexName;

    /** 供直接 new 的测试使用：按系统属性、环境变量或默认值解析 Wiki 索引名。 */
    public RetrievalIndexCleanup(ElasticsearchClient client) {
        this(client, resolveWikiIndexName());
    }

    /** Spring 上下文注入 Wiki 索引名，与生产配置保持一致。 */
    @Autowired
    public RetrievalIndexCleanup(
            ElasticsearchClient client,
            @Value("${noteweave.knowledge.wiki-index-name:noteweave-wiki}") String wikiIndexName
    ) {
        this.client = client;
        this.wikiIndexName = normalizeWikiIndexName(wikiIndexName);
    }

    /** 删除指定工作区的 QA、Note 检索索引，以及全局 Wiki 索引中的工作区文档。 */
    public void deleteWorkspaceIndices(String workspaceId) {
        if (workspaceId == null || workspaceId.isBlank()) {
            return;
        }
        deletePattern("noteweave_qa_chunk_*" + workspaceId + "*");
        deletePattern("noteweave_note_source_*" + workspaceId + "*");
        deleteWikiDocuments(workspaceId);
    }

    /**
     * Wiki 索引是全局单索引 noteweave-wiki，文档 id 为 workspaceId:itemId。
     * 这里按 workspace_id 字段删除文档，不删除索引本身，避免影响其他工作区。
     */
    private void deleteWikiDocuments(String workspaceId) {
        if (client == null) {
            return;
        }
        try {
            boolean exists = client.indices()
                    .exists(request -> request.index(wikiIndexName))
                    .value();
            if (!exists) {
                return;
            }
            DeleteByQueryResponse response = client.deleteByQuery(wikiDeleteRequest(workspaceId));
            if (response != null && response.deleted() > 0) {
                log.info("清理测试 Wiki 索引文档: index={}, workspaceId={}, deleted={}",
                        wikiIndexName, workspaceId, response.deleted());
            }
        } catch (IOException | RuntimeException ex) {
            log.warn("清理测试 Wiki 索引文档失败: index={}, workspaceId={}", wikiIndexName, workspaceId, ex);
        }
    }

    /** 构造按 workspace_id 精确匹配的删除请求，便于单元测试校验清理范围。 */
    DeleteByQueryRequest wikiDeleteRequest(String workspaceId) {
        return DeleteByQueryRequest.of(delete -> delete
                .index(wikiIndexName)
                .allowNoIndices(true)
                .ignoreUnavailable(true)
                .conflicts(Conflicts.Proceed)
                .refresh(true)
                .query(query -> query.term(term -> term
                        .field("workspace_id")
                        .value(workspaceId))));
    }

    private void deletePattern(String pattern) {
        try {
            GetIndexResponse response = client.indices()
                    .get(get -> get.index(pattern).ignoreUnavailable(true));
            List<String> indices = new ArrayList<>(response.result().keySet());
            for (int start = 0; start < indices.size(); start += 20) {
                List<String> batch = indices.subList(start, Math.min(start + 20, indices.size()));
                client.indices().delete(delete -> delete.index(batch).ignoreUnavailable(true));
            }
            if (!indices.isEmpty()) {
                log.info("清理测试检索索引: pattern={}, count={}", pattern, indices.size());
            }
        } catch (IOException | RuntimeException ex) {
            log.warn("清理测试检索索引失败: pattern={}", pattern, ex);
        }
    }

    private static String resolveWikiIndexName() {
        String property = System.getProperty(WIKI_INDEX_PROPERTY);
        if (property != null && !property.isBlank()) {
            return property;
        }
        String env = System.getenv(WIKI_INDEX_ENV);
        if (env != null && !env.isBlank()) {
            return env;
        }
        return DEFAULT_WIKI_INDEX;
    }

    private static String normalizeWikiIndexName(String indexName) {
        return indexName == null || indexName.isBlank() ? DEFAULT_WIKI_INDEX : indexName;
    }
}
