package com.noteweave.artifact;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 产物版本正文的完整差异计算。
 *
 * <p>不能只比较前 800 字符：长文尾部的修改同样必须被发现。
 * 章节按 Markdown 标题切分，逐节比较 SHA-256；无标题时按固定行块比较。</p>
 */
public final class ArtifactVersionDiff {

    /** 单个正文的最大比较字节数；超过时整体按 digest 比较，章节信息降级为整篇。 */
    public static final int MAX_CONTENT_BYTES = 8_000_000;

    private ArtifactVersionDiff() {
    }

    public static String sha256(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }

    /** 全文或逐章节比较，返回发生变化的章节标识；返回空列表表示无变化。 */
    public static List<String> changedSections(String before, String after) {
        String safeBefore = before == null ? "" : before;
        String safeAfter = after == null ? "" : after;
        if (safeBefore.equals(safeAfter)) {
            return List.of();
        }
        if (safeBefore.getBytes(StandardCharsets.UTF_8).length > MAX_CONTENT_BYTES
                || safeAfter.getBytes(StandardCharsets.UTF_8).length > MAX_CONTENT_BYTES) {
            return List.of("__whole_document__");
        }
        List<Section> beforeSections = splitSections(safeBefore);
        List<Section> afterSections = splitSections(safeAfter);
        Set<String> changed = new LinkedHashSet<>();
        int max = Math.max(beforeSections.size(), afterSections.size());
        for (int index = 0; index < max; index++) {
            String beforeDigest = index < beforeSections.size()
                    ? beforeSections.get(index).digest() : null;
            String afterDigest = index < afterSections.size()
                    ? afterSections.get(index).digest() : null;
            if (!java.util.Objects.equals(beforeDigest, afterDigest)) {
                changed.add(index < afterSections.size()
                        ? afterSections.get(index).title()
                        : index < beforeSections.size()
                                ? beforeSections.get(index).title() : "section-" + index);
            }
        }
        return List.copyOf(changed);
    }

    private static List<Section> splitSections(String content) {
        List<Section> sections = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        String currentTitle = "__preamble__";
        for (String line : content.split("\n", -1)) {
            if (line.startsWith("#")) {
                if (!current.isEmpty()) {
                    sections.add(new Section(currentTitle, current.toString()));
                }
                currentTitle = line.strip();
                current = new StringBuilder();
            }
            current.append(line).append('\n');
        }
        if (!current.isEmpty()) {
            sections.add(new Section(currentTitle, current.toString()));
        }
        if (sections.isEmpty()) {
            sections.add(new Section("__whole_document__", content));
        }
        return sections;
    }

    private record Section(String title, String content) {
        private String digest() {
            return sha256(content);
        }
    }
}
