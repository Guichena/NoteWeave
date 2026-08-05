import { describe, expect, it } from "vitest";
import { buildArtifactVersionAuditView, type ArtifactVersionAuditViewVersion } from "./artifactVersionAudit";

describe("artifactVersionAudit", () => {
  it("should normalize version audit preview and runtime trace projections", () => {
    const version: ArtifactVersionAuditViewVersion = {
      skill_key: "resume_highlight",
      version_no: 2,
      title: "简历亮点",
      content_markdown: "  generated output   with verifier trace  ",
      created_at: "2026-07-08T10:00:00Z",
      runtime_trace: {
        verification: {
          status: "PASS_WITH_REPAIR",
          repaired_checks: ["fixed heading"]
        }
      }
    };

    const view = buildArtifactVersionAuditView({
      version,
      scopeLabel: "最新版本",
      detailToggleLabel: "查看最新版本审计详情"
    });

    expect(view.version).toBe(version);
    expect(view.scopeLabel).toBe("最新版本");
    expect(view.detailToggleLabel).toBe("查看最新版本审计详情");
    expect(view.preview).toBe("generated output with verifier trace");
    expect(view.runtimeSummary).toEqual([
      {
        label: "Verifier",
        value: "PASS_WITH_REPAIR"
      }
    ]);
    expect(view.detailSections[0]?.title).toBe("Verifier");
  });

  it("should stay stable when no artifact version is available", () => {
    expect(buildArtifactVersionAuditView({
      version: null,
      scopeLabel: "历史版本",
      detailToggleLabel: "查看历史版本审计详情"
    })).toEqual({
      version: null,
      scopeLabel: "历史版本",
      detailToggleLabel: "查看历史版本审计详情",
      preview: "",
      runtimeSummary: [],
      detailSections: []
    });
  });
});
