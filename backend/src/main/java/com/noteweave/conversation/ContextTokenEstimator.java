package com.noteweave.conversation;

/**
 * 在没有固定模型分词器的情况下估算文本的 token 数，用于上下文预算。
 * <p>
 * 中日韩文字按每个字 1 个 token 计算；其余字符（英文、数字、标点、空白）按平均 4 个字符
 * 1 个 token 计算并向上取整。常见分词器对中文的实际切分通常在每字 0.6 到 1.2 个 token
 * 之间，这里取偏保守的估计，避免实际提示词超出模型窗口。
 */
public final class ContextTokenEstimator {

    private ContextTokenEstimator() {
    }

    public static int estimate(String value) {
        if (value == null || value.isEmpty()) return 0;
        int cjk = 0;
        int other = 0;
        for (int index = 0; index < value.length(); ) {
            int codePoint = value.codePointAt(index);
            index += Character.charCount(codePoint);
            if (isCjk(codePoint)) {
                cjk++;
            } else {
                other++;
            }
        }
        return cjk + (other + 3) / 4;
    }

    private static boolean isCjk(int codePoint) {
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL;
    }
}
