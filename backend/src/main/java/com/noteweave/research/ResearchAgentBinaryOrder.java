package com.noteweave.research;

import java.nio.charset.StandardCharsets;
import java.util.Comparator;

/** MySQL {@code CAST(value AS BINARY)} compatible unsigned UTF-8 byte order. */
final class ResearchAgentBinaryOrder {
    static final Comparator<String> UTF8 = ResearchAgentBinaryOrder::compare;

    private ResearchAgentBinaryOrder() { }

    private static int compare(String left, String right) {
        byte[] leftBytes = left.getBytes(StandardCharsets.UTF_8);
        byte[] rightBytes = right.getBytes(StandardCharsets.UTF_8);
        int common = Math.min(leftBytes.length, rightBytes.length);
        for (int index = 0; index < common; index++) {
            int compared = Integer.compare(Byte.toUnsignedInt(leftBytes[index]), Byte.toUnsignedInt(rightBytes[index]));
            if (compared != 0) return compared;
        }
        return Integer.compare(leftBytes.length, rightBytes.length);
    }
}
