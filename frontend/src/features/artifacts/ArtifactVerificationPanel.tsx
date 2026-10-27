import type { ArtifactVerification } from "./model";

const SUPPORT_LABELS: Record<string, { label: string; tone: string }> = {
  SUPPORTED: { label: "有依据", tone: "ok" },
  PARTIAL: { label: "部分有依据", tone: "warn" },
  UNSUPPORTED: { label: "缺少依据", tone: "danger" },
  NOT_RUN: { label: "未校验", tone: "muted" }
};

function percent(value: number | null | undefined) {
  return typeof value === "number" && Number.isFinite(value) ? `${Math.round(value * 100)}%` : "-";
}

/** 产物内容校验（4D）：输出契约、修复轮次、引用召回率与精确率、逐章节的证据支撑。 */
export function ArtifactVerificationPanel({ verification }: { verification?: ArtifactVerification | null }) {
  if (!verification) return null;
  const sections = verification.sections ?? [];
  // 旧版本只有生成过程面板里的检查结果，这里不重复展示
  if (!verification.contract_status && sections.length === 0) return null;
  const rounds = verification.repair_rounds ?? [];
  const unsupported = sections.filter((section) => section.support === "UNSUPPORTED").length;
  const notRun = sections.filter((section) => section.support === "NOT_RUN").length;
  const passed = verification.contract_status === "PASS";
  // NR23：用户编辑过的正文不能沿用原生成版本的校验结论——明确标注失效并提示重新核验
  const editedUnverified = verification.contract_status === "USER_EDITED_UNVERIFIED";

  return (
    <section className="artifact-verification" aria-label="内容校验">
      <div className="artifact-verification-summary">
        {verification.contract_status ? (
          <span className={`artifact-badge tone-${passed ? "ok" : editedUnverified ? "muted" : "danger"}`}>
            {passed
              ? "输出契约通过"
              : editedUnverified
                ? "正文已编辑，原校验结论已失效"
                : "输出契约未通过"}
          </span>
        ) : null}
        {editedUnverified ? null : (
          <>
            <span>引用召回率 {percent(verification.citation_recall)}</span>
            <span>引用精确率 {percent(verification.citation_precision)}</span>
            <span>{rounds.length > 0 ? `修复 ${rounds.length} 轮` : "未经修复"}</span>
          </>
        )}
        {unsupported > 0 ? <span className="artifact-verification-alert">{unsupported} 个章节缺少依据</span> : null}
      </div>
      {verification.status === "FAIL" || verification.evidence_status === "FAIL" ? (
        <p className="artifact-verification-alert-note" role="note">
          自动修复后仍有内容没有通过校验，产物已照常生成。标为缺少依据的章节请对照引用的原文核对后再使用。
        </p>
      ) : null}
      {notRun > 0 ? (
        <p className="artifact-verification-note">{notRun} 个章节因模型不可用没有完成证据校验，阅读时请自行核对引用。</p>
      ) : null}
      {sections.length > 0 ? (
        <ul className="artifact-verification-sections" aria-label="章节证据支撑">
          {sections.map((section, index) => {
            const support = SUPPORT_LABELS[section.support] ?? { label: section.support, tone: "muted" };
            return (
              <li key={`${section.heading}-${index}`}>
                <span className={`artifact-badge tone-${support.tone}`}>{support.label}</span>
                <span>{section.heading}</span>
              </li>
            );
          })}
        </ul>
      ) : null}
      {rounds.length > 0 ? (
        <details className="artifact-verification-rounds">
          <summary>修复记录</summary>
          <ol>
            {rounds.map((round) => (
              <li key={round.round}>
                第 {round.round} 轮：未通过 {round.failed_checks.join("、") || "-"}
                {round.repaired_sections.length > 0 ? `；重写 ${round.repaired_sections.join("、")}` : ""}
              </li>
            ))}
          </ol>
        </details>
      ) : null}
    </section>
  );
}
