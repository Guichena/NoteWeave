package com.noteweave.retrieval.index;

/**
 * 检索侧读取「当前向量版本」的唯一来源（IDX-02）。
 *
 * <p>写入侧把该版本写进文档的 {@code embedding_version}，查询侧用它过滤 kNN 候选：
 * 换模型或改维度后，旧索引里的向量与查询向量不在同一个空间，必须被排除，
 * 而不是参与相似度计算后给出看似正常的错误结果。实现由投影服务提供，
 * 保证写入与查询算出同一个版本。</p>
 */
public interface RetrievalEmbeddingVersionSource {

    String currentEmbeddingVersion();
}
