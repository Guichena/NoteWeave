import { memo, useMemo, useState } from "react";
import {
  AudioLines,
  BookOpenText,
  ChevronRight,
  CircleHelp,
  FileOutput,
  FileText,
  GraduationCap,
  ListChecks,
  Network,
  ScrollText,
  Video,
  type LucideIcon
} from "lucide-react";
import { type ArtifactStudioSkill } from "./artifactStudio";

type ArtifactStudioGridProps = {
  skills: ArtifactStudioSkill[];
  isBusy: boolean;
  onSelectSkill: (skill: ArtifactStudioSkill) => void;
};

export const ArtifactStudioGrid = memo(function ArtifactStudioGrid({
  skills,
  isBusy,
  onSelectSkill
}: ArtifactStudioGridProps) {
  const [activeFilter, setActiveFilter] = useState<ArtifactStudioFilterKey>("recommended");
  const availableFilters = useMemo(
    () => ARTIFACT_STUDIO_FILTERS.filter((filter) => (
      filter.key === "recommended"
      || filter.key === "all"
      || skills.some((skill) => filter.matches(skill.key))
    )),
    [skills]
  );
  const selectedFilter = availableFilters.find((filter) => filter.key === activeFilter)
    ?? availableFilters[0]
    ?? ARTIFACT_STUDIO_FILTERS[0];
  const visibleSkills = selectedFilter.key === "recommended"
    ? resolveRecommendedSkills(skills)
    : selectedFilter.key === "all"
      ? skills
      : skills.filter((skill) => selectedFilter.matches(skill.key));

  return (
    <div className="artifact-skill-catalog">
      <div className="artifact-catalog-toolbar">
        <div className="artifact-filter-tabs" role="tablist" aria-label="按用途筛选产物">
          {availableFilters.map((filter) => (
            <button
              key={filter.key}
              type="button"
              role="tab"
              aria-selected={selectedFilter.key === filter.key}
              className={selectedFilter.key === filter.key ? "artifact-filter-tab is-active" : "artifact-filter-tab"}
              onClick={() => setActiveFilter(filter.key)}
            >
              {filter.label}
            </button>
          ))}
        </div>
        <span className="artifact-filter-count">{visibleSkills.length} 种可用</span>
      </div>
      <div
        className="artifact-action-list studio-grid is-featured is-filtered"
        aria-label={`${selectedFilter.label}产物类型`}
      >
        {visibleSkills.map((skill) => (
          <ArtifactSkillButton key={skill.key} skill={skill} isBusy={isBusy} onSelectSkill={onSelectSkill} />
        ))}
      </div>
    </div>
  );
});

type ArtifactStudioFilterKey = "recommended" | "all" | "learning" | "knowledge" | "visual" | "delivery";

type ArtifactStudioFilter = {
  key: ArtifactStudioFilterKey;
  label: string;
  matches: (skillKey: string) => boolean;
};

const ARTIFACT_STUDIO_FILTERS: ArtifactStudioFilter[] = [
  { key: "recommended", label: "推荐", matches: () => true },
  { key: "all", label: "全部", matches: () => true },
  { key: "learning", label: "学习", matches: (key) => ["study_guide", "quiz_pack", "course_notes"].includes(key) },
  { key: "knowledge", label: "知识", matches: (key) => ["wiki_page", "structured_note", "report_draft", "faq_draft"].includes(key) },
  { key: "visual", label: "可视化", matches: (key) => ["mindmap_from_workspace"].includes(key) },
  {
    key: "delivery",
    label: "交付",
    matches: (key) => ["resume_highlight", "bilibili_course_note_pdf", "video_summary", "audio_minutes"].includes(key)
  }
];

const RECOMMENDED_ARTIFACT_SKILL_KEYS = [
  "mindmap_from_workspace",
  "report_draft",
  "wiki_page",
  "study_guide"
];

export function resolveRecommendedSkills(skills: ArtifactStudioSkill[]): ArtifactStudioSkill[] {
  const recommended = RECOMMENDED_ARTIFACT_SKILL_KEYS
    .flatMap((key) => skills.find((skill) => skill.key === key) ?? [])
    .slice(0, 4);
  if (recommended.length >= 4) {
    return recommended;
  }
  const recommendedKeys = new Set(recommended.map((skill) => skill.key));
  return [
    ...recommended,
    ...skills.filter((skill) => !recommendedKeys.has(skill.key))
  ].slice(0, 4);
}

function ArtifactSkillButton({
  skill,
  isBusy,
  onSelectSkill
}: {
  skill: ArtifactStudioSkill;
  isBusy: boolean;
  onSelectSkill: (skill: ArtifactStudioSkill) => void;
}) {
  const SkillIcon = resolveArtifactSkillIcon(skill.key);
  return (
    <button
      type="button"
      className={`artifact-action-card tone-${skill.tone}`}
      disabled={isBusy}
      title={skill.summary}
      onClick={() => onSelectSkill(skill)}
    >
      <span className="artifact-action-icon" aria-hidden="true">
        <SkillIcon size={17} strokeWidth={1.9} />
      </span>
      <span className="artifact-action-copy">
        <strong>{skill.title}</strong>
        <span className="artifact-action-summary">{skill.summary}</span>
      </span>
      <ChevronRight className="artifact-action-arrow" size={15} aria-hidden="true" />
    </button>
  );
}

const ARTIFACT_SKILL_ICONS: Record<string, LucideIcon> = {
  resume_highlight: ListChecks,
  study_guide: GraduationCap,
  quiz_pack: CircleHelp,
  wiki_page: BookOpenText,
  mindmap_from_workspace: Network,
  bilibili_course_note_pdf: FileOutput,
  report_draft: ScrollText,
  faq_draft: CircleHelp,
  structured_note: FileText,
  course_notes: GraduationCap,
  video_summary: Video,
  audio_minutes: AudioLines,
  structured_report: ScrollText,
  course_note: GraduationCap,
  faq: CircleHelp
};

function resolveArtifactSkillIcon(skillKey: string): LucideIcon {
  const normalized = skillKey.toLowerCase();
  if (ARTIFACT_SKILL_ICONS[normalized]) {
    return ARTIFACT_SKILL_ICONS[normalized];
  }
  if (normalized.includes("audio")) return AudioLines;
  if (normalized.includes("video")) return Video;
  if (normalized.includes("quiz") || normalized.includes("faq")) return CircleHelp;
  if (normalized.includes("course") || normalized.includes("study")) return GraduationCap;
  if (normalized.includes("report")) return ScrollText;
  return FileText;
}
