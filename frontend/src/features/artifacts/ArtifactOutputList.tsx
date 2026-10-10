import { ChevronRight, LoaderCircle } from "lucide-react";
import { resolveArtifactSkillIcon } from "./ArtifactStudioGrid";
import { ARTIFACT_PHASES, isArtifactOutputActive, type ArtifactOutputItem } from "./artifactOutputs";

type ArtifactOutputListProps = {
  items: ArtifactOutputItem[];
  loading: boolean;
  formatRelativeTime: (value: string) => string;
  onOpen: (item: ArtifactOutputItem) => void;
};

/** 已生成和正在生成的产物。运行中的任务显示所处阶段，完成后可打开阅读。 */
export function ArtifactOutputList({ items, loading, formatRelativeTime, onOpen }: ArtifactOutputListProps) {
  return (
    <section className="artifact-output-section" aria-labelledby="artifact-output-heading">
      <div className="artifact-section-heading">
        <h3 className="section-label" id="artifact-output-heading">我的产物</h3>
        {items.length > 0 ? <span>{items.length}</span> : null}
      </div>
      {items.length === 0 ? (
        loading ? (
          <div className="artifact-pending-state" role="status">
            <LoaderCircle className="is-spinning" size={16} aria-hidden="true" />
            <span>正在加载产物</span>
          </div>
        ) : (
          <p className="artifact-quiet-state">选择上面的类型开始生成，完成的产物和各个版本会保存在这里。</p>
        )
      ) : (
        <ul className="artifact-output-list">
          {items.map((item) => (
            <li key={item.key}>
              <ArtifactOutputRow item={item} formatRelativeTime={formatRelativeTime} onOpen={onOpen} />
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

function ArtifactOutputRow({
  item,
  formatRelativeTime,
  onOpen
}: {
  item: ArtifactOutputItem;
  formatRelativeTime: (value: string) => string;
  onOpen: (item: ArtifactOutputItem) => void;
}) {
  const Icon = resolveArtifactSkillIcon(item.skillKey);
  const active = isArtifactOutputActive(item);
  const openable = item.versionNo > 0;
  const content = (
    <>
      <span className="artifact-output-icon" aria-hidden="true"><Icon size={16} /></span>
      <span className="artifact-output-copy">
        <strong>{item.title}</strong>
        <small>
          {active ? <span className="artifact-phase-label">{describePhase(item)}</span> : null}
          {active ? " · " : null}
          {!active && item.title !== item.typeLabel ? `${item.typeLabel} · ` : ""}
          {!active && openable ? `v${item.versionNo} · ` : ""}
          {formatRelativeTime(item.updatedAt)}
        </small>
        {active ? <ArtifactPhaseTrack item={item} /> : null}
        {item.state === "failed" || item.state === "cancelled" ? (
          <span className="artifact-output-issue">
            {item.statusLabel}{item.detail ? `：${item.detail}` : ""}
          </span>
        ) : null}
      </span>
      {openable ? <ChevronRight className="artifact-output-arrow" size={15} aria-hidden="true" /> : null}
    </>
  );

  return openable ? (
    <button
      type="button"
      className={`artifact-output-row is-${item.state}`}
      data-run-key={item.key}
      data-state={item.state}
      onClick={() => onOpen(item)}
      aria-label={`打开 ${item.title}`}
      title={item.technicalDetail || undefined}
    >
      {content}
    </button>
  ) : (
    <div className={`artifact-output-row is-${item.state}`} data-run-key={item.key} data-state={item.state} title={item.technicalDetail || undefined}>
      {content}
    </div>
  );
}

function describePhase(item: ArtifactOutputItem) {
  if (item.state === "waiting" || item.versionNo === 0) return item.statusLabel;
  return `生成新版本 · ${item.statusLabel}`;
}

function ArtifactPhaseTrack({ item }: { item: ArtifactOutputItem }) {
  return (
    <span className="artifact-phase-track" role="progressbar" aria-label={describePhase(item)}
      aria-valuemin={0} aria-valuemax={ARTIFACT_PHASES.length} aria-valuenow={Math.max(item.phaseIndex, 0)}>
      {ARTIFACT_PHASES.map((phase, index) => (
        <span
          key={phase.key}
          className={index < item.phaseIndex ? "is-done" : index === item.phaseIndex ? "is-current" : ""}
        />
      ))}
    </span>
  );
}
