package com.noteweave.answer.generation;

/** 只在正文引用位置规范化编号；所有未决语法在发送前完成判定。 */
public final class CitationMarkerNormalizer {
    public static final int MAX_MARKER_LENGTH = 64;
    public static final int MAX_CITATION_NUMBER = 9999;
    private final GenerationContract contract;
    private final StringBuilder pending = new StringBuilder();
    private int outOfRangeNumbers;
    private int inlineTicks;
    private char fence;
    private int fenceLength;
    private boolean closingFenceLine;
    private int bracketDepth;
    private int linkDepth;
    private boolean afterLabel;
    private boolean escaped;
    private boolean linePrefix = true;
    private int indent;
    private char previous;

    public CitationMarkerNormalizer(GenerationContract contract) { this.contract = contract; }

    public String accept(String chunk) {
        if (chunk == null || chunk.isEmpty()) return "";
        pending.append(chunk);
        return drain(false);
    }

    public String flush() { return drain(true); }
    public int outOfRangeNumbers() { return outOfRangeNumbers; }
    /** 页面链接是普通导航，不作为证据标记，也不计非法证据。 */
    public int unknownPages() { return 0; }

    private String drain(boolean end) {
        StringBuilder output = new StringBuilder();
        int i = 0;
        while (i < pending.length()) {
            char c = pending.charAt(i);
            if (escaped) {
                append(output, c); i++; escaped = false; continue;
            }
            if (c == '\\' && fence == 0 && inlineTicks == 0) {
                append(output, c); i++; escaped = true; continue;
            }
            if (linkDepth > 0) {
                if (c == '(') linkDepth++;
                if (c == ')') linkDepth--;
                append(output, c); i++; continue;
            }
            if (bracketDepth > 0) {
                if (c == '[') bracketDepth++;
                if (c == ']') {
                    bracketDepth--;
                    if (bracketDepth == 0) afterLabel = true;
                }
                append(output, c); i++; continue;
            }
            if (afterLabel) {
                afterLabel = false;
                if (c == '(') { linkDepth = 1; append(output, c); i++; continue; }
                if (c == '[') { bracketDepth = 1; append(output, c); i++; continue; }
            }
            if (fence != 0) {
                if (linePrefix && c == fence) {
                    int run = runLength(i, c);
                    if (i + run == pending.length() && !end) break;
                    closingFenceLine = run >= fenceLength;
                    for (int j = 0; j < run; j++) append(output, c);
                    i += run; continue;
                }
                if (closingFenceLine) {
                    if (c == '\n' || c == '\r') { fence = 0; fenceLength = 0; closingFenceLine = false; }
                    else if (c != ' ' && c != '\t') closingFenceLine = false;
                }
                append(output, c); i++; continue;
            }
            if (c == '`' || (inlineTicks == 0 && linePrefix && c == '~')) {
                int run = runLength(i, c);
                if (i + run == pending.length() && !end) break;
                if (inlineTicks > 0) {
                    if (c == '`' && run == inlineTicks) inlineTicks = 0;
                } else if (linePrefix && run >= 3) {
                    fence = c; fenceLength = run;
                } else if (c == '`') inlineTicks = run;
                for (int j = 0; j < run; j++) append(output, c);
                i += run; continue;
            }
            if (inlineTicks > 0) { append(output, c); i++; continue; }
            if (c != '[' || contract == null) { append(output, c); i++; continue; }
            if (i + 1 == pending.length() && !end) break;
            char next = i + 1 < pending.length() ? pending.charAt(i + 1) : 0;
            // 标题导航、普通链接标签与标识符下标不参与引用规范化。
            if (next == '[' || identifier(previous) || !digit(next)) {
                bracketDepth = 1; append(output, c); i++; continue;
            }
            int close = i + 1;
            while (close < pending.length() && digit(pending.charAt(close))
                    && close - i < MAX_MARKER_LENGTH) close++;
            if (close == pending.length() && !end && close - i < MAX_MARKER_LENGTH) break;
            if (close - i >= MAX_MARKER_LENGTH || close == pending.length() || pending.charAt(close) != ']') {
                bracketDepth = 1; append(output, c); i++; continue;
            }
            if (close + 1 == pending.length() && !end) break;
            char following = close + 1 < pending.length() ? pending.charAt(close + 1) : 0;
            if (following == '(' || linePrefix && following == ':' || following == '[' && close + 2 < pending.length()
                    && !digit(pending.charAt(close + 2))) {
                bracketDepth = 1; append(output, c); i++; continue;
            }
            // 下一标记既可能是相邻编号，也可能是引用式链接，等一个字符再决定。
            if (following == '[' && close + 2 == pending.length() && !end) break;
            String digits = pending.substring(i + 1, close);
            int number = digits.length() <= 4 ? Integer.parseInt(digits) : -1;
            boolean allowed = number > 0 && number <= MAX_CITATION_NUMBER && !contract.refusal()
                    && contract.hasNumber(number) && contract.allows(contract.evidenceIdOf(number));
            if (allowed) output.append(pending, i, close + 1);
            else { output.append('［').append(digits).append('］'); outOfRangeNumbers++; }
            previous = ']'; linePrefix = false;
            i = close + 1;
        }
        pending.delete(0, i);
        return output.toString();
    }

    private int runLength(int start, char c) {
        int end = start;
        while (end < pending.length() && pending.charAt(end) == c) end++;
        return end - start;
    }

    private void append(StringBuilder output, char c) {
        output.append(c);
        previous = c;
        if (c == '\n' || c == '\r') { linePrefix = true; indent = 0; }
        else if (linePrefix && c == ' ' && indent < 3) indent++;
        else linePrefix = false;
    }
    private static boolean digit(char c) { return c >= '0' && c <= '9'; }
    private static boolean identifier(char c) {
        return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || digit(c) || c == '_' || c == '$';
    }
}
