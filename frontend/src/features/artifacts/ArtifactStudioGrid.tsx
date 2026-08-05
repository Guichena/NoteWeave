import { memo } from "react";
import {
  buildInitialArtifactFormValues,
  type ArtifactStudioSkill
} from "./artifactStudio";

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
  return (
    <div className="artifact-action-list studio-grid">
      {skills.map((skill) => (
        <button
          key={skill.key}
          className={`artifact-action-card tone-${skill.tone}`}
          disabled={isBusy}
          onClick={() => onSelectSkill(skill)}
        >
          <span className="artifact-action-heading">
            <strong>{skill.title}</strong>
            <small>{skill.artifactType}</small>
          </span>
          <span className="artifact-action-summary">{skill.summary}</span>
          <span className="artifact-action-meta">
            {skill.sourceHint} · {skill.runtimeHint}
          </span>
          {skill.badges?.length ? (
            <span className="artifact-badge-row">
              {skill.badges.map((badge) => (
                <small key={badge} className="artifact-badge">{badge}</small>
              ))}
            </span>
          ) : null}
        </button>
      ))}
    </div>
  );
});

export function selectArtifactSkillDefaults(skill: ArtifactStudioSkill) {
  return buildInitialArtifactFormValues(skill);
}
