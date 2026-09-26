package com.noteweave.artifact;

import com.noteweave.capability.CapabilityCatalogPort;
import com.noteweave.common.BusinessException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class ArtifactSkillCatalogService implements CapabilityCatalogPort {

    private static final List<Map<String, String>> LANGUAGE_OPTIONS = List.of(
            Map.of("const", "zh-CN", "title", "中文（简体）"),
            Map.of("const", "en", "title", "English"),
            Map.of("const", "zh-EN", "title", "中英双语")
    );

    private final Map<String, ArtifactSkillDefinition> skillsByKey;
    private final Map<String, String> aliases;
    private final Map<String, String> actionBindings;

    public ArtifactSkillCatalogService() {
        Map<String, ArtifactSkillDefinition> skills = new LinkedHashMap<>();
        register(skills, new ArtifactSkillDefinition("resume_highlight", "简历亮点描述", languageOnlySchema()));
        register(skills, new ArtifactSkillDefinition("study_guide", "学习指南", languageOnlySchema()));
        register(skills, new ArtifactSkillDefinition("quiz_pack", "测验题集", languageOnlySchema()));
        register(skills, new ArtifactSkillDefinition("wiki_page", "Wiki 页面", languageOnlySchema()));
        register(skills, new ArtifactSkillDefinition("mindmap_from_workspace", "思维导图", mindMapSchema()));
        register(skills, new ArtifactSkillDefinition("bilibili_course_note_pdf", "B站讲义 PDF",
                languageAndUrlSchema(true, List.of("video_url", "bilibili_url"))));
        register(skills, new ArtifactSkillDefinition("report_draft", "结构化报告", languageOnlySchema()));
        register(skills, new ArtifactSkillDefinition("faq_draft", "FAQ 草稿", languageOnlySchema()));
        register(skills, new ArtifactSkillDefinition("structured_note", "结构化笔记", languageOnlySchema()));
        register(skills, new ArtifactSkillDefinition("video_summary", "视频总结",
                languageAndUrlSchema(true, List.of("video_url"))));
        register(skills, new ArtifactSkillDefinition("audio_minutes", "音频纪要", languageOnlySchema()));
        register(skills, new ArtifactSkillDefinition("course_notes", "课程笔记",
                languageAndUrlSchema(false, List.of("video_url"))));
        this.skillsByKey = Map.copyOf(skills);
        this.aliases = Map.of(
                "resume_highlights", "resume_highlight",
                "bilibili_pdf", "bilibili_course_note_pdf"
        );
        this.actionBindings = Map.ofEntries(
                Map.entry("resume_highlight", "RESUME_HIGHLIGHT"),
                Map.entry("study_guide", "STUDY_GUIDE"),
                Map.entry("quiz_pack", "QUIZ"),
                Map.entry("wiki_page", "WIKI_PAGE"),
                Map.entry("mindmap_from_workspace", "MINDMAP"),
                Map.entry("bilibili_course_note_pdf", "COURSE_NOTES"),
                Map.entry("report_draft", "REPORT"),
                Map.entry("faq_draft", "FAQ"),
                Map.entry("structured_note", "STRUCTURED_NOTE"),
                Map.entry("video_summary", "VIDEO_SUMMARY"),
                Map.entry("audio_minutes", "AUDIO_MINUTES"),
                Map.entry("course_notes", "COURSE_NOTES")
        );
    }

    public ArtifactSkillDefinition resolveSkill(String skillKey) {
        String canonicalSkillKey = canonicalSkillKey(skillKey);
        ArtifactSkillDefinition skill = skillsByKey.get(canonicalSkillKey);
        if (skill == null) {
            throw new BusinessException("ARTIFACT_SKILL_NOT_FOUND", "未找到对应的产物 Skill：" + skillKey);
        }
        return skill;
    }

    public String resolveActionKey(String skillKey) {
        return actionBindings.getOrDefault(canonicalSkillKey(skillKey), "");
    }

    @Override
    public CapabilityDescriptor requireCapability(String capabilityKey) {
        ArtifactSkillDefinition skill = resolveSkill(capabilityKey);
        return new CapabilityDescriptor(
                skill.skillKey(),
                resolveActionKey(skill.skillKey())
        );
    }

    public Map<String, Object> validateAndNormalizeInputs(
            ArtifactSkillDefinition skill,
            Map<String, Object> rawInputs
    ) {
        Map<String, Object> inputs = rawInputs == null ? Map.of() : rawInputs;
        Map<String, Object> schemaProperties = readSchemaProperties(skill.inputSchema());
        Set<String> allowedKeys = schemaProperties.keySet();
        LinkedHashSet<String> unsupportedKeys = new LinkedHashSet<>();
        for (String key : inputs.keySet()) {
            if (!allowedKeys.contains(key)) {
                unsupportedKeys.add(key);
            }
        }
        if (!unsupportedKeys.isEmpty()) {
            throw new BusinessException(
                    "ARTIFACT_SKILL_INPUT_UNSUPPORTED",
                    "产物 Skill 不支持以下输入字段：" + String.join(", ", unsupportedKeys)
            );
        }

        LinkedHashMap<String, Object> normalized = new LinkedHashMap<>();
        for (String key : allowedKeys) {
            if (!inputs.containsKey(key)) {
                Object defaultValue = readDefaultValue(schemaProperties.get(key));
                if (defaultValue != null) {
                    normalized.put(key, defaultValue);
                }
                continue;
            }
            Object normalizedValue = normalizeInputValue(key, schemaProperties.get(key), inputs.get(key));
            if (normalizedValue != null) {
                normalized.put(key, normalizedValue);
            }
        }
        if (schemaProperties.containsKey("url")) {
            String canonicalUrl = String.valueOf(normalized.getOrDefault("url", "")).trim();
            for (String alias : List.of("video_url", "bilibili_url")) {
                Object rawAlias = normalized.remove(alias);
                if (rawAlias == null) continue;
                String aliasUrl = String.valueOf(rawAlias).trim();
                if (aliasUrl.isEmpty()) continue;
                if (!canonicalUrl.isEmpty() && !canonicalUrl.equals(aliasUrl)) {
                    throw new BusinessException("ARTIFACT_SKILL_INPUT_CONFLICT",
                            "产物视频链接字段不一致：url 与 " + alias);
                }
                canonicalUrl = aliasUrl;
            }
            if (!canonicalUrl.isEmpty()) normalized.put("url", canonicalUrl);
        }

        List<String> missingRequiredKeys = readRequiredInputKeys(skill.inputSchema()).stream()
                .filter(requiredKey -> isMissingRequiredValue(requiredKey, schemaProperties.get(requiredKey), normalized.get(requiredKey)))
                .toList();
        if (!missingRequiredKeys.isEmpty()) {
            throw new BusinessException(
                    "ARTIFACT_SKILL_INPUT_REQUIRED",
                    "产物 Skill 缺少必填输入：" + String.join(", ", missingRequiredKeys)
            );
        }

        return Map.copyOf(normalized);
    }

    public List<ArtifactSkillSummaryResponse> listSkills() {
        return skillsByKey.values().stream()
                .map(skill -> new ArtifactSkillSummaryResponse(
                        skill.skillKey(),
                        skill.displayName(),
                        buildDescription(skill.skillKey()),
                        "ACTIVE",
                        publicInputSchema(skill.inputSchema()),
                        buildDefaultInputHints(skill.skillKey())
                ))
                .toList();
    }

    private void register(Map<String, ArtifactSkillDefinition> skills, ArtifactSkillDefinition skill) {
        skills.put(skill.skillKey(), skill);
    }

    private String canonicalSkillKey(String value) {
        String normalizedSkillKey = normalizeSkillKey(value);
        return aliases.getOrDefault(normalizedSkillKey, normalizedSkillKey);
    }

    private String normalizeSkillKey(String value) {
        return value == null
                ? ""
                : value.trim()
                        .toLowerCase(Locale.ROOT)
                        .replace('-', '_')
                        .replace(' ', '_');
    }

    private String buildDescription(String skillKey) {
        return switch (skillKey) {
            case "resume_highlight" -> "从当前工作台资料中生成适合简历书写的项目亮点";
            case "study_guide" -> "按知识点、关键概念和练习建议生成结构化学习材料";
            case "quiz_pack" -> "围绕当前资料生成题目、答案解析和评分要点";
            case "wiki_page" -> "沉淀成定义、机制、引用和相关页面齐全的知识页草稿";
            case "mindmap_from_workspace" -> "把当前工作台资料整理为可缩放、可折叠的交互式思维导图";
            case "bilibili_course_note_pdf" -> "面向 B 站视频链接生成图文讲义与 PDF 讲义任务";
            case "report_draft" -> "生成结构化报告草稿";
            case "faq_draft" -> "生成 FAQ 草稿";
            case "structured_note" -> "生成结构化笔记";
            case "video_summary" -> "生成视频总结";
            case "audio_minutes" -> "生成音频纪要";
            case "course_notes" -> "生成课程笔记";
            default -> "NoteWeave 内置产物 Skill";
        };
    }

    private Map<String, Object> readSchemaProperties(Map<String, Object> inputSchema) {
        Object propertiesValue = inputSchema.get("properties");
        if (!(propertiesValue instanceof Map<?, ?> rawProperties)) {
            return Map.of();
        }
        LinkedHashMap<String, Object> properties = new LinkedHashMap<>();
        rawProperties.forEach((key, value) -> properties.put(String.valueOf(key), value));
        return properties;
    }

    private Map<String, Object> publicInputSchema(Map<String, Object> inputSchema) {
        LinkedHashMap<String, Object> properties = new LinkedHashMap<>(readSchemaProperties(inputSchema));
        properties.remove("video_url");
        properties.remove("bilibili_url");
        LinkedHashMap<String, Object> publicSchema = new LinkedHashMap<>(inputSchema);
        publicSchema.put("properties", Map.copyOf(properties));
        return Map.copyOf(publicSchema);
    }

    private List<String> readRequiredInputKeys(Map<String, Object> inputSchema) {
        Object requiredValue = inputSchema.get("required");
        if (!(requiredValue instanceof List<?> rawRequiredValues)) {
            return List.of();
        }
        ArrayList<String> requiredKeys = new ArrayList<>();
        for (Object item : rawRequiredValues) {
            String value = String.valueOf(item).trim();
            if (!value.isEmpty()) {
                requiredKeys.add(value);
            }
        }
        return List.copyOf(requiredKeys);
    }

    private Object normalizeInputValue(String key, Object schemaValue, Object rawValue) {
        String declaredType = readDeclaredType(schemaValue);
        if ("string".equals(declaredType)) {
            if (!(rawValue instanceof String stringValue)) {
                throw new BusinessException(
                        "ARTIFACT_SKILL_INPUT_TYPE_INVALID",
                        "产物 Skill 输入字段类型不正确：" + key + " 应为 string"
                );
            }
            String normalized = stringValue.trim();
            if (normalized.isEmpty()) {
                return null;
            }
            validateAllowedStringValue(key, schemaValue, normalized);
            return normalized;
        }
        return rawValue;
    }

    private boolean isMissingRequiredValue(String key, Object schemaValue, Object normalizedValue) {
        if (normalizedValue == null) {
            return true;
        }
        if ("string".equals(readDeclaredType(schemaValue)) && normalizedValue instanceof String stringValue) {
            return stringValue.isBlank();
        }
        return false;
    }

    private String readDeclaredType(Object schemaValue) {
        if (!(schemaValue instanceof Map<?, ?> schemaMap)) {
            return "";
        }
        Object typeValue = schemaMap.get("type");
        return typeValue == null ? "" : String.valueOf(typeValue).trim().toLowerCase(Locale.ROOT);
    }

    private Object readDefaultValue(Object schemaValue) {
        if (!(schemaValue instanceof Map<?, ?> schemaMap)) {
            return null;
        }
        Object defaultValue = schemaMap.get("default");
        if (defaultValue instanceof String stringValue) {
            String normalized = stringValue.trim();
            return normalized.isEmpty() ? null : normalized;
        }
        return defaultValue;
    }

    private void validateAllowedStringValue(String key, Object schemaValue, String normalizedValue) {
        Set<String> enumValues = readAllowedStringValues(schemaValue);
        if (!enumValues.isEmpty() && !enumValues.contains(normalizedValue)) {
            throw new BusinessException(
                    "ARTIFACT_SKILL_INPUT_ENUM_INVALID",
                    "产物 Skill 输入字段取值不受支持：" + key + "=" + normalizedValue
            );
        }
    }

    private Set<String> readAllowedStringValues(Object schemaValue) {
        if (!(schemaValue instanceof Map<?, ?> schemaMap)) {
            return Set.of();
        }
        LinkedHashSet<String> values = new LinkedHashSet<>();
        Object enumValue = schemaMap.get("enum");
        if (enumValue instanceof List<?> rawEnumValues) {
            for (Object item : rawEnumValues) {
                String value = String.valueOf(item).trim();
                if (!value.isEmpty()) {
                    values.add(value);
                }
            }
        }
        Object oneOfValue = schemaMap.get("oneOf");
        if (oneOfValue instanceof List<?> rawOptions) {
            for (Object item : rawOptions) {
                if (!(item instanceof Map<?, ?> optionMap)) {
                    continue;
                }
                Object constValue = optionMap.get("const");
                if (constValue == null) {
                    continue;
                }
                String value = String.valueOf(constValue).trim();
                if (!value.isEmpty()) {
                    values.add(value);
                }
            }
        }
        return Set.copyOf(values);
    }

    private Map<String, Object> languageOnlySchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "language", languageSchema()
                )
        );
    }

    private Map<String, Object> mindMapSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("language", languageSchema());
        properties.put("layout", Map.of(
                "type", "string",
                "default", "balanced",
                "oneOf", List.of(
                        Map.of("const", "balanced", "title", "均衡分支"),
                        Map.of("const", "compact", "title", "紧凑概览")
                )
        ));
        properties.put("depth", Map.of(
                "type", "string",
                "default", "3",
                "oneOf", List.of(
                        Map.of("const", "2", "title", "2 层，快速浏览"),
                        Map.of("const", "3", "title", "3 层，推荐"),
                        Map.of("const", "4", "title", "4 层，详细")
                )
        ));
        return Map.of("type", "object", "properties", Map.copyOf(properties));
    }

    private Map<String, Object> languageAndUrlSchema(boolean required, List<String> aliases) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("language", languageSchema());
        properties.put("url", Map.of("type", "string"));
        for (String alias : aliases) properties.put(alias, Map.of("type", "string"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (required) {
            schema.put("required", List.of("url"));
        }
        return Map.copyOf(schema);
    }

    private Map<String, Object> languageSchema() {
        return Map.of(
                "type", "string",
                "default", "zh-CN",
                "oneOf", LANGUAGE_OPTIONS
        );
    }

    private List<String> buildDefaultInputHints(String skillKey) {
        return switch (skillKey) {
            case "resume_highlight" -> List.of("强调架构设计", "强调工程复杂度", "适合校招简历");
            case "study_guide" -> List.of("突出关键概念", "加入练习路径", "适合新人上手");
            case "quiz_pack" -> List.of("区分题型难度", "附标准答案", "保留评分要点");
            case "wiki_page" -> List.of("定义先行", "补充关键机制", "保留相关页面建议");
            case "mindmap_from_workspace" -> List.of("4 到 7 条主分支", "节点使用短语", "保留来源线索");
            case "bilibili_course_note_pdf" -> List.of("填写 B 站视频链接", "保留章节结构", "输出讲义 PDF");
            case "report_draft" -> List.of("问题-方法-效果", "保留关键证据", "适合方案沉淀");
            case "faq_draft" -> List.of("面向帮助中心", "问题答案成对", "补充使用说明");
            case "structured_note" -> List.of("沉淀主题快照", "保留关键摘录", "补充后续问题");
            case "video_summary" -> List.of("填写视频链接", "突出核心观点", "保留时间线摘要");
            case "audio_minutes" -> List.of("总结会议结论", "补充行动项", "标注待确认问题");
            case "course_notes" -> List.of("突出知识点", "补充重点难点", "附复习题");
            default -> List.of("按当前 Skill 默认结构生成");
        };
    }
}
