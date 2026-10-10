import { describe, expect, it } from "vitest";
import { buildArtifactStudioSkill } from "./artifactStudio";
import { groupArtifactSkills } from "./artifactOutputs";

describe("skill catalog presentation", () => {
  it("builds a studio card for a skill declared only in the backend catalog", () => {
    const skill = buildArtifactStudioSkill({
      skill_key: "reading_digest",
      display_name: "精读摘要",
      description: "逐篇提炼资料的核心论点",
      status: "ACTIVE",
      default_input_hints: ["论点先行"],
      presentation: {
        summary: "逐篇提炼核心论点、证据、方法与局限。",
        group: "sources",
        order: 6,
        badges: ["精读", "可追溯"],
        runtime_hint: "快速生成",
        tone: "blue"
      }
    });

    // 前端没有这个产物的任何本地配置，展示信息全部来自目录
    expect(skill).toMatchObject({
      key: "reading_digest",
      title: "精读摘要",
      summary: "逐篇提炼核心论点、证据、方法与局限。",
      badges: ["精读", "可追溯"],
      runtimeHint: "快速生成",
      defaultInputHints: ["论点先行"],
      group: "sources",
      order: 6
    });
  });

  it("groups skills by the catalog group and hides video-material skills", () => {
    const card = (key: string, group?: string) => buildArtifactStudioSkill({
      skill_key: key, display_name: key, description: "", status: "ACTIVE", presentation: group ? { group } : undefined
    });
    const groups = groupArtifactSkills([
      card("reading_digest", "sources"),
      card("podcast_minutes", "media"),
      card("knowledge_blog", "video_material"),
      card("audio_minutes")
    ]);

    expect(groups.map((group) => [group.key, group.skills.map((skill) => skill.key)])).toEqual([
      ["sources", ["reading_digest"]],
      ["media", ["podcast_minutes", "audio_minutes"]]
    ]);
  });
});
