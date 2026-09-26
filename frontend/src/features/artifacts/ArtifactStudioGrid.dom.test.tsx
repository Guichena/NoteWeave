// @vitest-environment jsdom

import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { ArtifactStudioGrid, resolveRecommendedSkills } from "./ArtifactStudioGrid";
import type { ArtifactStudioSkill } from "./artifactStudio";

function skill(key: string, title: string): ArtifactStudioSkill {
  return {
    key,
    title,
    summary: `${title}说明`,
    artifactType: title,
    sourceHint: "当前工作台资料",
    runtimeHint: "异步生成",
    badges: [],
    styleHint: "",
    promptFocus: "",
    tone: "blue",
    supportsUrl: false,
    defaultInputHints: [],
    inputFields: []
  };
}

const skills = [
  skill("resume_highlight", "简历亮点"),
  skill("study_guide", "学习指南"),
  skill("wiki_page", "Wiki 页面"),
  skill("mindmap_from_workspace", "思维导图"),
  skill("report_draft", "结构化报告"),
  skill("video_summary", "视频总结")
];

describe("ArtifactStudioGrid", () => {
  it("builds a cross-format recommended set instead of taking the first four entries", () => {
    expect(resolveRecommendedSkills(skills).map((item) => item.key)).toEqual([
      "mindmap_from_workspace",
      "report_draft",
      "wiki_page",
      "study_guide"
    ]);
  });

  it("filters the unified Studio by user purpose", () => {
    const onSelectSkill = vi.fn();
    render(<ArtifactStudioGrid skills={skills} isBusy={false} onSelectSkill={onSelectSkill} />);

    expect(screen.getByRole("button", { name: /思维导图/ })).toBeTruthy();
    expect(screen.queryByRole("button", { name: /简历亮点/ })).toBeNull();

    fireEvent.click(screen.getByRole("tab", { name: "交付" }));

    expect(screen.getByRole("button", { name: /简历亮点/ })).toBeTruthy();
    expect(screen.getByRole("button", { name: /视频总结/ })).toBeTruthy();
    expect(screen.queryByRole("button", { name: /思维导图/ })).toBeNull();
    expect(screen.getByText("2 种可用")).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: /视频总结/ }));
    expect(onSelectSkill).toHaveBeenCalledWith(expect.objectContaining({ key: "video_summary" }));
  });
});
