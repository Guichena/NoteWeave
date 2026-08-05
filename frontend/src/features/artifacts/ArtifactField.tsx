import type { ArtifactStudioField } from "./artifactStudio";

export function ArtifactField({
  field,
  value,
  onChange
}: {
  field: ArtifactStudioField;
  value: string;
  onChange: (fieldKey: string, value: string) => void;
}) {
  if (field.kind === "select") {
    return (
      <label key={field.key} className="rail-field">
        <span>{field.label}</span>
        <select value={value} onChange={(event) => onChange(field.key, event.target.value)}>
          {(field.options || []).map((option) => (
            <option key={option.value} value={option.value}>{option.label}</option>
          ))}
        </select>
      </label>
    );
  }

  return (
    <label key={field.key} className="rail-field">
      <span>{field.label}</span>
      <input
        value={value}
        onChange={(event) => onChange(field.key, event.target.value)}
        placeholder={field.placeholder}
        type={field.kind === "url" ? "url" : "text"}
      />
    </label>
  );
}
