import { BookOpenCheck, BookPlus, FileStack, Link2, Network, Sparkles } from "lucide-react";

type WikiEmptyWorkbenchProps = {
  workspaceName: string;
  readySourceCount: number;
  wikiEnabled: boolean;
  advice: string;
  action?: { label: string; run: () => void } | null;
  wikiTitle: string;
  setWikiTitle: (title: string) => void;
  wikiDraft: string;
  setWikiDraft: (draft: string) => void;
  createWikiPage: () => void;
  isBusy: boolean;
};

export function WikiEmptyWorkbench({
  workspaceName,
  readySourceCount,
  wikiEnabled,
  advice,
  action,
  wikiTitle,
  setWikiTitle,
  wikiDraft,
  setWikiDraft,
  createWikiPage,
  isBusy
}: WikiEmptyWorkbenchProps) {
  return (
    <section className="wiki-workbench wiki-empty-workbench" aria-labelledby="wiki-empty-title">
      <article className="wiki-empty-main">
        <span className="wiki-empty-mark" aria-hidden="true"><Network size={24} /></span>
        <p className="section-label">工作台知识</p>
        <h2 id="wiki-empty-title">准备工作台知识网络</h2>
        <p>Wiki 会把资料与人工维护内容组织为可追踪版本、引用和页面关系。</p>

        <div className="wiki-empty-flow" aria-label="Wiki 构建流程">
          <span className={readySourceCount > 0 ? "is-ready" : "is-current"}>
            <FileStack size={16} aria-hidden="true" /><strong>准备资料</strong><small>{readySourceCount} 份已解析</small>
          </span>
          <span className={wikiEnabled ? "is-ready" : ""}>
            <BookOpenCheck size={16} aria-hidden="true" /><strong>生成页面</strong><small>{wikiEnabled ? "构建已开启" : "等待开启"}</small>
          </span>
          <span>
            <Link2 size={16} aria-hidden="true" /><strong>维护关系</strong><small>页面生成后可用</small>
          </span>
        </div>

        {action ? (
          <button type="button" className="wiki-empty-action" disabled={isBusy} onClick={action.run}>
            <Sparkles size={16} aria-hidden="true" />{action.label}
          </button>
        ) : null}
      </article>

      <aside className="wiki-empty-context" aria-label="Wiki 准备状态">
        <p className="section-label">当前工作台</p>
        <strong title={workspaceName}>{workspaceName}</strong>
        <dl>
          <div><dt>已解析资料</dt><dd>{readySourceCount}</dd></div>
          <div><dt>Wiki 构建</dt><dd>{wikiEnabled ? "已开启" : "未开启"}</dd></div>
          <div><dt>知识页面</dt><dd>0</dd></div>
        </dl>
        <div className="wiki-empty-advice">
          <span>下一步</span>
          <p>{advice}</p>
        </div>
        <details className="wiki-empty-manual">
          <summary><BookPlus size={15} aria-hidden="true" />手动创建首个页面</summary>
          <form
            onSubmit={(event) => {
              event.preventDefault();
              createWikiPage();
            }}
          >
            <label className="rail-field">
              <span>页面标题</span>
              <input
                aria-label="首个 Wiki 页面标题"
                value={wikiTitle}
                onChange={(event) => setWikiTitle(event.target.value)}
              />
            </label>
            <label className="rail-field">
              <span>页面正文</span>
              <textarea
                aria-label="首个 Wiki 页面正文"
                value={wikiDraft}
                onChange={(event) => setWikiDraft(event.target.value)}
                placeholder="记录定义、关键机制、结论或相关资料。"
                rows={5}
              />
            </label>
            <button
              type="submit"
              className="wiki-empty-action"
              disabled={isBusy || !wikiTitle.trim() || !wikiDraft.trim()}
            >
              <BookPlus size={16} aria-hidden="true" />创建首个 Wiki 页面
            </button>
          </form>
        </details>
      </aside>
    </section>
  );
}
