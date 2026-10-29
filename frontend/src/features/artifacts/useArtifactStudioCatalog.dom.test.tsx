// @vitest-environment jsdom

import { act, renderHook, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { useArtifactStudioCatalog } from "./useArtifactStudioCatalog";

const presentation = {
  first: { title: "First presented" },
  second: { title: "Second presented" }
};
const preferredSkillKeys = ["first", "second"];

describe("useArtifactStudioCatalog", () => {
  it("仅由接口声明的新 Skill 能出现在列表并生成选项表单", async () => {
    const api = { listSkills: vi.fn(async () => [{
      skill_key: "new-learning-product", display_name: "新的学习材料", description: "新 Skill 的描述",
      status: "ACTIVE", kind: "DOC", input_schema: { type: "object", properties: {
        length: { type: "string", enum: ["SHORT", "STANDARD", "LONG"], default: "STANDARD" }
      } }
    }]) };
    const { result } = renderHook(() => useArtifactStudioCatalog({}, [], vi.fn(), api as never));
    await waitFor(() => expect(result.current.skills).toHaveLength(1));
    expect(result.current.selectedSkill?.key).toBe("new-learning-product");
    expect(result.current.selectedSkill?.kind).toBe("DOC");
    expect(result.current.selectedSkill?.inputFields[0].label).toBe("长度");
    expect(result.current.selectedSkill?.inputFields[0].options?.map((option) => option.label)).toEqual(["简短", "标准", "详细"]);
    expect(result.current.formValues).toEqual({ length: "STANDARD" });
  });

  it("uses only skills returned by the server and derives form defaults", async () => {
    const onStatus = vi.fn();
    const api = {
      listSkills: vi.fn(async () => [{
        skill_key: "second",
        display_name: "Second",
        description: "Server skill",
        status: "ACTIVE",
        input_schema: {
          type: "object",
          properties: { language: { type: "string", default: "zh-CN" } }
        }
      }])
    };
    const { result } = renderHook(() => useArtifactStudioCatalog(
      presentation,
      preferredSkillKeys,
      onStatus,
      api as never
    ));

    await waitFor(() => expect(result.current.skills).toHaveLength(1));
    expect(result.current.loading).toBe(false);
    expect(result.current.skills[0].key).toBe("second");
    expect(result.current.selectedSkillKey).toBe("second");
    expect(result.current.formValues).toEqual({ language: "zh-CN" });
    expect(result.current.skills.some((skill) => skill.key === "first")).toBe(false);

    act(() => result.current.setComposerOpen(true));
    expect(result.current.composerOpen).toBe(true);
  });

  it("fails visibly and keeps the catalog empty", async () => {
    const onStatus = vi.fn();
    const api = {
      listSkills: vi.fn(async () => {
        throw new Error("offline");
      })
    };
    const { result } = renderHook(() => useArtifactStudioCatalog(
      presentation,
      preferredSkillKeys,
      onStatus,
      api as never
    ));

    await waitFor(() => expect(onStatus).toHaveBeenCalledWith(
      "Skill 目录加载失败，请检查 Artifact Worker 后重试。"
    ));
    expect(result.current.skills).toEqual([]);
    expect(result.current.loading).toBe(false);
    expect(result.current.selectedSkill).toBeUndefined();
    expect(result.current.composerOpen).toBe(false);
  });
});
