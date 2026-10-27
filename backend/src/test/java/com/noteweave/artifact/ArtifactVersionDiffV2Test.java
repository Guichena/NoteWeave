package com.noteweave.artifact;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * FA-04 定向验证：C4 v2 差异合同。
 * 覆盖长文尾部改动可见、标题不变正文大改时片段来自正文而非标题、
 * 片段摘录上限与截断标记、diffStats 完整比较口径。
 */
class ArtifactVersionDiffV2Test {

    @Test
    void tailChangeProducesFragmentWithExcerpt() {
        String before = "# 报告\n" + "背景内容。\n".repeat(200) + "\n# 结论\n旧结论。";
        String after = "# 报告\n" + "背景内容。\n".repeat(200) + "\n# 结论\n全新结论：改成了完全不同的表述。";
        ArtifactVersionDiffV2.DiffResult result = ArtifactVersionDiffV2.compute(before, after);
        org.junit.jupiter.api.Assertions.assertFalse(result.fragments().isEmpty());
        ArtifactVersionDiffV2.Fragment tail = result.fragments().get(result.fragments().size() - 1);
        org.junit.jupiter.api.Assertions.assertTrue(tail.afterExcerpt().contains("全新结论"),
                "尾部改动必须以受限摘录形式可见");
        org.junit.jupiter.api.Assertions.assertTrue(result.changedSectionIds().size() >= 1);
        org.junit.jupiter.api.Assertions.assertTrue(result.diffStats().changedChars() > 0);
        org.junit.jupiter.api.Assertions.assertEquals(after.length(), result.diffStats().totalChars());
        org.junit.jupiter.api.Assertions.assertEquals(ArtifactVersionDiffV2.ALGORITHM,
                result.diffStats().algorithm());
    }

    @Test
    void unchangedTitleWithBodyRewriteIsVisibleInFragments() {
        String before = "# 同一标题\n" + "很长的一段正文回答。" .repeat(100);
        String after = "# 同一标题\n" + "短列表。";
        ArtifactVersionDiffV2.DiffResult result = ArtifactVersionDiffV2.compute(before, after);
        org.junit.jupiter.api.Assertions.assertFalse(result.fragments().isEmpty());
        // 消费者拿到的是正文片段摘录与全文摘要，不是章节标题文本
        org.junit.jupiter.api.Assertions.assertTrue(
                result.fragments().stream().anyMatch(fragment ->
                        fragment.afterExcerpt().contains("短列表")
                                || fragment.beforeExcerpt().contains("很长的一段正文回答")));
        org.junit.jupiter.api.Assertions.assertTrue(result.diffStats().changedChars() > 100);
    }

    @Test
    void oversizedExcerptIsTruncatedWithFlag() {
        String before = "# t\n短";
        String after = "# t\n" + "长".repeat(5000);
        ArtifactVersionDiffV2.DiffResult result = ArtifactVersionDiffV2.compute(before, after);
        ArtifactVersionDiffV2.Fragment fragment = result.fragments().get(0);
        org.junit.jupiter.api.Assertions.assertTrue(fragment.afterTruncated());
        org.junit.jupiter.api.Assertions.assertTrue(fragment.afterExcerpt().length()
                <= ArtifactVersionDiffV2.MAX_EXCERPT_CHARS);
        org.junit.jupiter.api.Assertions.assertEquals(5000, fragment.afterChars());
    }

    @Test
    void identicalContentHasNoFragments() {
        String content = "# 标题\n正文";
        ArtifactVersionDiffV2.DiffResult result = ArtifactVersionDiffV2.compute(content, content);
        org.junit.jupiter.api.Assertions.assertTrue(result.fragments().isEmpty());
        org.junit.jupiter.api.Assertions.assertEquals(0, result.diffStats().changedChars());
    }

    @Test
    void manyRegionsSampledToBoundWithFirstAndLastKept() {
        StringBuilder before = new StringBuilder("# 头\n");
        StringBuilder after = new StringBuilder("# 头\n");
        for (int region = 0; region < 60; region++) {
            before.append("共用行").append(region).append("\n\n\n\n\n");
            after.append("共用行").append(region).append("\n");
            after.append("变化").append(region).append("\n\n\n\n");
        }
        after.append("最后的尾部改动");
        ArtifactVersionDiffV2.DiffResult result = ArtifactVersionDiffV2.compute(
                before.toString(), after.toString());
        org.junit.jupiter.api.Assertions.assertTrue(result.fragments().size()
                <= ArtifactVersionDiffV2.MAX_FRAGMENTS);
        ArtifactVersionDiffV2.Fragment last = result.fragments().get(result.fragments().size() - 1);
        org.junit.jupiter.api.Assertions.assertTrue(last.afterExcerpt().contains("最后的尾部改动")
                        || !last.afterTruncated() && last.afterExcerpt().contains("尾部改动"),
                "尾部变化区域必须保留");
    }
}
