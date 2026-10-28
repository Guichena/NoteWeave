package com.noteweave.artifact;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noteweave.capability.CapabilityCatalogPort;
import com.noteweave.common.BusinessException;
import java.io.InputStream;
import java.net.URI;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.constructor.SafeConstructor;

@Service
public class ArtifactSkillCatalogService implements CapabilityCatalogPort {

    private final Map<String, ArtifactSkillDefinition> skillsByKey;
    private final Map<String, String> aliases;
    private final Map<String, String> actionBindings;
    private final Map<String, List<String>> requiredFileRoles;
    private final Map<String, String> publishedVersions;
    private final Map<String, Boolean> evidenceRequiredByKey;
    private final String catalogDigest;
    private final boolean pipelineEnabled;
    private final Map<String, String> kinds;
    /** 目录里的描述与展示信息：前端的产物卡片和默认输入提示都从这里读取，新增产物不需要改代码。 */
    private final Map<String, String> descriptions;
    private final Map<String, Map<String, Object>> presentations;

    public ArtifactSkillCatalogService() {
        this(System.getenv().getOrDefault("NOTEWEAVE_SKILLS_DIR", "../skills"),
                System.getenv().getOrDefault("NOTEWEAVE_SCHEMAS_DIR", "../schemas"),
                System.getenv().getOrDefault("NOTEWEAVE_ARTIFACT_PIPELINE", "v2"));
    }

    @Autowired
    public ArtifactSkillCatalogService(
            @Value("${noteweave.artifact.skills-dir:../skills}") String skillsDir,
            @Value("${noteweave.artifact.schemas-dir:../schemas}") String schemasDir,
            @Value("${noteweave.artifact.pipeline:v2}") String pipeline) {
        if (!Set.of("v2", "legacy").contains(pipeline)) {
            throw new IllegalStateException("产物流水线配置必须为 v2 或 legacy");
        }
        pipelineEnabled = pipeline.equals("v2");
        Map<String, Object> catalog;
        try {
            if (pipelineEnabled) {
                catalog = loadDirectoryCatalog(Path.of(skillsDir), Path.of(schemasDir));
                catalogDigest = String.valueOf(catalog.remove("digest"));
            } else {
                try (InputStream input = ArtifactSkillCatalogService.class.getResourceAsStream("/artifact-skill-catalog-v2.json")) {
                    if (input == null) throw new IllegalStateException("旧版产物目录不存在");
                    byte[] bytes = input.readAllBytes();
                    catalog = new ObjectMapper().readValue(bytes, new TypeReference<>() {});
                    catalogDigest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
                }
            }
        } catch (Exception error) {
            throw new IllegalStateException("无法加载产物 Skill 目录", error);
        }
        if (!Integer.valueOf(2).equals(catalog.get("catalog_version"))) {
            throw new IllegalStateException("unsupported artifact Skill catalog version");
        }
        Map<String, ArtifactSkillDefinition> skills = new LinkedHashMap<>();
        Map<String, Object> graphDefinitions = new LinkedHashMap<>();
        for (Object rawEntry : (List<?>) catalog.get("skills")) {
            if (!(rawEntry instanceof Map<?, ?> entry) || !(entry.get("input_schema") instanceof Map<?, ?> schema)) {
                throw new IllegalStateException("invalid artifact Skill catalog entry");
            }
            if (!pipelineEnabled) validatePublishedEntry(entry, schema);
            Object previousGraph = graphDefinitions.putIfAbsent(
                    String.valueOf(entry.get("graph_key")), entry.get("graph_definition"));
            if (previousGraph != null && !previousGraph.equals(entry.get("graph_definition"))) {
                throw new IllegalStateException("发布目录中共享技能图的声明不一致");
            }
            String key = String.valueOf(entry.get("skill_key"));
            Map<String, Object> inputSchema = new LinkedHashMap<>();
            schema.forEach((field, value) -> inputSchema.put(String.valueOf(field), value));
            if (skills.putIfAbsent(key, new ArtifactSkillDefinition(
                    key, String.valueOf(entry.get("display_name")), Map.copyOf(inputSchema))) != null) {
                throw new IllegalStateException("duplicate artifact Skill key: " + key);
            }
        }
        this.skillsByKey = java.util.Collections.unmodifiableMap(skills);
        Map<String, String> shapeKinds = new LinkedHashMap<>();
        for (Object raw : (List<?>) catalog.get("skills")) {
            Map<?, ?> entry = (Map<?, ?>) raw;
            String kind = entry.get("kind") == null ? switch (String.valueOf(entry.get("action_key"))) {
                case "MINDMAP" -> "TREE";
                case "QUIZ" -> "QUIZ";
                case "VIDEO_LEARNING_DECK" -> "DECK";
                default -> "DOC";
            } : String.valueOf(entry.get("kind"));
            shapeKinds.put(String.valueOf(entry.get("skill_key")), kind);
        }
        this.kinds = Map.copyOf(shapeKinds);
        Map<String, String> catalogAliases = new LinkedHashMap<>();
        ((Map<?, ?>) catalog.get("aliases")).forEach((key, value) ->
                catalogAliases.put(String.valueOf(key), String.valueOf(value)));
        this.aliases = Map.copyOf(catalogAliases);
        Map<String, String> bindings = new LinkedHashMap<>();
        Map<String, List<String>> requiredRoles = new LinkedHashMap<>();
        Map<String, String> versions = new LinkedHashMap<>();
        Map<String, Boolean> evidenceRequired = new LinkedHashMap<>();
        for (Object rawEntry : (List<?>) catalog.get("skills")) {
            Map<?, ?> entry = (Map<?, ?>) rawEntry;
            String key = String.valueOf(entry.get("skill_key"));
            versions.put(key, String.valueOf(entry.get("version")));
            Map<?, ?> contract = (Map<?, ?>) entry.get("output_contract");
            Map<?, ?> citation = (Map<?, ?>) contract.get("citation");
            evidenceRequired.put(key, "PER_SECTION".equals(citation.get("density")));
            bindings.put(key, String.valueOf(entry.get("action_key")));
            if (!(entry.get("required_file_roles") instanceof List<?> roles)) {
                throw new IllegalStateException("Skill has no required file roles: " + key);
            }
            List<String> names = roles.stream().map(String::valueOf).toList();
            if (names.isEmpty() || names.size() != Set.copyOf(names).size()
                    || !names.contains("PRIMARY_MARKDOWN")
                    || !Set.of("PRIMARY_MARKDOWN", "PRIMARY_PDF", "PRIMARY_PPTX", "SOURCE_MD", "SLIDE_PREVIEW", "PRIMARY_CSV")
                            .containsAll(names)) {
                throw new IllegalStateException("invalid required file roles: " + key);
            }
            requiredRoles.put(key, names);
        }
        Map<String, String> catalogDescriptions = new LinkedHashMap<>();
        Map<String, Map<String, Object>> catalogPresentations = new LinkedHashMap<>();
        for (Object rawEntry : (List<?>) catalog.get("skills")) {
            Map<?, ?> entry = (Map<?, ?>) rawEntry;
            String key = String.valueOf(entry.get("skill_key"));
            catalogDescriptions.put(key, String.valueOf(entry.get("description")));
            Map<String, Object> presentation = new LinkedHashMap<>();
            if (entry.get("presentation") instanceof Map<?, ?> raw) {
                raw.forEach((field, value) -> presentation.put(String.valueOf(field), value));
            }
            catalogPresentations.put(key, java.util.Collections.unmodifiableMap(presentation));
        }
        this.descriptions = Map.copyOf(catalogDescriptions);
        this.presentations = Map.copyOf(catalogPresentations);
        this.actionBindings = Map.copyOf(bindings);
        this.requiredFileRoles = Map.copyOf(requiredRoles);
        this.publishedVersions = Map.copyOf(versions);
        this.evidenceRequiredByKey = Map.copyOf(evidenceRequired);
    }

    public boolean pipelineEnabled() { return pipelineEnabled; }

    public String kind(String skillKey) {
        return kinds.getOrDefault(canonicalSkillKey(skillKey), "");
    }

    private static final Map<String, String> RULE_KINDS = Map.of(
            "sections", "DOC", "required_headings", "DOC", "quiz_single_answer", "QUIZ",
            "card_no_leak", "CARDS", "tree_depth", "TREE", "table_columns", "TABLE", "deck_bullets", "DECK");

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadDirectoryCatalog(Path directory, Path schemas) throws Exception {
        List<Path> paths;
        try (var stream = Files.list(directory)) {
            paths = stream.filter(Files::isDirectory).map(path -> path.resolve("SKILL.md"))
                    .filter(Files::isRegularFile).sorted().toList();
        }
        if (paths.isEmpty()) throw new IllegalStateException("Skill 目录为空");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        LoaderOptions yamlOptions = new LoaderOptions();
        yamlOptions.setAllowDuplicateKeys(false);
        Yaml yaml = new Yaml(new SafeConstructor(yamlOptions));
        List<Map<String, Object>> entries = new ArrayList<>();
        Map<String, String> aliases = new LinkedHashMap<>();
        Set<String> names = new java.util.HashSet<>();
        for (Path path : paths) {
            byte[] raw = Files.readAllBytes(path);
            String[] parts = new String(raw, StandardCharsets.UTF_8).split("---", 3);
            if (parts.length != 3 || !parts[0].isBlank() || parts[2].isBlank()) {
                throw new IllegalStateException("Skill 缺少 YAML 头部或生成指令");
            }
            Object loaded = yaml.load(parts[1]);
            if (!(loaded instanceof Map<?, ?>)) throw new IllegalStateException("Skill 头部必须是对象");
            Map<String, Object> header = (Map<String, Object>) loaded;
            String name = String.valueOf(header.get("name"));
            String kind = String.valueOf(header.get("kind"));
            if (!name.equals(path.getParent().getFileName().toString()) || !name.matches("[a-z][a-z0-9-]*")
                    || !names.add(name) || !(header.get("description") instanceof String) || ((String) header.get("description")).isBlank()
                    || !(header.get("version") instanceof Integer version) || version < 1
                    || !Set.of("DOC", "QUIZ", "CARDS", "TREE", "TABLE", "DECK").contains(kind)) {
                throw new IllegalStateException("Skill 名称、形态、描述或版本无效");
            }
            Map<String, Object> shape = new ObjectMapper().readValue(Files.readAllBytes(
                    schemas.resolve(kind.toLowerCase(Locale.ROOT) + ".v1.json")), Map.class);
            if (!(shape.get("properties") instanceof Map<?, ?> props)
                    || !Map.of("const", kind).equals(props.get("kind"))) {
                throw new IllegalStateException("Skill 形态与 Schema 不一致");
            }
            Map<String, Object> contract = objectField(header, "contract");
            validateDirectoryContract(kind, contract);
            Map<String, Object> options = objectField(header, "options");
            Map<String, Object> properties = new LinkedHashMap<>();
            for (var option : options.entrySet()) {
                if (!(option.getValue() instanceof Map<?, ?> rule)
                        || !Set.of("choices", "default").containsAll(rule.keySet())
                        || !(rule.get("choices") instanceof List<?> choices) || choices.isEmpty()
                        || choices.stream().anyMatch(v -> !(v instanceof String text) || text.isBlank())
                        || choices.size() != Set.copyOf(choices).size() || !choices.contains(rule.get("default"))) {
                    throw new IllegalStateException("Skill 选项或默认值无效");
                }
                properties.put(option.getKey(), Map.of("type", "string", "enum", rule.get("choices"), "default", rule.get("default")));
            }
            if (!(header.get("renderers") instanceof List<?> renderers) || !renderers.contains("MARKDOWN")
                    || renderers.size() != Set.copyOf(renderers).size()
                    || !(switch (kind) { case "TABLE" -> Set.of("MARKDOWN", "CSV"); case "DOC" -> Set.of("MARKDOWN", "PDF"); case "DECK" -> Set.of("MARKDOWN", "PPTX"); default -> Set.of("MARKDOWN"); }).containsAll(renderers)) {
                throw new IllegalStateException("Skill 渲染器与形态不匹配");
            }
            Object rawAliases = header.getOrDefault("aliases", List.of());
            if (!(rawAliases instanceof List<?>)) throw new IllegalStateException("Skill 别名必须是数组");
            for (Object alias : (List<?>) rawAliases) {
                if (!(alias instanceof String text) || !text.matches("[a-z][a-z0-9_-]*")
                        || aliases.putIfAbsent(text, name) != null) throw new IllegalStateException("Skill 别名无效或重复");
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("skill_key", name);
            String description = (String) header.get("description");
            entry.put("display_name", description.split("：", 2)[0]);
            entry.put("description", description);
            entry.put("kind", kind);
            entry.put("version", String.valueOf(header.get("version")));
            entry.put("input_schema", Map.of("type", "object", "properties", properties, "required", List.of()));
            entry.put("graph_key", kind);
            entry.put("graph_definition", Map.of());
            entry.put("action_key", kind);
            List<String> roles = new ArrayList<>(List.of("PRIMARY_MARKDOWN"));
            if (renderers.contains("CSV")) roles.add("PRIMARY_CSV");
            if (renderers.contains("PDF")) roles.add("PRIMARY_PDF");
            if (renderers.contains("PPTX")) roles.add("PRIMARY_PPTX");
            entry.put("required_file_roles", List.copyOf(roles));
            entry.put("output_contract", Map.of("citation", Map.of("density", "PER_SECTION")));
            entries.add(entry);
            digest.update(("skills/" + name + "/SKILL.md\0").getBytes(StandardCharsets.UTF_8));
            digest.update(new String(raw, StandardCharsets.UTF_8).replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
        }
        if (names.stream().anyMatch(aliases::containsKey)) throw new IllegalStateException("Skill 别名覆盖正式名称");
        try (var stream = Files.list(schemas)) {
            for (Path path : stream.filter(p -> p.getFileName().toString().endsWith(".v1.json")).sorted().toList()) {
                digest.update(("schemas/" + path.getFileName() + "\0").getBytes(StandardCharsets.UTF_8));
                digest.update(Files.readString(path, StandardCharsets.UTF_8).replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("catalog_version", 2);
        result.put("skills", entries);
        result.put("aliases", aliases);
        result.put("digest", HexFormat.of().formatHex(digest.digest()));
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> objectField(Map<String, Object> header, String field) {
        Object value = header.getOrDefault(field, Map.of());
        if (!(value instanceof Map<?, ?>)) throw new IllegalStateException("Skill 字段必须是对象：" + field);
        return (Map<String, Object>) value;
    }

    private static void validateDirectoryContract(String kind, Map<String, Object> contract) {
        for (var entry : contract.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (!(key.equals("figure_required") ? Set.of("DOC", "DECK").contains(kind) : kind.equals(RULE_KINDS.get(key)))) throw new IllegalStateException("契约规则未注册或不适用于形态：" + key);
            if (Set.of("sections", "tree_depth", "table_columns", "deck_bullets").contains(key)) {
                Set<String> allowed = key.equals("tree_depth") ? Set.of("min", "max", "min_children", "max_children") : Set.of("min", "max");
                if (!(value instanceof Map<?, ?> bounds) || !allowed.containsAll(bounds.keySet())
                        || !(bounds.get("min") instanceof Integer min) || !(bounds.get("max") instanceof Integer max)
                        || min < 1 || min > max || bounds.values().stream().anyMatch(v -> !(v instanceof Integer n) || n < 1)
                        || (bounds.containsKey("min_children") && bounds.containsKey("max_children")
                            && (Integer) bounds.get("min_children") > (Integer) bounds.get("max_children"))) {
                    throw new IllegalStateException("契约上下限无效：" + key);
                }
            } else if ((key.equals("required_headings") || key.equals("figure_required") && !Boolean.TRUE.equals(value))) {
                if (!(value instanceof List<?> list) || list.isEmpty()
                        || list.stream().anyMatch(v -> !(v instanceof String text) || text.isBlank())
                        || list.size() != Set.copyOf(list).size()) throw new IllegalStateException("必需章节标题无效");
            } else if (!Boolean.TRUE.equals(value)) throw new IllegalStateException("契约开关必须为 true");
        }
    }

    public String catalogDigest() {
        return catalogDigest;
    }

    public String publishedVersion(String skillKey) {
        return publishedVersions.get(resolveSkill(skillKey).skillKey());
    }

    boolean requiresEvidence(String skillKey) {
        return evidenceRequiredByKey.get(resolveSkill(skillKey).skillKey());
    }

    public List<String> requiredFileRoles(String skillKey) {
        return requiredFileRoles.get(resolveSkill(skillKey).skillKey());
    }

    private static final Set<String> REQUIRED_ENTRY_FIELDS = Set.of("skill_key", "version", "display_name",
            "description", "input_schema", "output_schema_ref", "graph_key", "prompt_recipe_id",
            "required_file_roles", "capability_allowlist", "action_key", "output_contract", "graph_definition");
    /** presentation 是展示信息；definition 让新产物只在目录里声明动作和提示词配方，由 Worker 加载。 */
    private static final Set<String> OPTIONAL_ENTRY_FIELDS = Set.of("presentation", "definition", "generation_mode");

    static void validatePublishedEntry(Map<?, ?> entry, Map<?, ?> schema) {
        Set<String> fields = new java.util.HashSet<>();
        entry.keySet().forEach(field -> fields.add(String.valueOf(field)));
        boolean knownFields = fields.containsAll(REQUIRED_ENTRY_FIELDS) && fields.stream()
                .allMatch(field -> REQUIRED_ENTRY_FIELDS.contains(field) || OPTIONAL_ENTRY_FIELDS.contains(field));
        if (!knownFields
                || (entry.containsKey("presentation") && !validPresentation(entry.get("presentation")))
                || (entry.containsKey("definition") && !(entry.get("definition") instanceof Map<?, ?>))
                || !"1.0.0".equals(entry.get("version"))
                || !("artifact-" + entry.get("skill_key") + "-output.v1").equals(entry.get("output_schema_ref"))
                || !schema.keySet().stream().allMatch(Set.of("type", "properties", "required")::contains)
                || !"object".equals(schema.get("type"))
                || !(schema.get("properties") instanceof Map<?, ?> properties)
                || !(entry.get("capability_allowlist") instanceof List<?> capabilities)
                || !Set.of("READ_WORKSPACE_DOC", "GENERATE_STRUCTURED_TEXT", "VERIFY_OUTPUT",
                        "READ_WEB_PAGE", "EXTRACT_TRANSCRIPT", "TRANSCRIBE_AUDIO",
                        "CAPTURE_VIDEO_FRAMES", "ANALYZE_FRAME").containsAll(capabilities)) {
            throw new IllegalStateException("unsupported artifact Skill publication policy");
        }
        ArtifactOutputContractPublicationPolicy.validate(entry.get("output_contract"));
        ArtifactNodeGraphPublicationPolicy.validate(
                entry.get("graph_key"), entry.get("graph_definition"), entry.get("output_contract"), generationMode(entry));
        Object required = schema.containsKey("required") ? schema.get("required") : List.of();
        if (!(required instanceof List<?> requiredKeys) || !properties.keySet().containsAll(requiredKeys)) {
            throw new IllegalStateException("published Skill has invalid required inputs");
        }
        for (Object rawField : properties.values()) {
            if (!(rawField instanceof Map<?, ?> field)
                    || !field.keySet().stream().allMatch(Set.of("type", "default", "oneOf", "enum")::contains)
                    || !"string".equals(field.get("type"))) {
                throw new IllegalStateException("unsupported published Skill input field");
            }
        }
    }

    private static String generationMode(Map<?, ?> entry) {
        Object value = entry.containsKey("generation_mode") ? entry.get("generation_mode") : "SINGLE";
        if (!(value instanceof String mode) || !Set.of("SINGLE", "SECTIONED").contains(mode)) {
            throw new IllegalStateException("产物生成模式必须是 SINGLE 或 SECTIONED");
        }
        return mode;
    }

    private static boolean validPresentation(Object value) {
        if (!(value instanceof Map<?, ?> presentation)) return false;
        Object hints = presentation.get("default_input_hints");
        return hints == null || (hints instanceof List<?> list && list.stream().allMatch(String.class::isInstance));
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
            if ("bilibili_course_note_pdf".equals(skill.skillKey()) && normalized.containsKey("part")) {
                canonicalUrl = resolveVideoPart(canonicalUrl, String.valueOf(normalized.get("part")));
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

    private static String resolveVideoPart(String url, String requestedPart) {
        if (!requestedPart.matches("[1-9][0-9]{0,3}") || Integer.parseInt(requestedPart) > 1_000) {
            throw new BusinessException("ARTIFACT_SKILL_INPUT_PART_INVALID", "视频分集必须为 1–1000");
        }
        URI parsed;
        try { parsed = URI.create(url); }
        catch (IllegalArgumentException ex) {
            throw new BusinessException("ARTIFACT_SKILL_INPUT_PART_INVALID", "视频链接格式不正确");
        }
        if (!("http".equals(parsed.getScheme()) || "https".equals(parsed.getScheme()))
                || !("bilibili.com".equals(parsed.getHost())
                        || "www.bilibili.com".equals(parsed.getHost()))
                || parsed.getPath() == null
                || !parsed.getPath().matches("/video/BV[0-9A-Za-z]{10}/?")
                || parsed.getRawFragment() != null) {
            throw new BusinessException("ARTIFACT_SKILL_INPUT_PART_INVALID", "分集只适用于 B 站视频链接");
        }
        Matcher existing = Pattern.compile("(?:^|&)p=([^&]*)")
                .matcher(parsed.getRawQuery() == null ? "" : parsed.getRawQuery());
        if (existing.find()) {
            String value = existing.group(1);
            if (!value.matches("[1-9][0-9]{0,3}") || Integer.parseInt(value) > 1_000
                    || Integer.parseInt(value) != Integer.parseInt(requestedPart) || existing.find()) {
                throw new BusinessException("ARTIFACT_SKILL_INPUT_CONFLICT", "视频链接分集与 part 输入不一致");
            }
            return url;
        }
        if ("1".equals(requestedPart)) return url;
        return url + (parsed.getRawQuery() == null ? "?" : "&") + "p=" + requestedPart;
    }

    public List<ArtifactSkillSummaryResponse> listSkills() {
        return skillsByKey.values().stream()
                .map(skill -> new ArtifactSkillSummaryResponse(
                        skill.skillKey(),
                        skill.displayName(),
                        descriptions.getOrDefault(skill.skillKey(), "NoteWeave 内置产物 Skill"),
                        "ACTIVE",
                        publicInputSchema(skill.inputSchema()),
                        defaultInputHints(skill.skillKey()),
                        presentations.getOrDefault(skill.skillKey(), Map.of()),
                        kinds.getOrDefault(skill.skillKey(), "")
                ))
                .toList();
    }

    private String canonicalSkillKey(String value) {
        String normalizedSkillKey = pipelineEnabled ? (value == null ? "" : value.trim().toLowerCase(Locale.ROOT)) : normalizeSkillKey(value);
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

    private List<String> defaultInputHints(String skillKey) {
        Object hints = presentations.getOrDefault(skillKey, Map.of()).get("default_input_hints");
        if (hints instanceof List<?> values && !values.isEmpty()) {
            return values.stream().map(String::valueOf).toList();
        }
        return List.of("按当前 Skill 默认结构生成");
    }
}
