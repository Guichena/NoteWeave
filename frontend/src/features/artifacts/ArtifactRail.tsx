import { type ArtifactRailProps } from "./ArtifactRailProps";
import { ArtifactStudioGrid, selectArtifactSkillDefaults } from "./ArtifactStudioGrid";

export type { ArtifactRailProps };

export function ArtifactRail(props: ArtifactRailProps) {
  const {
    artifactComposerOpen,
    isBusy,
    setArtifactComposerOpen,
    selectedArtifactSkill,
    renderArtifactField,
    artifactCustomInstruction,
    setArtifactCustomInstruction,
    appendArtifactHint,
    workspace,
    prepareArtifactPrompt,
    isArtifactFormReady,
    artifactFormValues,
    launchArtifactPrompt,
    artifactStudioSkills,
    setSelectedArtifactSkillKey,
    setArtifactFormValues,
    artifactSidebarState,
    latestArtifactVersion,
    formatRelativeTime,
    saveArtifactVersionAsSource,
    artifactSavedSourceByVersionId,
    artifactVersionSaveKey,
    writeArtifactVersionToKnowledge,
    artifactWritebackByVersionId,
    regenerateArtifactVersion,
    compareArtifactWithPreviousVersion,
    rollbackArtifactVersion,
    downloadArtifactVersionPdf,
    resolveArtifactSkillTitle,
    summarizeRunStatus,
    openArtifactHistoryVersion,
    artifactHistoryLoadingKey,
    openWikiHome,
    openResearchWorkbench,
    openMemoryWorkbench,
    toggleWikiEnabled,
    wikiEnabled,
    sourceDraftTitle,
    setSourceDraftTitle,
    sourceDraftContent,
    setSourceDraftContent,
    sourceDraftRewriteMode,
    rewriteNoteSourceDraft,
    saveNoteAnswerAsSource,
    lastNoteAssistantMessageId,
    wikiRebuildAdvice
  } = props;
  return (
        <aside className="artifact-rail">
          {artifactComposerOpen && selectedArtifactSkill ? (
            <div className="artifact-composer-view">
              <div className="artifact-composer-header">
                <button className="secondary-button" disabled={isBusy} onClick={() => setArtifactComposerOpen(false)}>
                  返回
                </button>
                <div>
                  <p className="section-label">创建产物</p>
                  <h2>{selectedArtifactSkill.title}</h2>
                </div>
              </div>

              {selectedArtifactSkill.inputFields.map((field) => renderArtifactField(field))}

              <label className="rail-field">
                <span>请描述您要创建什么样的{selectedArtifactSkill.title}</span>
                <textarea
                  value={artifactCustomInstruction}
                  onChange={(event) => setArtifactCustomInstruction(event.target.value)}
                  rows={6}
                  placeholder={`例如：${selectedArtifactSkill.summary}；重点关注哪些信息、希望采用什么语气、需要什么结构。`}
                />
              </label>

              {selectedArtifactSkill.defaultInputHints.length > 0 ? (
                <div className="artifact-hint-strip">
                  {selectedArtifactSkill.defaultInputHints.map((hint) => (
                    <button
                      key={hint}
                      type="button"
                      className="artifact-hint-chip"
                      disabled={isBusy}
                      onClick={() => appendArtifactHint(hint)}
                    >
                      {hint}
                    </button>
                  ))}
                </div>
              ) : null}

              <div className="artifact-panel-actions">
                <button className="secondary-button" disabled={isBusy || !workspace} onClick={() => prepareArtifactPrompt(selectedArtifactSkill)}>
                  填入聊天输入框
                </button>
                <button
                  disabled={isBusy || !workspace || !isArtifactFormReady(selectedArtifactSkill, artifactFormValues)}
                  onClick={() => void launchArtifactPrompt(selectedArtifactSkill)}
                >
                  生成
                </button>
              </div>
            </div>
          ) : (
            <>
              <div className="artifact-studio-header">
                <h2>Studio</h2>
              </div>

              <ArtifactStudioGrid
                skills={artifactStudioSkills}
                isBusy={isBusy}
                onSelectSkill={(skill) => {
                  setSelectedArtifactSkillKey(skill.key);
                  setArtifactFormValues(selectArtifactSkillDefaults(skill));
                  setArtifactComposerOpen(true);
                }}
              />
              {artifactStudioSkills.length === 0 ? (
                <p className="phase-note">Skill 目录当前不可用，无法创建 Artifact 任务。</p>
              ) : null}

              <div className="artifact-run-section">
                <p className="section-label">正在生成</p>
                {artifactSidebarState.runs.length > 0 ? artifactSidebarState.runs.slice(0, 1).map((run) => (
                  <div
                    key={run.key}
                    className={`artifact-run-card tone-${run.tone}`}
                    data-run-key={run.key}
                  >
                    <div className="artifact-run-row">
                      <span className="artifact-run-icon" aria-hidden="true">R</span>
                      <div>
                        <strong>{run.title}</strong>
                        <span>{run.detail}</span>
                        <small>{run.status} · {run.meta}</small>
                        {run.waitSignals.length > 0 ? (
                          <div className="signal-chip-row artifact-wait-signal-row">
                            {run.waitSignals.map((chip) => (
                              <span key={`${run.key}-${chip.label}-${chip.value}`} className={`signal-chip tone-${chip.tone}`}>
                                {chip.label}: {chip.value}
                              </span>
                            ))}
                          </div>
                        ) : null}
                        {run.waitDetails.length > 0 ? (
                          <div className="artifact-runtime-trace">
                            {run.waitDetails.map((line) => (
                              <small key={`${run.key}-${line.label}-${line.value}`} className="artifact-runtime-trace-line">
                                <strong>{line.label}</strong> · {line.value}
                              </small>
                            ))}
                          </div>
                        ) : null}
                      </div>
                    </div>
                  </div>
                )) : (
                  <p className="phase-note">当前没有运行中的产物任务。</p>
                )}
              </div>

              <div className="artifact-history-section">
                <p className="section-label">最新结果</p>
                {latestArtifactVersion ? (
                  <div className="artifact-run-card tone-stable">
                    <div className="artifact-run-row">
                      <span className="artifact-run-icon" aria-hidden="true">V</span>
                      <div>
                        <strong>{latestArtifactVersion.title}</strong>
                        <span>{artifactSidebarState.latestAuditView.preview || "已生成产物版本，可继续查看或用于后续沉淀。"}</span>
                        <small>
                          Skill {latestArtifactVersion.skill_key} · v{latestArtifactVersion.version_no} · {formatRelativeTime(latestArtifactVersion.created_at)}
                        </small>
                        {latestArtifactVersion.files?.length > 0 ? (
                          <small>
                            文件状态 {latestArtifactVersion.files.map((file) => `${file.file_format} · ${file.status} · ${file.storage_backend} · ${file.size_bytes}B`).join(" / ")}
                          </small>
                        ) : null}
                        <button
                          className="secondary-button"
                          onClick={() => void saveArtifactVersionAsSource(latestArtifactVersion)}
                          disabled={isBusy}
                        >
                          {artifactSavedSourceByVersionId[artifactVersionSaveKey(latestArtifactVersion)] ? "已保存为资料" : "保存为资料"}
                        </button>
                        <button
                          className="secondary-button"
                          onClick={() => void writeArtifactVersionToKnowledge(latestArtifactVersion, "NOTE")}
                          disabled={isBusy}
                        >
                          {artifactWritebackByVersionId[artifactVersionSaveKey(latestArtifactVersion)]?.includes("NOTE") ? "已写入 Note" : "写入 Note"}
                        </button>
                        <button
                          className="secondary-button"
                          onClick={() => void writeArtifactVersionToKnowledge(latestArtifactVersion, "WIKI")}
                          disabled={isBusy}
                        >
                          {artifactWritebackByVersionId[artifactVersionSaveKey(latestArtifactVersion)]?.includes("WIKI") ? "已写入 Wiki" : "写入 Wiki"}
                        </button>
                        <button className="secondary-button" onClick={() => void regenerateArtifactVersion(latestArtifactVersion)} disabled={isBusy}>
                          再生成
                        </button>
                        <button className="secondary-button" onClick={() => void compareArtifactWithPreviousVersion(latestArtifactVersion)} disabled={isBusy || latestArtifactVersion.version_no <= 1}>
                          与上一版比较
                        </button>
                        <button className="secondary-button" onClick={() => void rollbackArtifactVersion(latestArtifactVersion)} disabled={isBusy}>
                          追加式回滚
                        </button>
                        {latestArtifactVersion.runtime_trace?.export_trace?.status === "COMPILED" ? (
                          <button
                            className="secondary-button"
                            onClick={() => downloadArtifactVersionPdf(latestArtifactVersion)}
                          >
                            下载 PDF
                          </button>
                        ) : null}
                        {artifactSidebarState.latestAuditView.runtimeSummary.length > 0 ? (
                          <div className="artifact-runtime-trace">
                            {artifactSidebarState.latestAuditView.runtimeSummary.map((item) => (
                              <small key={item.label} className="artifact-runtime-trace-line">
                                <strong>{item.label}</strong> · {item.value}
                              </small>
                            ))}
                          </div>
                        ) : null}
                        {artifactSidebarState.latestAuditView.detailSections.length > 0 ? (
                          <details className="artifact-runtime-detail-panel">
                            <summary className="artifact-runtime-detail-toggle">{artifactSidebarState.latestAuditView.detailToggleLabel}</summary>
                            <div className="artifact-runtime-detail-grid">
                              {artifactSidebarState.latestAuditView.detailSections.map((section) => (
                                <section key={section.title} className="artifact-runtime-detail-section">
                                  <strong>{section.title}</strong>
                                  {section.lines.map((line) => (
                                    <small key={`${section.title}-${line}`}>{line}</small>
                                  ))}
                                </section>
                              ))}
                            </div>
                          </details>
                        ) : null}
                      </div>
                    </div>
                  </div>
                ) : (
                  <p className="phase-note">当前还没有已完成的产物版本。</p>
                )}
              </div>

              <div className="artifact-history-section">
                <p className="section-label">最近产物</p>
                {artifactSidebarState.historyItems.length > 0 ? artifactSidebarState.historyItems.map((item) => (
                  <div
                    key={item.key}
                    className={`artifact-history-item${artifactSidebarState.historyViewer.activeKey === item.key ? " active" : ""}`}
                  >
                    <div className="artifact-history-icon" aria-hidden="true">V</div>
                    <div className="artifact-history-copy">
                      <strong>{item.title || resolveArtifactSkillTitle(item.skillKey, artifactStudioSkills)}</strong>
                      <small>
                        Skill {item.skillKey} · v{item.versionNo} · {formatRelativeTime(item.updatedAt)}
                      </small>
                      <small>
                        {summarizeRunStatus(item.status)} · {item.detail || "已沉淀 Artifact Version"}
                      </small>
                    </div>
                    <button
                      className="artifact-history-more secondary-button"
                      onClick={() => void openArtifactHistoryVersion(item)}
                      disabled={isBusy || artifactHistoryLoadingKey === item.key}
                      title="查看版本审计"
                    >
                      {artifactHistoryLoadingKey === item.key ? "加载中" : "审计"}
                    </button>
                  </div>
                )) : (
                  <p className="phase-note">当前还没有可回看的产物历史。</p>
                )}
                {artifactSidebarState.historyViewer.activeVersion ? (
                  <div className="artifact-run-card tone-stable artifact-history-detail-card">
                    <div className="artifact-run-row">
                      <span className="artifact-run-icon" aria-hidden="true">H</span>
                      <div>
                        <strong>
                          {artifactSidebarState.historyViewer.activeVersion.title
                            || resolveArtifactSkillTitle(artifactSidebarState.historyViewer.activeVersion.skill_key, artifactStudioSkills)}
                        </strong>
                        <span>{artifactSidebarState.historyViewer.preview || "该产物版本已生成，可回看其运行时审计细节。"}</span>
                        <small>
                          {artifactSidebarState.historyViewer.scopeLabel} · Skill {artifactSidebarState.historyViewer.activeVersion.skill_key} · v{artifactSidebarState.historyViewer.activeVersion.version_no}
                          {" · "}
                          {formatRelativeTime(artifactSidebarState.historyViewer.activeVersion.created_at)}
                        </small>
                        {artifactSidebarState.historyViewer.activeVersion.files
                          && artifactSidebarState.historyViewer.activeVersion.files.length > 0 ? (
                            <small>
                              文件状态 {artifactSidebarState.historyViewer.activeVersion.files
                                .map((file) => `${file.file_format} · ${file.status} · ${file.storage_backend} · ${file.size_bytes}B`)
                                .join(" / ")}
                            </small>
                          ) : null}
                        <button
                          className="secondary-button"
                          onClick={() => void saveArtifactVersionAsSource(artifactSidebarState.historyViewer.activeVersion!)}
                          disabled={isBusy}
                        >
                          {artifactSavedSourceByVersionId[artifactVersionSaveKey(artifactSidebarState.historyViewer.activeVersion)]
                            ? "已保存为资料"
                            : "保存为资料"}
                        </button>
                        <button
                          className="secondary-button"
                          onClick={() => void writeArtifactVersionToKnowledge(artifactSidebarState.historyViewer.activeVersion!, "NOTE")}
                          disabled={isBusy}
                        >
                          {artifactWritebackByVersionId[artifactVersionSaveKey(artifactSidebarState.historyViewer.activeVersion)]?.includes("NOTE")
                            ? "已写入 Note"
                            : "写入 Note"}
                        </button>
                        <button
                          className="secondary-button"
                          onClick={() => void writeArtifactVersionToKnowledge(artifactSidebarState.historyViewer.activeVersion!, "WIKI")}
                          disabled={isBusy}
                        >
                          {artifactWritebackByVersionId[artifactVersionSaveKey(artifactSidebarState.historyViewer.activeVersion)]?.includes("WIKI")
                            ? "已写入 Wiki"
                            : "写入 Wiki"}
                        </button>
                        <button
                          className="secondary-button"
                          onClick={() => void regenerateArtifactVersion(artifactSidebarState.historyViewer.activeVersion!)}
                          disabled={isBusy}
                        >
                          再生成
                        </button>
                        <button
                          className="secondary-button"
                          onClick={() => void compareArtifactWithPreviousVersion(artifactSidebarState.historyViewer.activeVersion!)}
                          disabled={isBusy || artifactSidebarState.historyViewer.activeVersion.version_no <= 1}
                        >
                          与上一版比较
                        </button>
                        <button
                          className="secondary-button"
                          onClick={() => void rollbackArtifactVersion(artifactSidebarState.historyViewer.activeVersion!)}
                          disabled={isBusy}
                        >
                          追加式回滚
                        </button>
                        {artifactSidebarState.historyViewer.activeVersion.runtime_trace?.export_trace?.status === "COMPILED" ? (
                          <button
                            className="secondary-button"
                            onClick={() => downloadArtifactVersionPdf(artifactSidebarState.historyViewer.activeVersion!)}
                          >
                            下载 PDF
                          </button>
                        ) : null}
                        {artifactSidebarState.historyViewer.runtimeSummary.length > 0 ? (
                          <div className="artifact-runtime-trace">
                            {artifactSidebarState.historyViewer.runtimeSummary.map((item) => (
                              <small key={`history-${item.label}`} className="artifact-runtime-trace-line">
                                <strong>{item.label}</strong> · {item.value}
                              </small>
                            ))}
                          </div>
                        ) : null}
                        {artifactSidebarState.historyViewer.detailSections.length > 0 ? (
                          <details className="artifact-runtime-detail-panel" open>
                            <summary className="artifact-runtime-detail-toggle">{artifactSidebarState.historyViewer.detailToggleLabel}</summary>
                            <div className="artifact-runtime-detail-grid">
                              {artifactSidebarState.historyViewer.detailSections.map((section) => (
                                <section key={`history-${section.title}`} className="artifact-runtime-detail-section">
                                  <strong>{section.title}</strong>
                                  {section.lines.map((line) => (
                                    <small key={`history-${section.title}-${line}`}>{line}</small>
                                  ))}
                                </section>
                              ))}
                            </div>
                          </details>
                        ) : null}
                      </div>
                    </div>
                  </div>
                ) : null}
              </div>

              <div className="artifact-utility-section">
                <p className="section-label">更多工具</p>
                <button className="secondary-button" onClick={openWikiHome} disabled={isBusy || !workspace}>
                  打开 Wiki 工作台
                </button>
                <button className="secondary-button" onClick={openResearchWorkbench} disabled={isBusy || !workspace}>
                  打开 Deep Research
                </button>
                <button className="secondary-button" onClick={openMemoryWorkbench} disabled={isBusy || !workspace}>
                  打开 Memory 审核
                </button>
                <button className="secondary-button" onClick={toggleWikiEnabled} disabled={isBusy || !workspace}>
                  {wikiEnabled ? "关闭 Wiki 构建" : "开启 Wiki 构建"}
                </button>
                <div className="artifact-utility-section">
                  <p className="section-label">Note 确认入库</p>
                  <p className="phase-note">
                    仅 Note 模式回答可整理后进入资料池。系统会先生成中性 Markdown 草稿（模板/LLM），你确认后再入库；入库后与上传资料一视同仁。
                  </p>
                  <label className="rail-field">
                    <span>资料标题</span>
                    <input
                      value={sourceDraftTitle}
                      onChange={(event) => setSourceDraftTitle(event.target.value)}
                      placeholder="例如：阶段3 Note 整理"
                    />
                  </label>
                  <label className="rail-field">
                    <span>中性 Markdown 正文</span>
                    <textarea
                      value={sourceDraftContent}
                      onChange={(event) => setSourceDraftContent(event.target.value)}
                      rows={8}
                      placeholder="Note 回答完成后会自动生成中性草稿，也可点下方按钮重写"
                    />
                  </label>
                  {sourceDraftRewriteMode ? (
                    <p className="phase-note">当前草稿来源：{sourceDraftRewriteMode}</p>
                  ) : null}
                  <button
                    className="secondary-button"
                    onClick={() => void rewriteNoteSourceDraft()}
                    disabled={isBusy || !workspace || !lastNoteAssistantMessageId}
                  >
                    生成/刷新中性草稿
                  </button>
                  <button
                    onClick={() => void saveNoteAnswerAsSource()}
                    disabled={isBusy || !workspace || !lastNoteAssistantMessageId}
                  >
                    保存最新回答为 Note
                  </button>
                </div>
                {wikiRebuildAdvice ? <p className="phase-note">{wikiRebuildAdvice.message}</p> : null}
              </div>
            </>
          )}
        </aside>
  );
}
