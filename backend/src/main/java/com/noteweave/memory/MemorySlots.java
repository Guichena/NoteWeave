package com.noteweave.memory;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 把偏好整理成槽位、取值和极性。
 * 槽位用受控前缀加自由后缀，例如 answer.language、terminology.术语。
 */
public final class MemorySlots {
    private static final Pattern SLOT = Pattern.compile(
            "^[a-z][a-z0-9]*(?:\\.[a-z0-9_\\-\\p{IsHan}]{1,64})+$");
    private static final Pattern STRUCTURE = Pattern.compile(
            ".*(结构|先.*再|分点|列表|表格|标题|段落|结论|步骤|bullet|table|heading).*",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TERMINOLOGY = Pattern.compile(
            ".*(术语|叫做|称为|统一称|统一叫|译为|写作).*");
    private static final Pattern FORBIDDEN = Pattern.compile(
            ".*(不要|别|避免|禁止|不许|never|avoid|don't|do not).*",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern INTERACTION = Pattern.compile(
            ".*(追问|确认|反问|提问|澄清|ask|confirm).*",
            Pattern.CASE_INSENSITIVE);
    /**
     * 描述篇幅的词表。按「详略、数量、长短」分组排列，先于表格判定和类别兜底生效，
     * 所以「简洁的段落」仍然归入长度而不是结构槽；这里只放整词，不使用裸「长/短」，
     * 以免「更长的解释」这类语气描述被误判为长度偏好。
     */
    private static final String[] LENGTH_TERMS = {
            "简短", "简洁", "精简", "啰嗦", "罗嗦", "冗长", "详细",
            "字数", "篇幅", "长度",
            "太长", "长一点", "长一些", "长段落",
            "短句", "短一点", "短一些"
    };

    private MemorySlots() {
    }

    public record Assignment(String slot, String value, String polarity) {
    }

    public static String categoryOf(String statement) {
        String value = statement == null ? "" : statement.toLowerCase(Locale.ROOT);
        if (FORBIDDEN.matcher(value).find()) {
            return "forbidden";
        }
        if (TERMINOLOGY.matcher(statement == null ? "" : statement).find()) {
            return "terminology";
        }
        if (STRUCTURE.matcher(value).find()) {
            return "structure";
        }
        if (INTERACTION.matcher(value).find()) {
            return "interaction";
        }
        return "style";
    }

    public static Assignment complete(
            String category,
            String statement,
            String signalType,
            String slot,
            String value,
            String polarity
    ) {
        String resolvedSlot = normalizeSlot(slot);
        if (resolvedSlot == null) {
            resolvedSlot = inferSlot(category, statement);
        }
        String resolvedValue = clean(value);
        if (resolvedValue.isBlank()) {
            resolvedValue = clean(statement);
        }
        if (resolvedValue.length() > 80) {
            resolvedValue = resolvedValue.substring(0, 80);
        }
        return new Assignment(resolvedSlot, resolvedValue, polarityOf(category, signalType, polarity));
    }

    public static String polarityOf(String category, String signalType, String polarity) {
        String requested = polarity == null ? "" : polarity.trim().toUpperCase(Locale.ROOT);
        if ("REQUIRE".equals(requested) || "FORBID".equals(requested)) {
            return requested;
        }
        if ("NEGATIVE".equalsIgnoreCase(signalType) || "forbidden".equalsIgnoreCase(category)) {
            return "FORBID";
        }
        return "REQUIRE";
    }

    private static String inferSlot(String category, String statement) {
        String text = statement == null ? "" : statement.toLowerCase(Locale.ROOT);
        if (containsAny(text, "语言", "英文", "中文", "language")) {
            return "answer.language";
        }
        if (containsAny(text, LENGTH_TERMS)) {
            return "answer.length";
        }
        if (containsAny(text, "表格", "table")) {
            return "format.table";
        }
        return switch (category == null ? "" : category) {
            case "forbidden" -> "answer.constraint";
            case "terminology" -> "terminology.term";
            case "structure" -> "format.structure";
            case "interaction" -> "interaction.style";
            default -> "answer.style";
        };
    }

    private static boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    public static String normalizeSlot(String slot) {
        if (slot == null || slot.isBlank()) {
            return null;
        }
        String normalized = slot.trim().toLowerCase(Locale.ROOT);
        return SLOT.matcher(normalized).matches() ? normalized : null;
    }

    /** 接口上的槽位。身份键和无法归类的值都显示为 unknown，不写回唯一列。 */
    public static String publicSlot(String stored) {
        if (stored == null || stored.isBlank()) {
            return "unknown";
        }
        String value = stored.trim();
        if (value.startsWith("legacy:") || value.startsWith("candidate:")) {
            return "unknown";
        }
        String normalized = normalizeSlot(value);
        return normalized == null ? "unknown" : normalized;
    }

    private static String clean(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\r', ' ').replace('\n', ' ').trim().replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
    }
}
