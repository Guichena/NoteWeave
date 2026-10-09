import { memo, type ReactNode } from "react";
import {
  AudioLines,
  BookOpenText,
  CircleHelp,
  FileOutput,
  FileText,
  GraduationCap,
  ListChecks,
  MessagesSquare,
  Network,
  ScrollText,
  Video,
  type LucideIcon
} from "lucide-react";
import { type ArtifactStudioSkill } from "./artifactStudio";
import { groupArtifactSkills } from "./artifactOutputs";

type ArtifactStudioGridProps = {
  skills: ArtifactStudioSkill[];
  isBusy: boolean;
  onSelectSkill: (skill: ArtifactStudioSkill) => void;
  /** 追加在音视频分组末尾的入口，例如视频学习。 */
  mediaExtra?: ReactNode;
};

/** 产物类型目录：按输入来源分组，每种类型对应一份输入 Schema 和输出契约。 */
export const ArtifactStudioGrid = memo(function ArtifactStudioGrid({
  skills,
  isBusy,
  onSelectSkill,
  mediaExtra
}: ArtifactStudioGridProps) {
  const groups = groupArtifactSkills(skills);
  const hasMediaGroup = groups.some((group) => group.key === "media");

  return (
    <div className="artifact-skill-catalog">
      {groups.map((group) => (
        <section key={group.key} className="artifact-skill-group" aria-label={`${group.label}产物类型`}>
          <h3 className="artifact-group-label">{group.label}</h3>
          <div className="artifact-action-list">
            {group.skills.map((skill) => (
              <ArtifactSkillButton key={skill.key} skill={skill} isBusy={isBusy} onSelectSkill={onSelectSkill} />
            ))}
            {group.key === "media" ? mediaExtra : null}
          </div>
        </section>
      ))}
      {!hasMediaGroup && mediaExtra ? (
        <section className="artifact-skill-group" aria-label="音视频产物类型">
          <h3 className="artifact-group-label">音视频</h3>
          <div className="artifact-action-list">{mediaExtra}</div>
        </section>
      ) : null}
    </div>
  );
});

function ArtifactSkillButton({
  skill,
  isBusy,
  onSelectSkill
}: {
  skill: ArtifactStudioSkill;
  isBusy: boolean;
  onSelectSkill: (skill: ArtifactStudioSkill) => void;
}) {
  return (
    <ArtifactTile
      title={skill.title}
      summary={skill.summary}
      tone={skill.tone}
      icon={resolveArtifactSkillIcon(skill.key)}
      disabled={isBusy}
      onClick={() => onSelectSkill(skill)}
    />
  );
}

export function ArtifactTile({
  title,
  summary,
  tone,
  icon: Icon,
  disabled,
  onClick
}: {
  title: string;
  summary: string;
  tone: string;
  icon: LucideIcon;
  disabled?: boolean;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      className={`artifact-action-card tone-${tone}`}
      disabled={disabled}
      title={summary}
      onClick={onClick}
    >
      <span className="artifact-action-icon" aria-hidden="true">
        <Icon size={16} strokeWidth={1.9} />
      </span>
      <span className="artifact-action-copy">
        <strong>{title}</strong>
      </span>
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
  faq_draft: MessagesSquare,
  structured_note: FileText,
  course_notes: GraduationCap,
  video_summary: Video,
  audio_minutes: AudioLines,
  video_learning_deck: FileOutput,
  knowledge_blog: FileText,
  interview_qa: MessagesSquare
};

export function resolveArtifactSkillIcon(skillKey: string): LucideIcon {
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
