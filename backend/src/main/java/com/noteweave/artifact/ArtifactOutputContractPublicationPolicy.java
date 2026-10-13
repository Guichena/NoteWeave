package com.noteweave.artifact;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 宿主校验发布目录中的契约元数据，执行时由 Worker 完成 JSON Schema 和规则校验。 */
final class ArtifactOutputContractPublicationPolicy {
    private static final Set<String> RULES = Set.of("bullet_count", "numbered_item_count", "section_terms",
            "section_nonempty", "focus_coverage", "no_fabricated_metrics", "quiz_answer_in_options");

    private ArtifactOutputContractPublicationPolicy() { }

    static void validate(Object value) {
        Map<?, ?> contract = object(value);
        require(Set.of("schema_version", "sections", "fields", "json_schema", "citation", "rules")
                .containsAll(contract.keySet()));
        require("artifact-output-contract.v1".equals(contract.get("schema_version")));
        Map<?, ?> sections = object(contract.get("sections"));
        require(Set.of("min", "max", "required_headings").containsAll(sections.keySet()));
        int min = boundedInteger(sections.get("min"), 1, 80);
        int max = boundedInteger(sections.get("max"), 1, 80);
        require(min <= max);
        List<?> headings = array(sections.get("required_headings"));
        require(headings.size() <= max && new HashSet<>(headings).size() == headings.size());
        headings.forEach(h -> require(h instanceof String text && !text.isBlank() && text.length() <= 200));
        Map<?, ?> fields = object(contract.get("fields"));
        for (Object raw : fields.values()) {
            Map<?, ?> field = object(raw);
            require(!field.containsKey("required") || field.get("required") instanceof Boolean);
        }
        Map<?, ?> citation = object(contract.get("citation"));
        require(Set.of("density", "ref_kind").containsAll(citation.keySet()));
        require(citation.get("density") instanceof String density && Set.of("NONE", "OPTIONAL", "PER_SECTION").contains(density));
        require(citation.get("ref_kind") instanceof String kind && Set.of("SOURCE", "CHUNK").contains(kind));
        List<?> rules = array(contract.get("rules"));
        require(rules.size() <= 32);
        Set<String> names = new HashSet<>();
        for (Object raw : rules) {
            Map<?, ?> rule = object(raw);
            require(Set.of("name", "level", "backtrack_to", "parameters", "label").containsAll(rule.keySet()));
            require(rule.get("name") instanceof String name && RULES.contains(name) && names.add(name));
            require(rule.get("level") instanceof String level && Set.of("ASSERT", "SUGGEST").contains(level));
            require(rule.get("backtrack_to") instanceof String node && node.matches("[a-z][a-z0-9_]{0,99}"));
            validateParameters(String.valueOf(rule.get("name")), object(rule.get("parameters")));
        }
        if (contract.containsKey("json_schema")) object(contract.get("json_schema"));
        rejectReferences(contract, 0);
    }

    private static void validateParameters(String name, Map<?, ?> parameters) {
        Set<String> allowed = switch (name) {
            case "bullet_count", "numbered_item_count" -> Set.of("section_index", "min", "max");
            case "section_terms" -> Set.of("section_index", "groups");
            case "section_nonempty", "quiz_answer_in_options" -> Set.of("section_index");
            default -> Set.of();
        };
        require(allowed.containsAll(parameters.keySet()));
        if (allowed.contains("section_index")) boundedInteger(parameters.get("section_index"), 0, 79);
        int min = parameters.containsKey("min") ? boundedInteger(parameters.get("min"), 0, 80) : 0;
        int max = parameters.containsKey("max") ? boundedInteger(parameters.get("max"), 0, 80) : 80;
        require(min <= max);
        if ("section_terms".equals(name)) {
            List<?> groups = array(parameters.get("groups"));
            require(!groups.isEmpty() && groups.size() <= 20);
            for (Object group : groups) {
                List<?> terms = array(group);
                require(!terms.isEmpty() && terms.size() <= 10);
                terms.forEach(term -> require(term instanceof String text && !text.isBlank() && text.length() <= 200));
            }
        }
    }

    private static int boundedInteger(Object value, int min, int max) {
        require(value instanceof Number number && number.doubleValue() == number.intValue()
                && number.intValue() >= min && number.intValue() <= max);
        return ((Number) value).intValue();
    }

    private static Map<?, ?> object(Object value) {
        require(value instanceof Map<?, ?>);
        return (Map<?, ?>) value;
    }

    private static List<?> array(Object value) {
        require(value instanceof List<?>);
        return (List<?>) value;
    }

    private static void rejectReferences(Object value, int depth) {
        require(depth <= 64);
        if (value instanceof Map<?, ?> map) {
            require(!map.containsKey("$ref") && !map.containsKey("$dynamicRef"));
            map.values().forEach(child -> rejectReferences(child, depth + 1));
        } else if (value instanceof List<?> list) {
            list.forEach(child -> rejectReferences(child, depth + 1));
        }
    }

    private static void require(boolean valid) {
        if (!valid) throw new IllegalStateException("产物输出契约不符合发布规范");
    }
}
