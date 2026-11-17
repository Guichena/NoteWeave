package com.noteweave.research;

import com.noteweave.common.BusinessException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** 独立核对显式需求的原文、对象、维度和数量，不能只信任 Worker 的标注。 */
final class ResearchRequirementDefinitions {
    private static final Map<String, Integer> WORDS = Map.ofEntries(
            Map.entry("zero", 0), Map.entry("one", 1), Map.entry("two", 2), Map.entry("three", 3),
            Map.entry("four", 4), Map.entry("five", 5), Map.entry("six", 6), Map.entry("seven", 7),
            Map.entry("eight", 8), Map.entry("nine", 9), Map.entry("ten", 10),
            Map.entry("一", 1), Map.entry("二", 2), Map.entry("两", 2), Map.entry("三", 3), Map.entry("四", 4),
            Map.entry("五", 5), Map.entry("六", 6), Map.entry("七", 7), Map.entry("八", 8), Map.entry("九", 9), Map.entry("十", 10));
    private static final Pattern COUNT = Pattern.compile(
            "(?:至少|不少于|最少|at\\s+least|no\\s+fewer\\s+than|minimum(?:\\s+of)?)?\\s*"
            + "(\\d+|zero|one|two|three|four|five|six|seven|eight|nine|ten|[一二两三四五六七八九十]+)"
            + "\\s*(?:[a-z]+(?:-[a-z]+)*\\s+){0,4}(?:个|项|种|条|家|篇|组|features?|changes?|findings?|products?|systems?|"
            + "databases?|frameworks?|studies|papers?|organizations?|implementations?|examples?|reasons?|limitations?)(?:\\D*)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);

    static List<Map<String, Object>> validate(Object raw, String question,
                                              List<ResearchPlanningService.ValidatedColumn> columns,
                                              List<ResearchPlanningService.ValidatedEntity> entities,
                                              boolean required) {
        return validate(raw, question, columns, entities, required, false);
    }

    static List<Map<String, Object>> validate(Object raw, String question,
                                              List<ResearchPlanningService.ValidatedColumn> columns,
                                              List<ResearchPlanningService.ValidatedEntity> entities,
                                              boolean required, boolean structured) {
        if (raw == null && !required) return List.of();
        if (!required && raw instanceof List<?> optional && optional.isEmpty()) return List.of();
        if (!(raw instanceof List<?> list) || list.isEmpty() || list.size() > 24) throw invalid("显式需求数量不符合约定");
        Map<String, String> dimensionKeys = new LinkedHashMap<>();
        columns.forEach(column -> dimensionKeys.put(normalized(column.label()).toLowerCase(Locale.ROOT), column.key()));
        Map<String, String> entityKeys = new LinkedHashMap<>();
        entities.forEach(entity -> entityKeys.put(normalized(entity.label()).toLowerCase(Locale.ROOT), entity.key()));
        List<Map<String, Object>> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Object value : list) {
            if (!(value instanceof Map<?, ?> item)) throw invalid("显式需求必须为对象");
            String text = normalized(item.get("text"));
            String quote = normalized(item.get("question_quote"));
            String column = dimensionKeys.get(normalized(item.get("dimension")).toLowerCase(Locale.ROOT));
            if (text.isEmpty() || text.codePointCount(0, text.length()) > 1000 || quote.isEmpty()
                    || quote.codePointCount(0, quote.length()) > 1000 || !normalized(question).contains(quote) || column == null)
                throw invalid("显式需求缺少原问题依据或对应维度");
            String mode = normalized(item.get("completion_mode"));
            Object number = item.get("minimum_count");
            if (!(number instanceof Integer) && !(number instanceof Long)) throw invalid("需求数量必须为整数");
            long count = ((Number) number).longValue();
            String expression = normalized(item.get("count_expression"));
            if (!Set.of("SINGLE", "DISTINCT_FACTS", "DISTINCT_ENTITIES", "COMPARISON").contains(mode) || count < 1 || count > 20)
                throw invalid("需求完成条件不符合约定");
            if ("SINGLE".equals(mode)) {
                if (count != 1 || !expression.isEmpty()) throw invalid("单项需求不能声明额外数量");
            } else if (!"COMPARISON".equals(mode) && (expression.isEmpty() || !quote.contains(expression) || countValue(expression) != count))
                throw invalid("需求数量与用户原文不一致");
            if (!(item.get("entities") instanceof List<?> subjects)) throw invalid("需求适用对象必须为列表");
            Set<String> bound = new java.util.TreeSet<>();
            for (Object subject : subjects) {
                String key = entityKeys.get(normalized(subject).toLowerCase(Locale.ROOT));
                if (key == null) throw invalid("需求引用了未规划的对象");
                bound.add(key);
            }
            if ("COMPARISON".equals(mode) && (bound.size() < 2 || count != bound.size() || !expression.isEmpty()))
                throw invalid("比较需求必须明确绑定至少两个对象，数量与对象数一致");
            String identity = ResearchRequirementCoverageService.key("definition:", text, column, mode, count, bound);
            if (!seen.add(identity)) continue;
            Map<String, Object> definition = new LinkedHashMap<>();
            definition.put("text", text); definition.put("question_quote", quote); definition.put("dimension_key", column);
            definition.put("entity_keys", List.copyOf(bound)); definition.put("origin", "EXPLICIT");
            definition.put("completion_mode", mode); definition.put("minimum_count", (int) count);
            definition.put("count_expression", expression);
            definition.put("scope_type", Set.of("DISTINCT_ENTITIES","COMPARISON").contains(mode) ? "RUN" : "CELL");
            if (structured) definition.putAll(ResearchCompletionGapPolicy.fields(item, question));
            result.add(Map.copyOf(definition));
        }
        return List.copyOf(result);
    }

    static int countValue(String expression) {
        var numbers = Pattern.compile("\\d+|\\b(?:zero|one|two|three|four|five|six|seven|eight|nine|ten)\\b|[一二两三四五六七八九十]+",
                Pattern.CASE_INSENSITIVE).matcher(normalized(expression));
        if (numbers.results().count() != 1) throw invalid("数量原文只能包含一个明确数值");
        var matcher = COUNT.matcher(normalized(expression));
        if (!matcher.matches()) throw invalid("数量原文需要明确数值及单位");
        String number = matcher.group(1).toLowerCase(Locale.ROOT);
        if (number.matches("[0-9]+")) {
            try { return Integer.parseInt(number); } catch (NumberFormatException error) { throw invalid("需求数量超出范围"); }
        }
        if (WORDS.containsKey(number)) return WORDS.get(number);
        String[] parts = number.split("十", -1);
        if (parts.length == 2 && (parts[0].isEmpty() || WORDS.containsKey(parts[0]))
                && (parts[1].isEmpty() || WORDS.containsKey(parts[1])))
            return (parts[0].isEmpty() ? 1 : WORDS.get(parts[0])) * 10 + (parts[1].isEmpty() ? 0 : WORDS.get(parts[1]));
        throw invalid("数量原文不符合约定");
    }

    private static String normalized(Object value) {
        return Normalizer.normalize(value == null ? "" : String.valueOf(value), Normalizer.Form.NFC).replaceAll("(?U)\\s+", " ").strip();
    }
    private static BusinessException invalid(String message) { return new BusinessException("RESEARCH_REQUIREMENT_DEFINITION_INVALID", message); }
}
