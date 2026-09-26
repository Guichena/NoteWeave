export type ArtifactStudioTone = "blue" | "gold" | "green" | "rose" | "violet";

export type ArtifactStudioFieldKind = "text" | "url" | "select";

export type ArtifactStudioFieldOption = {
  value: string;
  label: string;
};

export type ArtifactStudioField = {
  key: string;
  label: string;
  kind: ArtifactStudioFieldKind;
  required: boolean;
  placeholder: string;
  options?: ArtifactStudioFieldOption[];
};

export type ArtifactStudioSkill = {
  key: string;
  title: string;
  summary: string;
  artifactType: string;
  sourceHint: string;
  runtimeHint: string;
  badges: string[];
  styleHint: string;
  promptFocus: string;
  tone: ArtifactStudioTone;
  supportsUrl: boolean;
  inputSchema?: Record<string, unknown>;
  defaultInputHints: string[];
  inputFields: ArtifactStudioField[];
};

export type ArtifactSkillSummary = {
  skill_key: string;
  display_name: string;
  description: string;
  status: string;
  input_schema?: Record<string, unknown>;
  default_input_hints?: string[];
};

type ArtifactStudioSkillPresentation = Omit<
  ArtifactStudioSkill,
  "key" | "supportsUrl" | "inputSchema" | "defaultInputHints" | "inputFields"
>;

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}

function readSchemaProperties(inputSchema?: Record<string, unknown>): Record<string, Record<string, unknown>> {
  if (!isRecord(inputSchema)) {
    return {};
  }
  const properties = inputSchema.properties;
  if (!isRecord(properties)) {
    return {};
  }
  return Object.fromEntries(
    Object.entries(properties).filter(([, value]) => isRecord(value))
  ) as Record<string, Record<string, unknown>>;
}

function readRequiredKeys(inputSchema?: Record<string, unknown>): Set<string> {
  if (!isRecord(inputSchema) || !Array.isArray(inputSchema.required)) {
    return new Set<string>();
  }
  return new Set(inputSchema.required.filter((value): value is string => typeof value === "string"));
}

function readSchemaDefault(propertySchema?: Record<string, unknown>): string {
  if (!isRecord(propertySchema)) {
    return "";
  }
  return typeof propertySchema.default === "string" ? propertySchema.default.trim() : "";
}

function readSchemaOptions(propertySchema?: Record<string, unknown>): ArtifactStudioFieldOption[] | undefined {
  if (!isRecord(propertySchema)) {
    return undefined;
  }
  if (Array.isArray(propertySchema.oneOf)) {
    const options = propertySchema.oneOf
      .flatMap((option) => {
        if (!isRecord(option)) {
          return [];
        }
        const value = typeof option.const === "string" ? option.const.trim() : "";
        if (!value) {
          return [];
        }
        const label = typeof option.title === "string" && option.title.trim().length > 0
          ? option.title.trim()
          : value;
        return [{ value, label }];
      });
    return options.length > 0 ? options : undefined;
  }
  if (Array.isArray(propertySchema.enum)) {
    const options = propertySchema.enum
      .flatMap((value) => {
        if (typeof value !== "string") {
          return [];
        }
        const normalized = value.trim();
        return normalized ? [{ value: normalized, label: normalized }] : [];
      });
    return options.length > 0 ? options : undefined;
  }
  return undefined;
}

function isUrlFieldKey(key: string): boolean {
  const normalized = key.toLowerCase();
  return normalized === "url" || normalized.endsWith("_url");
}

function buildFieldLabel(skillKey: string, fieldKey: string): string {
  if (fieldKey === "language") {
    return "选择语言";
  }
  if (fieldKey === "layout") {
    return "导图布局";
  }
  if (fieldKey === "depth") {
    return "内容层级";
  }
  if (isUrlFieldKey(fieldKey)) {
    return skillKey.includes("bilibili") ? "B站链接" : "链接";
  }
  return fieldKey
    .split("_")
    .filter(Boolean)
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join(" ");
}

function buildFieldPlaceholder(skillKey: string, fieldKey: string): string {
  if (fieldKey === "language" || fieldKey === "layout" || fieldKey === "depth") {
    return "";
  }
  if (fieldKey === "url") {
    return skillKey.includes("bilibili")
      ? "https://www.bilibili.com/video/BV..."
      : "https://example.com/resource";
  }
  if (fieldKey === "video_url") {
    return "https://example.com/video";
  }
  if (fieldKey === "bilibili_url") {
    return "https://www.bilibili.com/video/BV...";
  }
  return "请输入";
}

function buildFieldKind(fieldKey: string): ArtifactStudioFieldKind {
  if (isUrlFieldKey(fieldKey)) {
    return "url";
  }
  return "text";
}

function buildInputFields(skillKey: string, inputSchema: Record<string, unknown> | undefined): ArtifactStudioField[] {
  const properties = readSchemaProperties(inputSchema);
  const requiredKeys = readRequiredKeys(inputSchema);
  const preferredFieldOrder = ["language", "purpose", "depth", "layout", "url", "video_url", "bilibili_url"];
  const fieldOrder = new Map(preferredFieldOrder.map((key, index) => [key, index]));
  const fieldKeys = Object.keys(properties).sort((left, right) => {
    const leftOrder = fieldOrder.get(left) ?? Number.MAX_SAFE_INTEGER;
    const rightOrder = fieldOrder.get(right) ?? Number.MAX_SAFE_INTEGER;
    return leftOrder === rightOrder ? left.localeCompare(right) : leftOrder - rightOrder;
  });

  return fieldKeys.map((fieldKey) => {
    const propertySchema = properties[fieldKey];
    const options = readSchemaOptions(propertySchema);
    return {
      key: fieldKey,
      label: buildFieldLabel(skillKey, fieldKey),
      kind: options && options.length > 0 ? "select" : buildFieldKind(fieldKey),
      required: requiredKeys.has(fieldKey),
      placeholder: buildFieldPlaceholder(skillKey, fieldKey),
      options
    };
  });
}

export function buildArtifactStudioSkill(
  skill: ArtifactSkillSummary,
  fallback: Partial<ArtifactStudioSkillPresentation> = {}
): ArtifactStudioSkill {
  const inputFields = buildInputFields(skill.skill_key, skill.input_schema);
  const supportsUrl = inputFields.some((field) => isUrlFieldKey(field.key));
  return {
    key: skill.skill_key,
    title: fallback.title || skill.display_name,
    summary: fallback.summary || skill.description || "生成工作台产物。",
    artifactType: fallback.artifactType || skill.display_name,
    sourceHint: fallback.sourceHint || "当前工作台资料",
    runtimeHint: fallback.runtimeHint || "异步生成",
    badges: fallback.badges || ["内置 Skill"],
    styleHint: fallback.styleHint || "按当前 skill 的默认风格与结构生成。",
    promptFocus: fallback.promptFocus || `输出适合 ${skill.display_name} 的产物`,
    tone: fallback.tone || "blue",
    supportsUrl,
    inputSchema: skill.input_schema,
    defaultInputHints: skill.default_input_hints || [],
    inputFields
  };
}

export function buildInitialArtifactFormValues(
  skill: ArtifactStudioSkill,
  currentValues: Record<string, string> = {}
): Record<string, string> {
  return {
    ...currentValues,
    ...Object.fromEntries(skill.inputFields.map((field) => {
      const currentValue = currentValues[field.key];
      if (typeof currentValue === "string" && currentValue.length > 0) {
        return [field.key, currentValue];
      }
      const propertySchema = readSchemaProperties(skill.inputSchema)[field.key];
      const schemaDefault = readSchemaDefault(propertySchema);
      if (schemaDefault) {
        return [field.key, schemaDefault];
      }
      if (field.kind === "select" && field.options && field.options.length > 0) {
        return [field.key, field.options[0].value];
      }
      return [field.key, ""];
    }))
  };
}

export function buildArtifactJobInputs(skill: ArtifactStudioSkill, formValues: Record<string, string>): Record<string, string> {
  return Object.fromEntries(
    skill.inputFields
      .map((field) => [field.key, (formValues[field.key] || "").trim()] as const)
      .filter(([, value]) => value.length > 0)
  );
}

export function isArtifactFormReady(skill: ArtifactStudioSkill, formValues: Record<string, string>): boolean {
  return skill.inputFields.every((field) => {
    if (!field.required) {
      return true;
    }
    return (formValues[field.key] || "").trim().length > 0;
  });
}

export function readArtifactLanguage(formValues: Record<string, string>): string {
  return formValues.language?.trim() || "zh-CN";
}

export function readArtifactUrl(skill: ArtifactStudioSkill, formValues: Record<string, string>): string {
  const urlField = skill.inputFields.find((field) => isUrlFieldKey(field.key));
  if (!urlField) {
    return "";
  }
  return (formValues[urlField.key] || "").trim();
}
