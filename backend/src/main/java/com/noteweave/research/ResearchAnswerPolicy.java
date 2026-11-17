package com.noteweave.research;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** 冻结单项答案的验收条件，问题引文仅用于追溯，不在成文时扩充条件。 */
final class ResearchAnswerPolicy {
    static final String FLAG = "requirement_answer_policy_version";
    private static final Pattern IDENTIFIER = Pattern.compile(
            "(?i)\\b(?:PEP|RFC|JEP|CVE|ISO|IEC)\\b|编号|标题|规范名称");
    private static final Pattern IDENTITY_REQUEST = Pattern.compile(
            "(?i)identify|number|title|defines|defining|governing|编号|标题|定义|对应|名称");
    private static final Pattern SOURCE_REQUEST = Pattern.compile(
            "(?i)official|primary|documentation|cite|citation|reference|官方|一手|引用|出处|来源");

    static Map<String, Object> freeze(String text, String quote, String origin, String mode, int minimum,
                                      Map<String, Object> inherited, boolean hasExplicitDefinitions) {
        String temporal = ResearchSourceScopePolicy.temporalMode(text);
        if ("UNSPECIFIED".equals(temporal) && IDENTIFIER.matcher(text).find() && IDENTITY_REQUEST.matcher(text).find())
            temporal = "TIME_INVARIANT";
        if ("UNSPECIFIED".equals(temporal)) temporal = ResearchSourceScopePolicy.temporalMode(quote);
        if ("UNSPECIFIED".equals(temporal)) temporal = String.valueOf(inherited.getOrDefault("temporal_mode", temporal));
        boolean mandatory = !"DERIVED".equals(origin) || !hasExplicitDefinitions;
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("version", 1); answer.put("criterion", text); answer.put("temporal_mode", temporal);
        answer.put("completion_mode", mode); answer.put("minimum_count", minimum); answer.put("mandatory", mandatory);
        answer.put("source_constraints", SOURCE_REQUEST.matcher(quote).find()
                ? List.of(Map.of("policy", "PRIMARY_SOURCE", "question_quote", quote)) : List.of());
        return Map.copyOf(answer);
    }

    private ResearchAnswerPolicy() { }
}
