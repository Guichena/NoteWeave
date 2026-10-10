// @vitest-environment jsdom

import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ArtifactStudioGrid } from "./ArtifactStudioGrid";
import type { ArtifactStudioSkill } from "./artifactStudio";

function skill(key: string, title: string, required: string[] = []): ArtifactStudioSkill {
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
    inputSchema: { type: "object", properties: {}, required },
    defaultInputHints: [],
    inputFields: []
  };
}

const skills = [
  skill("mindmap_from_workspace", "思维导图"),
  skill("report_draft", "结构化报告"),
  skill("video_summary", "视频总结", ["url"]),
  skill("audio_minutes", "音频纪要"),
  skill("knowledge_blog", "视频知识博客", ["url", "video_material_bundle_id"])
];

describe("ArtifactStudioGrid", () => {
  afterEach(() => cleanup());

  it("groups artifact types by input source and hides video-derived outputs", () => {
    render(<ArtifactStudioGrid skills={skills} isBusy={false} onSelectSkill={vi.fn()} />);

    const sourceGroup = screen.getByRole("region", { name: "基于资料产物类型" });
    const mediaGroup = screen.getByRole("region", { name: "音视频产物类型" });
    expect(within(sourceGroup).getAllByRole("button").map((button) => button.textContent)).toEqual(["思维导图", "结构化报告"]);
    expect(within(mediaGroup).getAllByRole("button").map((button) => button.textContent)).toEqual(["视频总结", "音频纪要"]);
    // 视频知识博客需要先准备视频素材，只能从视频学习入口创建
    expect(screen.queryByRole("button", { name: "视频知识博客" })).toBeNull();
  });

  it("opens the composer for the chosen type and shows its summary on hover", () => {
    const onSelectSkill = vi.fn();
    render(<ArtifactStudioGrid skills={skills} isBusy={false} onSelectSkill={onSelectSkill} />);

    const report = screen.getByRole("button", { name: "结构化报告" });
    expect(report.getAttribute("title")).toBe("结构化报告说明");
    fireEvent.click(report);
    expect(onSelectSkill).toHaveBeenCalledWith(expect.objectContaining({ key: "report_draft" }));
  });
});
