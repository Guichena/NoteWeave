package com.noteweave.retrieval;

import com.noteweave.retrieval.lexical.Bm25fScorer;
import java.util.List;
import java.util.Map;

/**
 * Note 五路加权 RRF 与窗口两路 RRF 的策略档案。
 * 运行时读取这里的常量，并复制进 {@code RetrievalPlan.filters} 供回放。
 *
 * <p>{@code experiments/retrieval/dataset.json} 评的是 QA 首轮、多轮追问和 Wiki 跨页，
 * 没有 Note 的向量、元数据、标签、笔记日志、关系五路标注。
 * {@code note-marginalia-gold-v1} 只有 3 条窗口候选，消融里元数据加笔记日志加语义已经打满，
 * 关系扩展不再改变 Recall。因此权重沿用原先向量 1.0、元数据 0.8 的相对关系，
 * 笔记日志略低于元数据，标签和关系再低一档，避免同一名次的关系扩展压过直接命中。
 * 这是契约可核对的默认值，不是在线评测拟合出来的最优解。</p>
 */
public final class NoteRetrievalStrategyProfile {
    public static final String PROFILE_VERSION = "note-channel-rrf-v1";
    public static final int RRF_K = 60;
    public static final int RERANK_LIMIT = 40;
    public static final int CHANNEL_LIMIT = 80;
    public static final int ANCHOR_LIMIT = 8;
    public static final int RELATION_HOP_LIMIT = 40;
    public static final int NEIGHBOR_LIMIT = 80;

    public static final double VECTOR_WEIGHT = 1.0d;
    public static final double METADATA_WEIGHT = 0.8d;
    public static final double JOURNAL_WEIGHT = 0.7d;
    public static final double TAG_WEIGHT = 0.55d;
    public static final double RELATION_WEIGHT = 0.45d;
    public static final double WINDOW_VECTOR_WEIGHT = 0.55d;
    public static final double WINDOW_BM25_WEIGHT = 0.45d;

    public static final String FILTER_PROFILE = "note_strategy_profile";
    public static final String FILTER_RRF_K = "note_rrf_k";
    public static final String FILTER_VECTOR_WEIGHT = "note_vector_weight";
    public static final String FILTER_METADATA_WEIGHT = "note_metadata_weight";
    public static final String FILTER_TAG_WEIGHT = "note_tag_weight";
    public static final String FILTER_JOURNAL_WEIGHT = "note_journal_weight";
    public static final String FILTER_RELATION_WEIGHT = "note_relation_weight";
    public static final String FILTER_WINDOW_BM25_WEIGHT = "note_window_bm25_weight";
    public static final String FILTER_WINDOW_VECTOR_WEIGHT = "note_window_vector_weight";
    public static final String FILTER_RERANK_LIMIT = "note_rerank_limit";

    /** 分数放大后转成整数，避免相邻名次在四舍五入后变成同一分。 */
    public static final double SCORE_SCALE = 100_000d;

    private NoteRetrievalStrategyProfile() {
    }

    public static List<Bm25fScorer.FieldSpec> metadataFields() {
        return List.of(
                new Bm25fScorer.FieldSpec("title", 3.0d, Bm25fScorer.DEFAULT_B),
                new Bm25fScorer.FieldSpec("summary", 2.0d, Bm25fScorer.DEFAULT_B),
                new Bm25fScorer.FieldSpec("tags", 2.0d, Bm25fScorer.DEFAULT_B),
                new Bm25fScorer.FieldSpec("metadata", 1.5d, Bm25fScorer.DEFAULT_B),
                new Bm25fScorer.FieldSpec("sample_text", 1.0d, Bm25fScorer.DEFAULT_B),
                new Bm25fScorer.FieldSpec("source_type", 0.3d, Bm25fScorer.DEFAULT_B));
    }

    public static List<Bm25fScorer.FieldSpec> windowFields() {
        return List.of(new Bm25fScorer.FieldSpec("window", 1.0d, Bm25fScorer.DEFAULT_B));
    }

    public static void copyInto(Map<String, String> filters) {
        filters.put(FILTER_PROFILE, PROFILE_VERSION);
        filters.put(FILTER_RRF_K, Integer.toString(RRF_K));
        filters.put(FILTER_VECTOR_WEIGHT, Double.toString(VECTOR_WEIGHT));
        filters.put(FILTER_METADATA_WEIGHT, Double.toString(METADATA_WEIGHT));
        filters.put(FILTER_TAG_WEIGHT, Double.toString(TAG_WEIGHT));
        filters.put(FILTER_JOURNAL_WEIGHT, Double.toString(JOURNAL_WEIGHT));
        filters.put(FILTER_RELATION_WEIGHT, Double.toString(RELATION_WEIGHT));
        filters.put(FILTER_WINDOW_BM25_WEIGHT, Double.toString(WINDOW_BM25_WEIGHT));
        filters.put(FILTER_WINDOW_VECTOR_WEIGHT, Double.toString(WINDOW_VECTOR_WEIGHT));
        filters.put(FILTER_RERANK_LIMIT, Integer.toString(RERANK_LIMIT));
    }

    public static int scaled(double rrf) {
        if (rrf <= 0.0d) {
            return 0;
        }
        return Math.max(1, (int) Math.round(rrf * SCORE_SCALE));
    }
}
