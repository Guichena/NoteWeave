import { useEffect, useMemo, useState } from "react";
import {
  buildArtifactStudioSkill,
  buildInitialArtifactFormValues,
  type ArtifactStudioSkill
} from "./artifactStudio";
import { artifactsApi, type ArtifactsApi } from "./api";

export function useArtifactStudioCatalog(
  presentation: Record<string, Partial<ArtifactStudioSkill>>,
  preferredSkillKeys: string[],
  onStatus: (message: string) => void,
  api: ArtifactsApi = artifactsApi
) {
  const [skills, setSkills] = useState<ArtifactStudioSkill[]>([]);
  const [selectedSkillKey, setSelectedSkillKey] = useState("");
  const [composerOpen, setComposerOpen] = useState(false);
  const [formValues, setFormValues] = useState<Record<string, string>>({});
  const [customInstruction, setCustomInstruction] = useState("");
  const preferredOrder = useMemo(
    () => new Map(preferredSkillKeys.map((key, index) => [key, index])),
    [preferredSkillKeys]
  );

  const selectedSkill = skills.find((skill) => skill.key === selectedSkillKey)
    ?? skills[0];

  useEffect(() => {
    let cancelled = false;
    void api.listSkills()
      .then((summaries) => {
        if (cancelled) {
          return;
        }
        const loadedSkills = summaries
          .map((skill) => buildArtifactStudioSkill(
            skill,
            presentation[skill.skill_key]
          ))
          .sort((left, right) => {
            const leftOrder = preferredOrder.get(left.key) ?? Number.MAX_SAFE_INTEGER;
            const rightOrder = preferredOrder.get(right.key) ?? Number.MAX_SAFE_INTEGER;
            return leftOrder === rightOrder
              ? left.title.localeCompare(right.title, "zh-CN")
              : leftOrder - rightOrder;
          });
        setSkills(loadedSkills);
        if (loadedSkills.length === 0) {
          setComposerOpen(false);
          onStatus("Skill 目录为空，当前不能创建 Artifact 任务。");
        }
        setSelectedSkillKey((current) => loadedSkills.some((skill) => skill.key === current)
          ? current
          : (loadedSkills[0]?.key ?? ""));
      })
      .catch(() => {
        if (cancelled) {
          return;
        }
        setSkills([]);
        setSelectedSkillKey("");
        setComposerOpen(false);
        onStatus("Skill 目录加载失败，请检查 Artifact Worker 后重试。");
      });
    return () => {
      cancelled = true;
    };
  }, [api, onStatus, preferredOrder, presentation]);

  useEffect(() => {
    setFormValues((current) => selectedSkill
      ? buildInitialArtifactFormValues(selectedSkill, current)
      : {});
  }, [selectedSkill]);

  return {
    skills,
    selectedSkill,
    selectedSkillKey,
    setSelectedSkillKey,
    composerOpen,
    setComposerOpen,
    formValues,
    setFormValues,
    customInstruction,
    setCustomInstruction
  };
}
