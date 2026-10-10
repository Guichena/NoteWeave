package com.noteweave.common.text;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 中英文混合文本的词项切分，供不依赖搜索引擎的本地打分使用（Wiki 检索、话题切分等）。
 * <p>
 * 规则与检索索引里的 cjk_bigram 分析器保持一致：中文连续片段按相邻两字切分，只有一个字的片段保留单字；
 * 英文和数字按单词切分并转小写，全角字符先做 NFKC 归一。这样同一段文本在 ES 和本地打分里得到的词项相同。
 */
public final class LexicalTerms {

    private static final Pattern RUN = Pattern.compile("[\\p{IsHan}]+|[\\p{L}\\p{N}&&[^\\p{IsHan}]]+");

    /** 出现在几乎所有提问里、对区分主题没有帮助的中文二元组（疑问词、指代词、请求用语）和英文虚词。 */
    private static final Set<String> STOP_TERMS = Set.of(
            "什么", "怎么", "如何", "为什", "么是", "是什", "这个", "那个", "一下", "可以", "请问", "我们",
            "你们", "他们", "现在", "之前", "继续", "问题", "一个", "的是", "是不", "不是", "有没", "没有",
            "解释", "释一", "介绍", "绍一", "说明", "明一", "讲讲", "讲一", "帮我", "告诉", "诉我", "详细", "具体",
            "the", "a", "an", "and", "or", "of", "to", "in", "on", "for", "with", "is", "are", "was",
            "be", "do", "does", "how", "what", "why", "which", "this", "that", "it");

    private LexicalTerms() {
    }

    /** 按出现顺序返回全部词项（保留重复，用于计算词频）。 */
    public static List<String> tokens(String text) {
        List<String> result = new ArrayList<>();
        if (text == null || text.isBlank()) return result;
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        Matcher matcher = RUN.matcher(normalized);
        while (matcher.find()) {
            String run = matcher.group();
            int first = run.codePointAt(0);
            if (Character.UnicodeScript.of(first) == Character.UnicodeScript.HAN) {
                int[] points = run.codePoints().toArray();
                if (points.length == 1) {
                    result.add(run);
                } else {
                    for (int index = 0; index + 1 < points.length; index++) {
                        result.add(new String(points, index, 2));
                    }
                }
            } else if (run.length() >= 2 || Character.isDigit(first)) {
                result.add(run);
            }
        }
        return result;
    }

    /** 去重并去掉停用词后的词项集合，用于查询。 */
    public static Set<String> queryTerms(String text) {
        Set<String> terms = new LinkedHashSet<>();
        for (String token : tokens(text)) {
            if (!STOP_TERMS.contains(token)) terms.add(token);
        }
        return terms;
    }

    public static boolean isStopTerm(String term) {
        return STOP_TERMS.contains(term);
    }
}
