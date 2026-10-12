package com.noteweave.memory;

/**
 * 有上限的归一化编辑距离。长文先截断，避免两段正文的距离计算没有上界。
 */
public final class MemoryEditRatio {

    static final int MAX_CHARS = 800;

    private MemoryEditRatio() {
    }

    /** 空白对空白为 0。一边为空时为 1。其余是编辑距离除以较长文本的长度。 */
    public static double ratio(String before, String after) {
        String left = normalize(before);
        String right = normalize(after);
        if (left.isEmpty() && right.isEmpty()) {
            return 0;
        }
        if (left.isEmpty() || right.isEmpty()) {
            return 1;
        }
        if (left.equals(right)) {
            return 0;
        }
        int distance = distance(left, right);
        int longer = Math.max(left.length(), right.length());
        return Math.round(distance * 10_000.0 / longer) / 10_000.0;
    }

    static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String collapsed = value.trim().replaceAll("\\s+", " ");
        return collapsed.length() <= MAX_CHARS ? collapsed : collapsed.substring(0, MAX_CHARS);
    }

    private static int distance(String left, String right) {
        int[] previous = new int[right.length() + 1];
        int[] current = new int[right.length() + 1];
        for (int column = 0; column <= right.length(); column++) {
            previous[column] = column;
        }
        for (int row = 1; row <= left.length(); row++) {
            current[0] = row;
            char leftChar = left.charAt(row - 1);
            for (int column = 1; column <= right.length(); column++) {
                int replace = previous[column - 1] + (leftChar == right.charAt(column - 1) ? 0 : 1);
                int delete = previous[column] + 1;
                int insert = current[column - 1] + 1;
                current[column] = Math.min(replace, Math.min(delete, insert));
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[right.length()];
    }
}
