package com.noteweave.memory;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 把偏好整理成槽位、取值和极性。
 * <p>
 * 槽位只由模型输出决定（抽取 schema 里的 slot 字段）：这里只做规范化和校验，
 * 不再按关键词从语句里推断槽位。模型没给槽位、或给的槽位不合规范时，
 * 退回到模型同时给出的 category 对应的默认槽位，仍然不看语句文本。
 * 槽位用受控前缀加自由后缀，例如 answer.language、terminology.术语。
 */
public final class MemorySlots {
    /**
     * 抽取模型允许输出的槽位枚举。提示词里的说明直接引用这份定义，避免 schema 与代码各写一份。
     */
    public static final List<String> ALLOWED_SLOTS = List.of(
            "answer.language",
            "answer.length",
            "answer.style",
            "answer.constraint",
            "format.structure",
            "format.table",
            "terminology.term",
            "interaction.style");
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
            resolvedSlot = slotForCategory(category);
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

    /**
     * 模型没给出合法槽位时的兜底：只按模型给出的 category 映射到默认槽位。
     * 这里刻意不读语句文本——「中文」「表格」「简短」这些词不再决定槽位。
     */
    static String slotForCategory(String category) {
        return switch (category == null ? "" : category) {
            case "forbidden" -> "answer.constraint";
            case "terminology" -> "terminology.term";
            case "structure" -> "format.structure";
            case "interaction" -> "interaction.style";
            default -> "answer.style";
        };
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
