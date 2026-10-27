package com.noteweave.artifact;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 产物版本正文的完整差异计算（C4 v2 差异合同）。
 *
 * <p>全文 SHA-256 由调用方计算；本类产出：
 * <ul>
 *   <li>changedSectionIds：稳定章节身份（章节序号 + 章节内容摘要前 8 位），不用标题文本充当正文。</li>
 *   <li>fragments：有界的变化片段，含受限 before/after 摘录、各自全文摘要与截断标记；
 *       片段数量超限时按变化分布均匀采样，首尾变化必须保留。</li>
 *   <li>diffStats：changedChars/totalChars 来自完整行级比较，附算法版本，
 *       不是标题长度或摘要长度。</li>
 * </ul>
 * 行级比较基于公共前后缀收缩 + 中段 LCS；中段规模超限时降级为整篇粗粒度比较并标记 truncated。</p>
 */
public final class ArtifactVersionDiffV2 {

    /** 单侧片段摘录的最大字符数；超出部分截断并置 truncated。 */
    public static final int MAX_EXCERPT_CHARS = 600;

    /** 事件携带的最大片段数；更多片段按分布采样，首尾必须保留。 */
    public static final int MAX_FRAGMENTS = 40;

    /** 中段 LCS 的最大单元数（行数乘积），超过则降级为整篇比较。 */
    static final long MAX_LCS_CELLS = 4_000_000L;

    public static final String ALGORITHM = "line-diff-v2";

    private ArtifactVersionDiffV2() {
    }

    /** 有界变化片段；before/after 摘录受限，全文以摘要为准。 */
    public record Fragment(
            String sectionId,
            String beforeExcerpt,
            String afterExcerpt,
            String beforeDigest,
            String afterDigest,
            boolean beforeTruncated,
            boolean afterTruncated,
            int beforeChars,
            int afterChars
    ) {
    }

    /** 完整比较统计；changedChars 为变化行字符总数（删除+新增），totalChars 为 after 全文字符数。 */
    public record DiffStats(int changedChars, int totalChars, String algorithm) {
    }

    public record DiffResult(
            List<String> changedSectionIds,
            List<Fragment> fragments,
            DiffStats diffStats
    ) {
    }

    public static DiffResult compute(String before, String after) {
        String safeBefore = before == null ? "" : before;
        String safeAfter = after == null ? "" : after;
        if (safeBefore.equals(safeAfter)) {
            return new DiffResult(List.of(), List.of(), new DiffStats(0, safeAfter.length(), ALGORITHM));
        }
        String[] beforeLines = safeBefore.split("\n", -1);
        String[] afterLines = safeAfter.split("\n", -1);
        List<String> sectionIds = sectionIds(afterLines);
        boolean[] changedAfter = new boolean[afterLines.length];
        boolean[] changedBefore = new boolean[beforeLines.length];
        boolean coarse;
        int prefix = 0;
        while (prefix < beforeLines.length && prefix < afterLines.length
                && beforeLines[prefix].equals(afterLines[prefix])) {
            prefix++;
        }
        int suffix = 0;
        while (suffix < beforeLines.length - prefix && suffix < afterLines.length - prefix
                && beforeLines[beforeLines.length - 1 - suffix].equals(afterLines[afterLines.length - 1 - suffix])) {
            suffix++;
        }
        int beforeMiddle = beforeLines.length - prefix - suffix;
        int afterMiddle = afterLines.length - prefix - suffix;
        coarse = (long) beforeMiddle * afterMiddle > MAX_LCS_CELLS;
        if (!coarse) {
            // 中段 LCS：removedBefore/keptAfter 标记被删除与被保留的行
            boolean[] removedBefore = lcsRemoved(
                    beforeLines, prefix, beforeMiddle,
                    afterLines, prefix, afterMiddle);
            boolean[] keptAfter = new boolean[afterMiddle];
            int bi = 0;
            int ai = 0;
            while (bi < beforeMiddle && ai < afterMiddle) {
                if (removedBefore[bi]) {
                    bi++;
                } else if (beforeLines[prefix + bi].equals(afterLines[prefix + ai])) {
                    keptAfter[ai] = true;
                    bi++;
                    ai++;
                } else {
                    ai++;
                }
            }
            for (int i = 0; i < beforeMiddle; i++) {
                changedBefore[prefix + i] = removedBefore[i];
            }
            for (int j = 0; j < afterMiddle; j++) {
                changedAfter[prefix + j] = !keptAfter[j];
            }
        } else {
            for (int i = 0; i < beforeLines.length; i++) {
                changedBefore[i] = true;
            }
            for (int j = 0; j < afterLines.length; j++) {
                changedAfter[j] = true;
            }
        }
        List<int[]> afterRegions = regionsOf(changedAfter);
        List<int[]> beforeRegions = regionsOf(changedBefore);
        int changedChars = 0;
        for (boolean flag : changedBefore) {
            if (flag) {
                changedChars++;
            }
        }
        for (int i = 0; i < changedBefore.length; i++) {
            if (changedBefore[i]) {
                changedChars += beforeLines[i].length();
            }
        }
        for (int j = 0; j < changedAfter.length; j++) {
            if (changedAfter[j]) {
                changedChars += afterLines[j].length();
            }
        }
        List<Fragment> fragments = buildFragments(beforeLines, afterLines,
                beforeRegions, afterRegions, sectionIds, coarse);
        Set<String> changedIds = new LinkedHashSet<>();
        for (Fragment fragment : fragments) {
            if (fragment.sectionId() != null) {
                changedIds.add(fragment.sectionId());
            }
        }
        if (coarse) {
            changedIds.add("whole-document-coarse");
        }
        return new DiffResult(List.copyOf(changedIds), fragments,
                new DiffStats(changedChars, safeAfter.length(), ALGORITHM));
    }

    /** 中段 LCS：返回 before 侧被删除（未参与最长公共子序列）的行标记。 */
    private static boolean[] lcsRemoved(
            String[] beforeLines, int beforeOffset, int beforeLength,
            String[] afterLines, int afterOffset, int afterLength
    ) {
        int[][] table = new int[beforeLength + 1][afterLength + 1];
        for (int i = beforeLength - 1; i >= 0; i--) {
            for (int j = afterLength - 1; j >= 0; j--) {
                if (beforeLines[beforeOffset + i].equals(afterLines[afterOffset + j])) {
                    table[i][j] = table[i + 1][j + 1] + 1;
                } else {
                    table[i][j] = Math.max(table[i + 1][j], table[i][j + 1]);
                }
            }
        }
        boolean[] removed = new boolean[beforeLength];
        int i = 0;
        int j = 0;
        while (i < beforeLength && j < afterLength) {
            if (beforeLines[beforeOffset + i].equals(afterLines[afterOffset + j])) {
                i++;
                j++;
            } else if (table[i + 1][j] >= table[i][j + 1]) {
                removed[i] = true;
                i++;
            } else {
                j++;
            }
        }
        while (i < beforeLength) {
            removed[i] = true;
            i++;
        }
        return removed;
    }

    private static List<int[]> regionsOf(boolean[] changed) {
        List<int[]> regions = new ArrayList<>();
        int start = -1;
        for (int i = 0; i <= changed.length; i++) {
            boolean flagged = i < changed.length && changed[i];
            if (flagged && start < 0) {
                start = i;
            } else if (!flagged && start >= 0) {
                regions.add(new int[] {start, i - 1});
                start = -1;
            }
        }
        return regions;
    }

    private static List<Fragment> buildFragments(
            String[] beforeLines,
            String[] afterLines,
            List<int[]> beforeRegions,
            List<int[]> afterRegions,
            List<String> sectionIds,
            boolean coarse
    ) {
        // 变化区域按 after 侧为准配对 before 侧区域（数量可能不等，按序对齐，多余侧单独成段）
        int regionCount = Math.max(beforeRegions.size(), afterRegions.size());
        List<int[]> paired = new ArrayList<>();
        for (int i = 0; i < regionCount; i++) {
            int[] beforeRegion = i < beforeRegions.size() ? beforeRegions.get(i) : null;
            int[] afterRegion = i < afterRegions.size() ? afterRegions.get(i) : null;
            paired.add(new int[] {
                    beforeRegion == null ? -1 : beforeRegion[0],
                    beforeRegion == null ? -1 : beforeRegion[1],
                    afterRegion == null ? -1 : afterRegion[0],
                    afterRegion == null ? -1 : afterRegion[1]});
        }
        List<int[]> selected = sampleRegions(paired);
        List<Fragment> fragments = new ArrayList<>();
        for (int[] region : selected) {
            int beforeStart = region[0];
            int beforeEnd = region[1];
            int afterStart = region[2];
            int afterEnd = region[3];
            String beforeText = joinLines(beforeLines, beforeStart, beforeEnd);
            String afterText = joinLines(afterLines, afterStart, afterEnd);
            Excerpt beforeExcerpt = excerpt(beforeText);
            Excerpt afterExcerpt = excerpt(afterText);
            String sectionId = afterStart >= 0 && afterStart < sectionIds.size()
                    ? sectionIds.get(afterStart) : sectionIds.isEmpty() ? null : sectionIds.get(0);
            fragments.add(new Fragment(
                    sectionId,
                    beforeExcerpt.text(),
                    afterExcerpt.text(),
                    ArtifactVersionDiff.sha256(beforeText),
                    ArtifactVersionDiff.sha256(afterText),
                    beforeExcerpt.truncated(),
                    afterExcerpt.truncated(),
                    beforeText.length(),
                    afterText.length()));
        }
        return fragments;
    }

    /** 片段配额采样：超限时间隔均匀选取，且始终保留首个与最后一个变化区域。 */
    private static List<int[]> sampleRegions(List<int[]> regions) {
        if (regions.size() <= MAX_FRAGMENTS) {
            return regions;
        }
        List<int[]> sampled = new ArrayList<>();
        double step = (double) (regions.size() - 1) / (MAX_FRAGMENTS - 1);
        for (int i = 0; i < MAX_FRAGMENTS; i++) {
            sampled.add(regions.get((int) Math.round(i * step)));
        }
        return sampled;
    }

    private static Excerpt excerpt(String text) {
        if (text.length() <= MAX_EXCERPT_CHARS) {
            return new Excerpt(text, false);
        }
        return new Excerpt(text.substring(0, MAX_EXCERPT_CHARS), true);
    }

    private record Excerpt(String text, boolean truncated) {
    }

    private static String joinLines(String[] lines, int start, int end) {
        if (start < 0 || end < start) {
            return "";
        }
        StringBuilder joined = new StringBuilder();
        for (int i = start; i <= end; i++) {
            joined.append(lines[i]);
            if (i < end) {
                joined.append('\n');
            }
        }
        return joined.toString();
    }

    /** 每行所属章节的稳定身份：章节序号 + 章节内容摘要前 8 位。 */
    private static List<String> sectionIds(String[] lines) {
        List<String> ids = new ArrayList<>(lines.length);
        int sectionIndex = 0;
        StringBuilder sectionBody = new StringBuilder();
        String currentId = "s0-" + shortDigest("");
        for (String line : lines) {
            if (line.startsWith("#")) {
                currentId = "s" + sectionIndex + "-" + shortDigest(sectionBody.toString());
                sectionIndex++;
                sectionBody = new StringBuilder();
            }
            sectionBody.append(line).append('\n');
            ids.add(currentId);
        }
        return ids;
    }

    private static String shortDigest(String content) {
        return ArtifactVersionDiff.sha256(content == null ? "" : content)
                .substring(0, 8);
    }

    static int utf8Length(String content) {
        return content == null ? 0 : content.getBytes(StandardCharsets.UTF_8).length;
    }

    static boolean sameFragmentShape(Fragment left, Fragment right) {
        return Objects.equals(left.beforeDigest(), right.beforeDigest())
                && Objects.equals(left.afterDigest(), right.afterDigest());
    }
}
