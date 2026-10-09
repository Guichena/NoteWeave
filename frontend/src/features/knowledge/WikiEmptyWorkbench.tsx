import { BookPlus, Network, Sparkles } from "lucide-react";

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
        <span className="wiki-empty-mark" aria-hidden="true"><Network size={22} /></span>
        <h2 id="wiki-empty-title">准备工作台知识网络</h2>
        <p>{advice}</p>

        {action ? (
          <button type="button" className="wiki-empty-action" disabled={isBusy} onClick={action.run}>
            <Sparkles size={16} aria-hidden="true" />{action.label}
          </button>
        ) : null}

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
      </article>
    </section>
  );
}
