package com.noteweave.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** V3 证据挑选：token 预算装箱、跳过放不下的长段落、用 MMR 压低重复内容。 */
class QaEvidenceSelectionPolicyV3Test {

    @Test
    void nearDuplicateLosesToALowerScoredButDifferentPassage() {
        String shared = "缓存一致性采用延迟双删方案，先删除缓存再更新数据库，最后延迟再次删除缓存";
        List<Item> candidates = List.of(
                new Item("a", "source-1", shared, 1.00, 100),
                new Item("a-copy", "source-1", shared + "。", 0.95, 100),
                new Item("b", "source-2", "订阅 binlog 异步刷新缓存，适合写多读多的场景", 0.80, 100));

        List<Item> selected = select(candidates, 250, 12);

        assertThat(selected).extracting(Item::id).containsExactly("a", "b");
    }

    @Test
    void oversizedCandidateIsSkippedAndTheBudgetGoesToLaterOnes() {
        List<Item> candidates = List.of(
                new Item("top", "source-1", "第一段 内容 甲", 1.00, 300),
                new Item("too-long", "source-2", "第二段 很长的内容 乙", 0.90, 900),
                new Item("short", "source-3", "第三段 简短 丙", 0.50, 300));

        List<Item> selected = select(candidates, 1_000, 12);

        assertThat(selected).extracting(Item::id).containsExactly("top", "short");
        assertThat(selected.stream().mapToInt(Item::tokens).sum()).isLessThanOrEqualTo(1_000);
    }

    @Test
    void countCapStillAppliesWhenTheBudgetIsLarge() {
        List<Item> candidates = List.of(
                new Item("1", "s1", "alpha one", 0.9, 10),
                new Item("2", "s2", "beta two", 0.8, 10),
                new Item("3", "s3", "gamma three", 0.7, 10));

        assertThat(select(candidates, 10_000, 2)).hasSize(2);
    }

    @Test
    void duplicateEvidenceIdsAreSelectedOnce() {
        List<Item> candidates = List.of(
                new Item("same", "s1", "alpha", 0.9, 10),
                new Item("same", "s1", "alpha", 0.9, 10));

        assertThat(select(candidates, 1_000, 12)).hasSize(1);
    }

    @Test
    void zeroScoresFallBackToRankOrder() {
        List<Item> candidates = List.of(
                new Item("first", "s1", "alpha beta", 0, 10),
                new Item("second", "s2", "gamma delta", 0, 10));

        assertThat(select(candidates, 1_000, 1)).extracting(Item::id).containsExactly("first");
    }

    @Test
    void bigramSimilarityWorksForChineseAndIgnoresPunctuation() {
        double same = QaEvidenceSelectionPolicy.jaccard(
                QaEvidenceSelectionPolicy.bigrams("延迟双删，方案"),
                QaEvidenceSelectionPolicy.bigrams("延迟双删方案"));
        double different = QaEvidenceSelectionPolicy.jaccard(
                QaEvidenceSelectionPolicy.bigrams("延迟双删方案"),
                QaEvidenceSelectionPolicy.bigrams("向量检索召回"));

        assertThat(same).isEqualTo(1.0d);
        assertThat(different).isZero();
    }

    private List<Item> select(List<Item> candidates, int budget, int maxEvidence) {
        return QaEvidenceSelectionPolicy.selectMmrWithinBudget(
                candidates, Item::source, Item::id, Item::text, Item::score, Item::tokens, budget, maxEvidence);
    }

    private record Item(String id, String source, String text, double score, int tokens) {
    }
}
