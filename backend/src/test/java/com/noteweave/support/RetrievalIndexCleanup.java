package com.noteweave.support;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.indices.GetIndexResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 清理测试工作区写入真实 ES 的检索索引，避免多次运行累积索引造成 ES 内存压力。 */
public class RetrievalIndexCleanup {
    private static final Logger log = LoggerFactory.getLogger(RetrievalIndexCleanup.class);

    private final ElasticsearchClient client;

    public RetrievalIndexCleanup(ElasticsearchClient client) {
        this.client = client;
    }

    /** 删除指定工作区的 QA 与 Note 检索索引；索引不存在时忽略。 */
    public void deleteWorkspaceIndices(String workspaceId) {
        if (workspaceId == null || workspaceId.isBlank()) {
            return;
        }
        deletePattern("noteweave_qa_chunk_*" + workspaceId + "*");
        deletePattern("noteweave_note_source_*" + workspaceId + "*");
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
}
