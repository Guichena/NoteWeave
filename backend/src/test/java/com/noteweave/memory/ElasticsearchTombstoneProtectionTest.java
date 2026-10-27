package com.noteweave.memory;

import static org.assertj.core.api.Assertions.assertThat;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import java.net.URI;
import java.util.List;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * B-07 旧写防护的真实 ES 验证（隔离测试实例 nw-oth-b-20261023-test-es，127.0.0.1:19200，免鉴权）：
 * 单调 external version + 持久墓碑文档。迟到旧 UPSERT 必须被版本比较拒绝，
 * 不能复活已删除文档；检索必须过滤墓碑。实例不可达时跳过并记录缺项（不借用业务 ES）。
 */
class ElasticsearchTombstoneProtectionTest {

    private static final String INDEX_NAME = "nw-oth-b-20261023-tombstone";
    private ElasticsearchClient client;
    private ElasticsearchMemoryVectorIndex index;

    private ElasticsearchMemoryVectorIndex connect() {
        try {
            RestClient rest = RestClient.builder(new HttpHost("127.0.0.1", 19200, "http")).build();
            client = new ElasticsearchClient(new RestClientTransport(rest, new JacksonJsonpMapper()));
            assertThat(client.cluster().health().status().toString()).isNotEmpty();
            index = new ElasticsearchMemoryVectorIndex(client, INDEX_NAME, 4, 0);
            return index;
        } catch (Exception unavailable) {
            return null;
        }
    }

    /** ES 近实时：写入对检索可见前需要 refresh。 */
    private void refresh() throws Exception {
        client.indices().refresh(request -> request.index(INDEX_NAME));
    }

    @Test
    void lateOldUpsertMustNotResurrectDeletedDocument() throws Exception {
        ElasticsearchMemoryVectorIndex index = connect();
        Assumptions.assumeTrue(index != null && index.available(),
                "隔离测试 ES（127.0.0.1:19200）不可达，本验证按缺项记录，不借用业务 ES");
        String workspace = "ws-tombstone-" + System.nanoTime();
        String item = "memory-tombstone-" + System.nanoTime();
        List<Float> vector = List.of(0.1f, 0.2f, 0.3f, 0.4f);

        // 版本 5 的正常写入可见
        assertThat(index.upsert(new MemoryVectorIndex.Document(
                workspace, item, "answer.style", "旧正文内容", "COMMON", vector), 5)).isTrue();
        refresh();
        assertThat(index.search(workspace, "COMMON", vector, 3).hits())
                .anySatisfy(hit -> assertThat(hit.text()).isEqualTo("旧正文内容"));

        // 版本 6 删除：写入持久墓碑而非物理删除，检索立即不可见
        assertThat(index.delete(workspace, item, 6)).isTrue();
        refresh();
        assertThat(index.search(workspace, "COMMON", vector, 3).hits())
                .noneMatch(hit -> item.equals(hit.itemId()));

        // 迟到的旧 UPSERT（版本 5）必须被版本比较拒绝，不能复活已删除文档
        assertThat(index.upsert(new MemoryVectorIndex.Document(
                workspace, item, "answer.style", "迟到的旧正文", "COMMON", vector), 5)).isFalse();
        refresh();
        assertThat(index.search(workspace, "COMMON", vector, 3).hits())
                .noneMatch(hit -> item.equals(hit.itemId()));

        // 数据库再次授予更高版本（7）的重新写入仍然可见：防护不阻塞合法恢复
        assertThat(index.upsert(new MemoryVectorIndex.Document(
                workspace, item, "answer.style", "重新激活的正文", "COMMON", vector), 7)).isTrue();
        refresh();
        assertThat(index.search(workspace, "COMMON", vector, 3).hits())
                .anySatisfy(hit -> assertThat(hit.text()).isEqualTo("重新激活的正文"));

        // 清理：仅删除本测试专用索引的文档，不触碰其他索引
        assertThat(index.delete(workspace, item, 8)).isTrue();
    }
}
