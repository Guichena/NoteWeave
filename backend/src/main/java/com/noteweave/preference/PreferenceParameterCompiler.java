package com.noteweave.preference;

import com.noteweave.memory.MemoryControlPackResponse;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * F5-02：把长期记忆控制包里的结构化偏好编译成任务参数。
 *
 * <p>只做确定性、可解释的识别：语言、字数、详略、表格、分点、结论先行、引用、代码与术语。
 * 无法稳定识别的条目保留在原有控制包文本里，标记为 {@code UNSUPPORTED_TARGET}，
 * 不静默丢弃，也不假装已经生效。</p>
 */
public final class PreferenceParameterCompiler {

    private static final Pattern MAX_CHARS = Pattern.compile(
            "(?:不超过|控制在|最多|限制为)?\\s*(\\d{1,4})\\s*字(?:以内|以下|之内)?");

    private PreferenceParameterCompiler() {
    }

    public static Compiled compile(MemoryControlPackResponse pack) {
        if (pack == null) {
            return new Compiled("", List.of(), Set.of());
        }
        List<PreferenceEntry> entries = new ArrayList<>();
        long sequence = 1;
        Set<String> handled = new LinkedHashSet<>();

        for (String value : safe(pack.styleConstraints())) {
            String normalized = value == null ? "" : value.strip();
            if (normalized.isEmpty()) {
                continue;
            }
            String language = language(normalized);
            if (language != null) {
                entries.add(entry("answer.language", PreferenceSlotFamily.LANGUAGE, language, sequence++));
                handled.add(normalized);
            }
            Matcher maxChars = MAX_CHARS.matcher(normalized);
            if (maxChars.find()) {
                entries.add(entry("format.length.max", PreferenceSlotFamily.LENGTH,
                        maxChars.group(1), sequence++));
                handled.add(normalized);
            }
            String detail = detail(normalized);
            if (detail != null) {
                entries.add(entry("format.length.detail", PreferenceSlotFamily.LENGTH, detail, sequence++));
                handled.add(normalized);
            }
            if (containsAny(normalized, "代码", "code", "示例")) {
                entries.add(entry("format.code", PreferenceSlotFamily.FORMAT, "", sequence++));
                handled.add(normalized);
            }
        }
        for (String value : safe(pack.structureConstraints())) {
            String normalized = value == null ? "" : value.strip();
            if (normalized.isEmpty()) {
                continue;
            }
            if (containsAny(normalized, "表格", "table")) {
                entries.add(entry("format.table", PreferenceSlotFamily.FORMAT, "", sequence++));
                handled.add(normalized);
            }
            if (containsAny(normalized, "分点", "列表", "要点", "bullet")) {
                entries.add(entry("format.bullets", PreferenceSlotFamily.FORMAT, "", sequence++));
                handled.add(normalized);
            }
            if (containsAny(normalized, "先给结论", "结论先行", "先结论", "结论开头")) {
                entries.add(entry("format.conclusion", PreferenceSlotFamily.FORMAT, "", sequence++));
                handled.add(normalized);
            }
        }
        for (String value : safe(pack.evidencePolicy())) {
            String normalized = value == null ? "" : value.strip();
            if (normalized.isEmpty()) {
                continue;
            }
            if (containsAny(normalized, "标注来源", "引用", "出处", "source")) {
                entries.add(entry("format.citation", PreferenceSlotFamily.FORMAT, "", sequence++));
                handled.add(normalized);
            }
        }
        for (String value : safe(pack.forbiddenPatterns())) {
            String normalized = value == null ? "" : value.strip();
            if (normalized.isEmpty()) {
                continue;
            }
            if (containsAny(normalized, "表格", "table")) {
                entries.add(forbid("format.table", PreferenceSlotFamily.FORMAT, sequence++));
                handled.add(normalized);
            }
            if (containsAny(normalized, "分点", "列表", "要点", "bullet")) {
                entries.add(forbid("format.bullets", PreferenceSlotFamily.FORMAT, sequence++));
                handled.add(normalized);
            }
            if (containsAny(normalized, "引用", "来源", "出处", "source")) {
                entries.add(forbid("format.citation", PreferenceSlotFamily.FORMAT, sequence++));
                handled.add(normalized);
            }
        }
        int terminologyIndex = 0;
        for (String value : safe(pack.terminologyPolicy())) {
            if (value != null && !value.isBlank()) {
                entries.add(new PreferenceEntry(
                        "terminology." + terminologyIndex++, PreferenceSlotFamily.TERMINOLOGY,
                        value.strip(), "REQUIRE", PreferenceLayer.LONG_TERM,
                        "WORKSPACE", "workspace", "memory-control-pack", "v1", sequence++));
                handled.add(value.strip());
            }
        }

        PreferenceLayeringService.TaskParameters parameters =
                PreferenceLayeringService.compile(PreferenceLayeringService.resolve(entries).active());
        List<PreferenceLayeringService.Unsupported> unsupported = new ArrayList<>(parameters.unsupported());
        for (String value : safe(pack.interactionPolicy())) {
            if (value != null && !value.isBlank() && !handled.contains(value.strip())) {
                unsupported.add(new PreferenceLayeringService.Unsupported(
                        "interaction_policy", "UNSUPPORTED_TARGET"));
            }
        }
        return new Compiled(promptLine(parameters.parameters()),
                List.copyOf(unsupported), Set.copyOf(handled));
    }

    static String promptLine(Map<String, String> parameters) {
        if (parameters == null || parameters.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder("结构化任务参数（优先执行）：");
        boolean first = true;
        for (Map.Entry<String, String> entry : parameters.entrySet()) {
            if (!first) {
                builder.append("；");
            }
            first = false;
            builder.append(entry.getKey());
            if (!entry.getValue().isBlank()) {
                builder.append('=').append(entry.getValue());
            }
        }
        return builder.toString();
    }

    private static PreferenceEntry entry(String key, PreferenceSlotFamily family, String value,
                                         long sequence) {
        return new PreferenceEntry(key, family, value, "REQUIRE", PreferenceLayer.LONG_TERM,
                "WORKSPACE", "workspace", "memory-control-pack", "v1", sequence);
    }

    private static PreferenceEntry forbid(String key, PreferenceSlotFamily family, long sequence) {
        return new PreferenceEntry(key, family, "", "FORBID", PreferenceLayer.LONG_TERM,
                "WORKSPACE", "workspace", "memory-control-pack", "v1", sequence);
    }

    private static String language(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        if (normalized.contains("中文") || normalized.contains("汉语")) {
            return "中文";
        }
        if (normalized.contains("英文") || normalized.contains("英语")
                || normalized.contains("english") || normalized.contains("chinese")) {
            return "English";
        }
        return null;
    }

    private static String detail(String value) {
        if (containsAny(value, "简短", "简洁", "精简", "别太长")) {
            return "short";
        }
        if (containsAny(value, "详细", "展开", "深入")) {
            return "detailed";
        }
        return null;
    }

    private static boolean containsAny(String value, String... needles) {
        String normalized = value.toLowerCase(Locale.ROOT);
        for (String needle : needles) {
            if (normalized.contains(needle.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static List<String> safe(List<String> values) {
        return values == null ? List.of() : values;
    }

    /**
     * @param promptLine  可直接附加到控制包文本后的结构化参数行，空串表示无参数
     * @param unsupported 未能稳定映射的条目，调用方必须保留原有文本
     * @param consumed    已被结构化参数吸收的原始文本，调用方必须从旧控制包中移除，避免两套输入
     */
    public record Compiled(String promptLine,
                           List<PreferenceLayeringService.Unsupported> unsupported,
                           Set<String> consumed) {
        public Compiled {
            promptLine = promptLine == null ? "" : promptLine;
            unsupported = unsupported == null ? List.of() : List.copyOf(unsupported);
            consumed = consumed == null ? Set.of() : Set.copyOf(consumed);
        }
    }
}
