package com.noteweave.answer.generation;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 流式输出里的引用标记规范化。
 *
 * <p>模型只写编号 {@code [n]} 和页面名 {@code [[页面名]]}。越界编号和未选页面名不属于本次允许的引用，
 * 在流式发送之前改成普通文字并计数，流式内容、返回值和落库正文因此保持一致，不会出现「发送时是角标、
 * 落库后是普通文字」这种前后不一致。</p>
 *
 * <p>标记可能跨多个 token 到达，未闭合的部分先暂存；暂存长度有上限，避免一直等不到闭合而卡住流式输出。</p>
 */
public final class CitationMarkerNormalizer {

    /** 单个标记允许的最大长度，超过就按普通文字输出，不再等待闭合。 */
    public static final int MAX_MARKER_LENGTH = 64;

    private static final Pattern NUMBER_MARKER = Pattern.compile("\\[(\\d{1,4})]");
    private static final Pattern PAGE_MARKER = Pattern.compile("\\[\\[([^\\[\\]\\n]{1,80})]]");
    /** 改成普通文字时使用全角括号，视觉不变但不再被当作引用标记解析。 */
    private static final String FULL_WIDTH_OPEN = "［";
    private static final String FULL_WIDTH_CLOSE = "］";

    private final GenerationContract contract;
    private final StringBuilder pending = new StringBuilder();
    private int outOfRangeNumbers;
    private int unknownPages;

    public CitationMarkerNormalizer(GenerationContract contract) {
        this.contract = contract;
    }

    /** 接收一个流式片段，返回可以立刻发送的安全文本。 */
    public String accept(String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return "";
        }
        pending.append(chunk);
        int safe = safeLength();
        if (safe <= 0) {
            return "";
        }
        String out = normalize(pending.substring(0, safe));
        pending.delete(0, safe);
        return out;
    }

    /** 生成结束后把暂存部分输出；未闭合的标记按普通文字发送。 */
    public String flush() {
        if (pending.length() == 0) {
            return "";
        }
        String out = normalize(pending.toString());
        pending.setLength(0);
        return out;
    }

    public int outOfRangeNumbers() {
        return outOfRangeNumbers;
    }

    public int unknownPages() {
        return unknownPages;
    }

    /**
     * 从尾部找可能还没写完的标记，返回可以安全处理的前缀长度。
     *
     * <p>只有以 {@code [} 开头、还没出现闭合括号且长度未超过上限的尾段才需要等待。</p>
     */
    private int safeLength() {
        int length = pending.length();
        int open = pending.lastIndexOf("[");
        if (open < 0) {
            return length;
        }
        // 连续的 [ 要一起留在缓冲区，否则 [[页面名]] 会被拆成两个片段
        int start = open;
        while (start > 0 && pending.charAt(start - 1) == '[') {
            start--;
        }
        String tail = pending.substring(start);
        if (tail.length() > MAX_MARKER_LENGTH) {
            return length;
        }
        if (tail.indexOf(']') >= 0) {
            return length;
        }
        return start;
    }

    private String normalize(String text) {
        if (text.isEmpty()) {
            return "";
        }
        String normalized = replaceNumbers(text);
        return replacePages(normalized);
    }

    private String replaceNumbers(String text) {
        if (contract == null) {
            return text;
        }
        Matcher matcher = NUMBER_MARKER.matcher(text);
        StringBuilder builder = new StringBuilder();
        while (matcher.find()) {
            String digits = matcher.group(1);
            int number;
            try {
                number = Integer.parseInt(digits);
            } catch (NumberFormatException exception) {
                number = -1;
            }
            String replacement;
            if (number > 0 && contract.hasNumber(number)) {
                replacement = matcher.group();
            } else {
                outOfRangeNumbers++;
                replacement = FULL_WIDTH_OPEN + digits + FULL_WIDTH_CLOSE;
            }
            matcher.appendReplacement(builder, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(builder);
        return builder.toString();
    }

    private String replacePages(String text) {
        if (contract == null) {
            return text;
        }
        Matcher matcher = PAGE_MARKER.matcher(text);
        StringBuilder builder = new StringBuilder();
        while (matcher.find()) {
            String title = matcher.group(1);
            String replacement;
            if (contract.allowsPage(title)) {
                replacement = matcher.group();
            } else {
                unknownPages++;
                replacement = FULL_WIDTH_OPEN + FULL_WIDTH_OPEN + title
                        + FULL_WIDTH_CLOSE + FULL_WIDTH_CLOSE;
            }
            matcher.appendReplacement(builder, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(builder);
        return builder.toString();
    }
}
