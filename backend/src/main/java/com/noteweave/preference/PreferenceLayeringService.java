package com.noteweave.preference;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * F5-01/F5-02：偏好分层覆盖与任务参数编译。
 *
 * <p>只在同一细粒度目标键、同一语义维度上覆盖；被覆盖的条目仍然保留在结果里并记录原因，
 * 不删除生命周期。参数编译只输出调用方真正支持的字段，不支持的目标键给出稳定原因，不静默丢弃。</p>
 */
public final class PreferenceLayeringService {

    private PreferenceLayeringService() {
    }

    public static Resolution resolve(List<PreferenceEntry> entries) {
        Map<String, List<PreferenceEntry>> byTarget = new LinkedHashMap<>();
        if (entries != null) {
            for (PreferenceEntry entry : entries) {
                if (entry != null) {
                    byTarget.computeIfAbsent(entry.targetKey(), ignored -> new ArrayList<>()).add(entry);
                }
            }
        }
        List<PreferenceEntry> active = new ArrayList<>();
        List<Superseded> superseded = new ArrayList<>();
        for (Map.Entry<String, List<PreferenceEntry>> group : byTarget.entrySet()) {
            List<PreferenceEntry> sorted = new ArrayList<>(group.getValue());
            sorted.sort(Comparator
                    .comparingInt((PreferenceEntry entry) -> entry.layer().precedence())
                    .thenComparingInt(PreferenceEntry::sameLayerRank)
                    .thenComparing(Comparator.comparingLong(PreferenceEntry::sequence).reversed()));
            PreferenceEntry chosen = sorted.get(0);
            active.add(chosen);
            for (int index = 1; index < sorted.size(); index++) {
                PreferenceEntry candidate = sorted.get(index);
                String reason;
                if (sameMeaning(candidate, chosen)) {
                    reason = "DUPLICATE";
                } else if (candidate.layer() == chosen.layer()) {
                    reason = "SAME_LAYER_CONFLICT";
                } else {
                    reason = "OVERRIDDEN_BY_" + chosen.layer().wire().toUpperCase(java.util.Locale.ROOT);
                }
                superseded.add(new Superseded(candidate, chosen.targetKey(), reason));
            }
        }
        return new Resolution(List.copyOf(active), List.copyOf(superseded));
    }

    public static TaskParameters compile(List<PreferenceEntry> active) {
        Map<String, String> parameters = new LinkedHashMap<>();
        List<Unsupported> unsupported = new ArrayList<>();
        Set<String> terminology = new LinkedHashSet<>();
        if (active != null) {
            for (PreferenceEntry entry : active) {
                String key = entry.targetKey();
                String value = entry.value() == null ? "" : entry.value().trim();
                switch (key) {
                    case "answer.language" -> parameters.put("language", value);
                    case "format.length.max" -> putInteger(entry, value, "max_chars", parameters, unsupported);
                    case "format.length.detail" -> parameters.put("detail", value);
                    case "format.table" -> parameters.put("table", Boolean.toString(!entry.forbids()));
                    case "format.bullets" -> parameters.put("bullets", Boolean.toString(!entry.forbids()));
                    case "format.conclusion" -> parameters.put("conclusion_first",
                            Boolean.toString(!entry.forbids()));
                    case "format.citation" -> parameters.put("citations",
                            Boolean.toString(!entry.forbids()));
                    case "format.code" -> parameters.put("code", Boolean.toString(!entry.forbids()));
                    default -> {
                        if (key.startsWith("terminology")) {
                            if (!value.isBlank()) {
                                terminology.add(value);
                            }
                        } else if (key.startsWith("source.")) {
                            parameters.put("source_policy", value);
                        } else {
                            unsupported.add(new Unsupported(key, "UNSUPPORTED_TARGET"));
                        }
                    }
                }
            }
        }
        if (!terminology.isEmpty()) {
            parameters.put("terminology", String.join(",", terminology));
        }
        return new TaskParameters(Map.copyOf(parameters), List.copyOf(unsupported));
    }

    private static boolean sameMeaning(PreferenceEntry left, PreferenceEntry right) {
        String leftValue = left.value() == null ? "" : left.value().trim();
        String rightValue = right.value() == null ? "" : right.value().trim();
        return leftValue.equals(rightValue) && left.polarity().equals(right.polarity());
    }

    private static void putInteger(PreferenceEntry entry, String value, String key,
                                   Map<String, String> parameters, List<Unsupported> unsupported) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed <= 0) {
                unsupported.add(new Unsupported(entry.targetKey(), "INVALID_VALUE"));
                return;
            }
            parameters.put(key, Integer.toString(parsed));
        } catch (NumberFormatException ex) {
            unsupported.add(new Unsupported(entry.targetKey(), "INVALID_VALUE"));
        }
    }

    /** 分层结果：生效条目与被覆盖/合并的条目及原因。 */
    public record Resolution(List<PreferenceEntry> active, List<Superseded> superseded) {
        public Resolution {
            active = active == null ? List.of() : List.copyOf(active);
            superseded = superseded == null ? List.of() : List.copyOf(superseded);
        }
    }

    public record Superseded(PreferenceEntry entry, String keptTargetKey, String reason) {
    }

    /** 任务参数编译结果；unsupported 不能静默丢弃。 */
    public record TaskParameters(Map<String, String> parameters, List<Unsupported> unsupported) {
        public TaskParameters {
            parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
            unsupported = unsupported == null ? List.of() : List.copyOf(unsupported);
        }
    }

    public record Unsupported(String targetKey, String reason) {
    }
}
