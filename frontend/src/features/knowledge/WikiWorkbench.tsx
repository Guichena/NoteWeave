import type { WikiWorkbenchProps } from "./buildWikiWorkbenchProps";
import { WikiIndexPanel } from "./WikiIndexPanel";

export type { WikiWorkbenchProps };

export function WikiWorkbench({
  isBusy,
  workspace,
  filters,
  draft,
  selection,
  data,
  actions,
  helpers,
  derived
}: WikiWorkbenchProps) {
  const props = {
    isBusy,
    workspace,
    ...filters,
    ...draft,
    ...selection,
    ...data,
    ...actions,
    ...helpers,
    ...derived,
    wikiIssues: data.wikiIssues,
    wikiIssueTypes: derived.wikiIssueTypes,
    wikiIssueSeverities: derived.wikiIssueSeverities
  };
  const wikiIssues = props.wikiIssues ?? [];
  const wikiIssueTypes = props.wikiIssueTypes ?? [];
  const wikiIssueSeverities = props.wikiIssueSeverities ?? [];
  return (
      <section className="wiki-workbench">
        <WikiIndexPanel
          isBusy={props.isBusy}
          wikiHome={props.wikiHome}
          wikiIndex={props.wikiIndex}
          wikiUrl={props.wikiHome.wiki_url}
          wikiSearch={props.wikiSearch}
          setWikiSearch={props.setWikiSearch}
          wikiKindFilter={props.wikiKindFilter}
          setWikiKindFilter={props.setWikiKindFilter}
          availableWikiKinds={props.availableWikiKinds}
          visibleWikiPages={props.visibleWikiPages}
          groupedWikiPages={props.groupedWikiPages}
          selectedWikiItemId={props.selectedWikiItemId}
          selectedWikiPage={props.selectedWikiPage}
          wikiRebuildAdvice={props.wikiRebuildAdvice}
          openWikiIndex={props.openWikiIndex}
          selectWikiPage={props.selectWikiPage}
        />

        <article className="wiki-page">
          <p className="section-label">{props.selectedWikiPage ? "Wiki Page" : "Wiki Index"}</p>
          {props.selectedWikiPage ? (
            <>
              <h2>{props.selectedWikiPage.title}</h2>
              <div className="wiki-maintenance">
                <strong>页面类型</strong>
                <span>{props.selectedWikiDetail?.page_kind || props.selectedWikiPage.page_kind || "TOPIC"}</span>
              </div>
              <div className="wiki-maintenance">
                <strong>当前查看</strong>
                <span>
                  {props.selectedWikiVersionDetail && props.selectedWikiVersionDetail.version_no !== (props.selectedWikiDetail?.latest_version_no ?? props.selectedWikiPage.latest_version_no)
                    ? `历史版本 v${props.selectedWikiVersionDetail.version_no}`
                    : `最新版本 v${props.selectedWikiDetail?.latest_version_no ?? props.selectedWikiPage.latest_version_no}`}
                </span>
              </div>
              <div className="wiki-maintenance">
                <strong>版本来源</strong>
                <span>
                  {props.selectedWikiVersionDetail?.source_message_id
                    ? `来自聊天消息 ${props.selectedWikiVersionDetail.source_message_id}`
                    : "来自工作台级资料 ingest 或人工维护"}
                  {" · "}
                  {props.formatDateTime(props.selectedWikiVersionDetail?.created_at)}
                </span>
              </div>
              <div className="wiki-maintenance">
                <strong>页面更新时间</strong>
                <span>{props.formatDateTime(props.selectedWikiDetail?.updated_at ?? props.selectedWikiPage.updated_at)}</span>
              </div>
              <p className="version-pill">当前版本 v{props.selectedWikiDetail?.latest_version_no ?? props.selectedWikiPage.latest_version_no}</p>
              <div className="wiki-summary">
                {(props.selectedWikiVersionDetail?.version_no === (props.selectedWikiDetail?.latest_version_no ?? props.selectedWikiPage.latest_version_no)
                  ? props.selectedWikiDetail?.content
                  : props.selectedWikiVersionDetail?.content) || props.selectedWikiDetail?.content || props.selectedWikiPage.summary || "这个页面暂时还没有正文。"}
              </div>
              <div className="wiki-citations">
                <strong>版本历史</strong>
                {props.selectedWikiVersions.length === 0 && <span>当前页面还没有版本记录。</span>}
                {props.selectedWikiVersions.map((version) => (
                  <button
                    key={version.version_id}
                    className="secondary-button"
                    disabled={props.isBusy}
                    onClick={() => void props.loadWikiVersion(props.selectedWikiPage!.item_id, version.version_no)}
                  >
                    v{version.version_no} · 引用 {version.citation_count} · {version.source_message_id ? "聊天来源" : "工作台维护"} · {version.summary || "无摘要"}
                  </button>
                ))}
                {props.selectedWikiVersionDetail && props.selectedWikiDetail && props.selectedWikiVersionDetail.version_no !== props.selectedWikiDetail.latest_version_no ? (
                  <button className="secondary-button" disabled={props.isBusy} onClick={props.restoreLatestWikiVersion}>
                    返回最新版本
                  </button>
                ) : null}
              </div>
              {props.selectedWikiVersionDetail && props.selectedWikiVersionDetail.version_no !== (props.selectedWikiDetail?.latest_version_no ?? props.selectedWikiPage.latest_version_no) ? (
                <div className="wiki-citations">
                  <strong>历史版本引用</strong>
                  {props.selectedWikiVersionDetail.citations.length === 0 && <span>该历史版本没有绑定引用。</span>}
                  {props.selectedWikiVersionDetail.citations.map((citation, index) => (
                    <span key={`history-${citation.citation_id}`}>
                      {index + 1}. {props.buildKnowledgeCitationLabel(citation)}：{citation.quote_text}
                    </span>
                  ))}
                </div>
              ) : null}
              {props.selectedWikiDetail?.citations.length ? (
                <div className="wiki-citations">
                  <strong>来源引用</strong>
                  {props.selectedWikiDetail.citations.map((citation, index) => (
                    <span key={citation.citation_id}>
                      {index + 1}. {props.buildKnowledgeCitationLabel(citation)}：{citation.quote_text}
                    </span>
                  ))}
                </div>
              ) : null}
              <div className="wiki-maintenance">
                <strong>页面出链</strong>
                {props.selectedWikiDetail?.outgoing_links?.length ? (
                  <span>
                    {props.selectedWikiDetail.outgoing_links.map((link, index) => (
                      <button
                        key={`${link.source_item_id}-${link.target_title}-${index}-inline`}
                        className="inline-action"
                        disabled={props.isBusy || !link.target_item_id}
                        onClick={() => link.target_item_id ? void props.openWikiPageById(link.target_item_id) : undefined}
                      >
                        {link.target_title}
                      </button>
                    ))}
                  </span>
                ) : "当前页面暂无显式出链"}
              </div>
              <div className="wiki-maintenance">
                <strong>反向链接</strong>
                {props.selectedWikiDetail?.backlinks?.length ? (
                  <span>
                    {props.selectedWikiDetail.backlinks.map((link, index) => (
                      <button
                        key={`${link.source_item_id}-${link.target_title}-${index}-back-inline`}
                        className="inline-action"
                        disabled={props.isBusy || !link.source_item_id}
                        onClick={() => link.source_item_id ? void props.openWikiPageById(link.source_item_id) : undefined}
                      >
                        {link.target_title}
                      </button>
                    ))}
                  </span>
                ) : "当前页面暂无反向链接"}
              </div>
              <div className="wiki-maintenance">
                <strong>页面维护动作</strong>
                <span>编辑正文会生成新版本；重命名会刷新页面关系并同步改写引用页；删除采用软删除，引用它的页面关系会回退为未解析。</span>
              </div>
              <div className="wiki-citations">
                <strong>页面健康问题</strong>
                {props.selectedWikiIssues.length ? props.selectedWikiIssues.map((issue, index) => (
                  (() => {
                    const issueAction = props.getWikiIssuePrimaryAction(issue);
                    return (
                      <div className="link-card" key={`page-issue-${issue.issue_type}-${index}`}>
                        <strong>{issue.issue_type} / {issue.severity}</strong>
                        <span>{issue.message}</span>
                        <span>{issue.auto_fixable ? "可自动修复" : "需人工确认"}</span>
                        <span>{issue.suggested_action}</span>
                        <div className="wiki-inline-actions">
                          {issue.auto_fixable ? (
                            <button className="secondary-button" disabled={props.isBusy || !props.workspace} onClick={() => void props.autoFixWiki()}>
                              自动补缺
                            </button>
                          ) : null}
                          {issueAction ? (
                            <button className="secondary-button" disabled={props.isBusy} onClick={issueAction.run}>
                              {issueAction.label}
                            </button>
                          ) : null}
                          {issue.item_id && issue.item_id !== props.selectedWikiItemId ? (
                            <button className="secondary-button" disabled={props.isBusy} onClick={() => void props.openWikiPageById(issue.item_id!)}>
                              打开相关页面
                            </button>
                          ) : null}
                        </div>
                      </div>
                    );
                  })()
                )) : <span>当前页面没有单独的维护提醒。</span>}
              </div>
              <div className="wiki-citations">
                <strong>页面最近变更</strong>
                {props.selectedWikiLog.length ? props.selectedWikiLog.slice(0, 5).map((entry) => (
                  <div className="link-card" key={`page-log-${entry.id}`}>
                    <strong>{entry.event_type}</strong>
                    <span>{entry.message}</span>
                    <span>{props.formatDateTime(entry.created_at)}</span>
                  </div>
                )) : <span>当前页面还没有可展示的变更日志。</span>}
              </div>
              <label className="input-block">
                <span>重命名页面</span>
                <input value={props.wikiRenameTitle} onChange={(event) => props.setWikiRenameTitle(event.target.value)} />
              </label>
              <div className="maintenance-actions">
                <button onClick={props.renameSelectedWikiPage} disabled={props.isBusy}>
                  重命名
                </button>
                <button className="danger-button" onClick={props.deleteSelectedWikiPage} disabled={props.isBusy}>
                  删除页面
                </button>
              </div>
              <label className="input-block">
                <span>追加为新版本</span>
                <textarea
                  value={props.wikiAppendDraft}
                  onChange={(event) => props.setWikiAppendDraft(event.target.value)}
                  placeholder="粘贴或编辑新的 Wiki 页面正文，提交后 latest_version_no 会递增。"
                  rows={6}
                />
              </label>
              <button onClick={props.appendWikiVersion} disabled={props.isBusy}>
                追加 Wiki 版本
              </button>
            </>
          ) : (
            <>
              <h2>工作台总览</h2>
              <div className="wiki-summary">
                当前 Wiki 工作台默认先展示工作台级总览，再按需进入具体页面。

                {"\n\n"}这和 WeKnora 的浏览器逻辑保持一致：Wiki 首先绑定研究工作台与资料变化，
                页面只是这套工作台级知识网络里的阅读与维护对象，而不是聊天临时草稿。
              </div>
              <div className="wiki-maintenance">
                <strong>构建状态</strong>
                <span>
                  {props.wikiIndex?.wiki_enabled ? "工作台级 Wiki 构建已开启" : "工作台级 Wiki 构建未开启"} ·
                  READY 资料 {props.wikiIndex?.ready_source_count ?? 0} ·
                  页面 {props.wikiIndex?.page_count ?? 0} ·
                  待处理任务 {props.wikiIndex?.pending_task_count ?? 0}
                </span>
              </div>
              <div className="wiki-maintenance">
                <strong>页面构成</strong>
                <span>
                  资料驱动页面 {props.wikiIndex?.source_backed_page_count ?? 0} ·
                  人工维护页面 {props.wikiIndex?.manual_page_count ?? 0} ·
                  链接 {props.wikiIndex?.link_count ?? 0} ·
                  断链 {props.wikiIndex?.unresolved_link_count ?? 0}
                </span>
              </div>
              <div className="wiki-maintenance">
                <strong>构建建议</strong>
                <span>{props.wikiRebuildAdvice?.message ?? "当前工作台会在这里显示 Wiki 的自动构建建议。"}</span>
                {props.wikiAdviceAction ? (
                  <button className="secondary-button" disabled={props.isBusy || !props.workspace} onClick={props.wikiAdviceAction.run}>
                    {props.wikiAdviceAction.label}
                  </button>
                ) : null}
              </div>
              <div className="wiki-citations">
                <strong>页面类型分布</strong>
                <span>{props.wikiIndex ? Object.entries(props.wikiIndex.pages_by_kind).map(([kind, count]) => `${kind}:${count}`).join(" / ") || "暂无页面" : "暂无页面"}</span>
              </div>
              <div className="wiki-citations">
                <strong>最近更新页面</strong>
                {props.wikiIndex?.recent_updates?.length ? props.wikiIndex.recent_updates.map((page) => props.renderWikiRecentUpdateCard(page, `index-page-${page.item_id}`)) : <span>当前还没有已沉淀的 Wiki 页面。</span>}
              </div>
              <div className="wiki-citations">
                <strong>最近资料变化</strong>
                {props.wikiIndex?.recent_sources?.length ? props.wikiIndex.recent_sources.map((source) => {
                  const sourceAction = props.getRecentSourceAction(source);
                  return (
                    <div className="link-card" key={`index-source-${source.source_id}`}>
                      <strong>{source.title}</strong>
                      <span>{source.status} · {source.index_status} · {props.formatDateTime(source.updated_at)}</span>
                      {source.related_pages.length ? (
                        <div className="wiki-inline-actions">
                          {source.related_pages.map((page) => (
                            <button
                              key={`source-page-${source.source_id}-${page.item_id}`}
                              className="secondary-button"
                              disabled={props.isBusy}
                              onClick={() => void props.openWikiPageById(page.item_id)}
                            >
                              打开 {page.title}
                            </button>
                          ))}
                        </div>
                      ) : (
                        <>
                          <span>当前资料尚未关联到可打开的 Wiki 页面。</span>
                          {sourceAction ? (
                            <div className="wiki-inline-actions">
                              <button className="secondary-button" disabled={props.isBusy} onClick={sourceAction.run}>
                                {sourceAction.label}
                              </button>
                            </div>
                          ) : null}
                        </>
                      )}
                    </div>
                  );
                }) : <span>当前工作台还没有资料。</span>}
              </div>
              <details className="maintenance-drawer">
                <summary>
                  <strong>维护提醒</strong>
                  <span>
                    待处理任务 {props.wikiIndex?.pending_task_count ?? 0} ·
                    自动补缺 {props.wikiIndex?.auto_fixable_issue_count ?? 0} ·
                    人工确认 {props.wikiIndex?.manual_review_issue_count ?? 0}
                  </span>
                </summary>
                <div className="wiki-citations">
                  <strong>最近 Wiki 任务</strong>
                  {props.wikiIndex?.recent_tasks?.length ? props.wikiIndex.recent_tasks.map((task) => props.renderWikiTaskCard(task, `index-task-${task.task_id}`)) : <span>当前还没有 Wiki 相关任务记录。</span>}
                </div>
                <div className="wiki-citations">
                  <strong>优先维护项</strong>
                  {props.wikiIndex?.top_issues?.length ? props.wikiIndex.top_issues.map((issue, index) => {
                    const issueAction = props.getWikiIssuePrimaryAction(issue);
                    return (
                      <div className="link-card" key={`index-issue-${issue.issue_type}-${index}`}>
                        <strong>{issue.issue_type} / {issue.severity}</strong>
                        <span>{issue.message}</span>
                        <span>{issue.auto_fixable ? "可自动补缺" : "需人工确认"}</span>
                        <span>{issue.suggested_action}</span>
                        <div className="wiki-inline-actions">
                          {issue.auto_fixable ? (
                            <button className="secondary-button" disabled={props.isBusy || !props.workspace} onClick={() => void props.autoFixWiki()}>
                              自动补缺
                            </button>
                          ) : null}
                          {issueAction ? (
                            <button className="secondary-button" disabled={props.isBusy} onClick={issueAction.run}>
                              {issueAction.label}
                            </button>
                          ) : null}
                          {issue.item_id ? (
                            <button className="secondary-button" disabled={props.isBusy} onClick={() => void props.openWikiPageById(issue.item_id!)}>
                              打开相关页面
                            </button>
                          ) : null}
                        </div>
                      </div>
                    );
                  }) : <span>当前没有需要优先处理的维护项。</span>}
                </div>
              </details>
            </>
          )}
        </article>

        <aside className="wiki-links">
          <p className="section-label">{props.selectedWikiPage ? "页面关系" : "工作台关系"}</p>
          {props.wikiStats && (
            <>
              <div className="wiki-maintenance">
                <strong>Wiki 健康度</strong>
                <span>页面 {props.wikiStats.page_count} · 链接 {props.wikiStats.link_count} · 已解析 {props.wikiStats.resolved_link_count} · 断链 {props.wikiStats.unresolved_link_count} · 引用 {props.wikiStats.citation_count} · 问题 {props.wikiStats.issue_count}</span>
              </div>
              <div className="wiki-maintenance">
                <strong>维护提醒</strong>
                <span>自动补缺 {props.wikiStats.auto_fixable_issue_count} · 人工确认 {props.wikiStats.manual_review_issue_count}</span>
              </div>
              <div className="wiki-maintenance">
                <strong>工作台状态</strong>
                <span>{props.wikiStats.wiki_enabled ? "Wiki 构建已开启" : "Wiki 构建未开启"} · 待处理任务 {props.wikiStats.pending_task_count}</span>
              </div>
              <div className="wiki-maintenance">
                <strong>页面类型分布</strong>
                <span>{Object.entries(props.wikiStats.pages_by_kind).map(([kind, count]) => `${kind}:${count}`).join(" / ") || "暂无页面"}</span>
              </div>
            </>
          )}
          {props.selectedWikiPage && props.selectedWikiDetail?.outgoing_links?.length === 0 && props.selectedWikiDetail?.backlinks?.length === 0 && (
            <p className="empty-state">当前页面暂无直接关系。</p>
          )}
          {props.selectedWikiDetail?.outgoing_links?.map((link, index) => (
            <div className="link-card" key={`${link.source_item_id}-${link.target_title}-${index}`}>
              <strong>{link.target_title}</strong>
              <span>出链 · {props.formatWikiRelationType(link.relation_type)} · {link.relation_status} · {link.mention_count} 次提及</span>
              <div className="wiki-inline-actions">
                {link.target_item_id ? (
                  <button className="secondary-button" disabled={props.isBusy} onClick={() => void props.openWikiPageById(link.target_item_id!)}>
                    打开页面
                  </button>
                ) : (
                  <button
                    className="secondary-button"
                    disabled={props.isBusy || !props.selectedWikiPage}
                    onClick={() => props.selectedWikiPage ? props.prepareWikiLinkRepair(link.target_title, props.selectedWikiPage.title) : undefined}
                  >
                    预填补缺页
                  </button>
                )}
              </div>
            </div>
          ))}
          {props.selectedWikiDetail?.backlinks?.map((link, index) => (
            <div className="link-card" key={`backlink-${link.source_item_id}-${link.target_title}-${index}`}>
              <strong>{link.target_title}</strong>
              <span>反链 · {props.formatWikiRelationType(link.relation_type)} · {link.relation_status} · {link.mention_count} 次提及</span>
              {link.source_item_id ? (
                <button className="secondary-button" disabled={props.isBusy} onClick={() => void props.openWikiPageById(link.source_item_id)}>
                  打开来源页
                </button>
              ) : null}
            </div>
          ))}
          <p className="section-label">Wiki Graph</p>
          <div className="wiki-maintenance">
            <span>
              {props.wikiGraph?.meta.mode === "ego" ? "当前页面局部子图" : "工作台总览图"} ·
              节点 {props.wikiGraph?.nodes.length ?? 0} / {props.wikiGraph?.meta.total_nodes ?? 0} ·
              边 {props.wikiGraph?.edges.length ?? 0}
              {props.wikiGraph?.meta.truncated ? " · 已截断" : ""}
            </span>
          </div>
          <div className="wiki-maintenance">
            <strong>图谱视角</strong>
            <button className={props.wikiGraphMode === "overview" ? "active" : ""} onClick={() => void props.switchWikiGraphMode("overview")} disabled={props.isBusy || !props.workspace}>
              总览图
            </button>
            <button className={props.wikiGraphMode === "ego" ? "active" : ""} onClick={() => void props.switchWikiGraphMode("ego")} disabled={props.isBusy || !props.workspace || !props.selectedWikiItemId}>
              当前页面子图
            </button>
          </div>
          <div className="wiki-maintenance">
            <strong>图谱类型过滤</strong>
            <span>{props.graphFilterLabel}</span>
            <div className="wiki-inline-pills">
              <button
                className={props.wikiGraphKindFilters.length === 0 ? "active filter-pill" : "filter-pill"}
                disabled={props.isBusy || !props.workspace}
                onClick={() => void props.resetWikiGraphKinds()}
              >
                全部
              </button>
              {props.availableWikiKinds.map((kind) => (
                <button
                  key={`graph-kind-${kind}`}
                  className={props.wikiGraphKindFilters.includes(kind) ? "active filter-pill" : "filter-pill"}
                  disabled={props.isBusy || !props.workspace}
                  onClick={() => void props.toggleWikiGraphKind(kind)}
                >
                  {kind}
                </button>
              ))}
            </div>
          </div>
          <div className="wiki-maintenance">
            <strong>图谱搜索</strong>
            <input
              value={props.wikiGraphSearch}
              onChange={(event) => props.setWikiGraphSearch(event.target.value)}
              placeholder="搜索页面标题、摘要或页面类型"
            />
            {props.graphSearchHits.length ? (
              <div className="wiki-search-hit-list">
                {props.graphSearchHits.map((page) => (
                  <div className="link-card" key={`graph-search-${page.item_id}`}>
                    <strong>{page.title}</strong>
                    <span>{page.page_kind || "TOPIC"} · v{page.latest_version_no}</span>
                    <div className="wiki-inline-actions">
                      <button className="secondary-button" disabled={props.isBusy} onClick={() => void props.openWikiPageById(page.item_id)}>
                        打开页面
                      </button>
                      <button className="secondary-button" disabled={props.isBusy} onClick={() => void props.openWikiGraphPage(page.item_id)}>
                        查看子图
                      </button>
                    </div>
                  </div>
                ))}
              </div>
            ) : props.wikiGraphSearch.trim() ? <span>没有匹配的图谱页面。</span> : null}
          </div>
          {props.wikiGraph?.nodes.slice(0, 6).map((node) => (
            <div className="link-card" key={`graph-node-${node.item_id}`}>
              <strong>{node.title}</strong>
              <span>
                {node.page_kind} · degree {node.degree} · 出链 {node.outgoing_count} · 反链 {node.backlink_count} · 引用 {node.citation_count}
                {node.unresolved_count > 0 ? ` · 断链 ${node.unresolved_count}` : ""}
              </span>
              <div className="wiki-inline-actions">
                <button className="secondary-button" disabled={props.isBusy} onClick={() => void props.openWikiPageById(node.item_id)}>
                  打开页面
                </button>
                <button className="secondary-button" disabled={props.isBusy} onClick={() => void props.openWikiGraphPage(node.item_id)}>
                  查看子图
                </button>
              </div>
            </div>
          ))}
          {props.wikiGraph?.edges.slice(0, 6).map((edge, index) => (
            <div className="link-card" key={`graph-edge-${edge.source_item_id}-${edge.target_title}-${index}`}>
              <strong>{edge.source_title} {"->"} {edge.target_title}</strong>
              <span>{edge.relation_status} · {edge.mention_count} 次提及 · {props.formatWikiRelationType(edge.relation_type)}</span>
              <div className="wiki-inline-actions">
                <button className="secondary-button" disabled={props.isBusy} onClick={() => void props.openWikiPageById(edge.source_item_id)}>
                  打开来源页
                </button>
                {!edge.target_item_id ? (
                  <button className="secondary-button" disabled={props.isBusy} onClick={() => props.prepareWikiLinkRepair(edge.target_title, edge.source_title)}>
                    预填补缺页
                  </button>
                ) : null}
                {edge.target_item_id ? (
                  <button className="secondary-button" disabled={props.isBusy} onClick={() => void props.openWikiPageById(edge.target_item_id!)}>
                    打开目标页
                  </button>
                ) : null}
                {edge.target_item_id ? (
                  <button className="secondary-button" disabled={props.isBusy} onClick={() => void props.openWikiGraphPage(edge.target_item_id!)}>
                    查看目标子图
                  </button>
                ) : null}
              </div>
            </div>
          ))}
          <details className="maintenance-drawer">
            <summary>
              <strong>维护工具</strong>
              <span>重建链接、自动补缺、维护问题、日志与手动补页</span>
            </summary>
            <div className="wiki-maintenance">
              <strong>维护动作</strong>
              <button onClick={props.rebuildWikiLinks} disabled={props.isBusy || !props.workspace}>重建链接</button>
              <button onClick={props.autoFixWiki} disabled={props.isBusy || !props.workspace}>自动补缺</button>
            </div>
            {props.wikiRebuildAdvice ? (
              <div className="wiki-maintenance">
                <strong>构建建议</strong>
                <span>{props.wikiRebuildAdvice.message}</span>
                {props.wikiAdviceAction ? (
                  <button className="secondary-button" disabled={props.isBusy || !props.workspace} onClick={props.wikiAdviceAction.run}>
                    {props.wikiAdviceAction.label}
                  </button>
                ) : null}
              </div>
            ) : null}
            {props.reviewRequiredIssues.length ? (
              <div className="wiki-citations">
                <strong>人工确认队列</strong>
                {props.reviewRequiredIssues.slice(0, 4).map((issue, index) => {
                  const issueAction = props.getWikiIssuePrimaryAction(issue);
                  return (
                    <div className="link-card" key={`manual-issue-${issue.issue_type}-${index}`}>
                      <strong>{issue.issue_type} / {issue.severity}</strong>
                      <span>{issue.message}</span>
                      <span>{issue.suggested_action}</span>
                      <div className="wiki-inline-actions">
                        {issueAction ? (
                          <button className="secondary-button" disabled={props.isBusy} onClick={issueAction.run}>
                            {issueAction.label}
                          </button>
                        ) : null}
                        {issue.item_id ? (
                          <button className="secondary-button" disabled={props.isBusy} onClick={() => void props.openWikiPageById(issue.item_id!)}>
                            打开相关页面
                          </button>
                        ) : null}
                        <button className="secondary-button" disabled={props.isBusy} onClick={() => void props.focusWikiIssue(issue)}>
                          打开问题列表
                        </button>
                      </div>
                    </div>
                  );
                })}
              </div>
            ) : null}
            <div className="wiki-maintenance">
              <strong>任务队列</strong>
              <span>
                {props.wikiStats?.wiki_enabled ? "工作台级 Wiki 已开启" : "工作台级 Wiki 未开启"} ·
                待处理 {props.wikiStats?.pending_task_count ?? 0} ·
                最近任务 {(props.wikiStats?.recent_tasks?.length ?? 0)}
              </span>
            </div>
            {props.wikiStats?.recent_tasks?.length ? (
              <div className="wiki-citations">
                <strong>最近任务详情</strong>
                {props.wikiStats.recent_tasks.slice(0, 4).map((task) => props.renderWikiTaskCard(task, `recent-task-${task.task_id}`))}
              </div>
            ) : null}
            {props.wikiStats?.recent_updates?.length ? (
              <div className="wiki-citations">
                <strong>最近更新</strong>
                {props.wikiStats.recent_updates.slice(0, 4).map((page) => props.renderWikiRecentUpdateCard(page, `recent-update-${page.item_id}`))}
              </div>
            ) : null}
            <div className="wiki-citations">
              <strong>手动补页 / 修正文案</strong>
              <span>这里用于补缺页面或修正文案。若标题已存在，系统会直接追加新版本，而不是重复创建同名页面。</span>
              <label className="rail-field">
                <span>页面标题</span>
                <input value={props.wikiTitle} onChange={(event) => props.setWikiTitle(event.target.value)} />
              </label>
              <label className="rail-field">
                <span>页面正文</span>
                <textarea
                  value={props.wikiDraft}
                  onChange={(event) => props.setWikiDraft(event.target.value)}
                  placeholder="可以从断链关系或人工确认项预填草稿，也可以手动输入正文。"
                  rows={8}
                />
              </label>
              <div className="wiki-inline-actions">
                <button disabled={props.isBusy || !props.workspace} onClick={props.createWikiPage}>
                  提交补页 / 修正文案
                </button>
                <button className="secondary-button" disabled={props.isBusy} onClick={props.clearWikiRepairDraft}>
                  清空草稿
                </button>
              </div>
              <span>如果当前是在修已有页面正文，优先使用中间区域的“追加 Wiki 版本”。</span>
            </div>
            <p className="section-label">维护问题列表</p>
            <div className="wiki-maintenance">
              <strong>问题过滤</strong>
              <div className="wiki-inline-pills">
                <button
                  className={props.wikiIssuePageFilter === "ALL" ? "active filter-pill" : "filter-pill"}
                  onClick={() => props.setWikiIssuePageFilter("ALL")}
                  disabled={props.isBusy}
                >
                  全工作台
                </button>
                <button
                  className={props.wikiIssuePageFilter === "CURRENT" ? "active filter-pill" : "filter-pill"}
                  onClick={() => props.setWikiIssuePageFilter("CURRENT")}
                  disabled={props.isBusy || !props.selectedWikiItemId}
                >
                  当前页面 {props.selectedWikiIssues.length}
                </button>
              </div>
              <div className="wiki-inline-pills">
                <button
                  className={props.wikiIssueScopeFilter === "ALL" ? "active filter-pill" : "filter-pill"}
                  onClick={() => props.setWikiIssueScopeFilter("ALL")}
                  disabled={props.isBusy}
                >
                  全部 {wikiIssues.length}
                </button>
                <button
                  className={props.wikiIssueScopeFilter === "AUTO" ? "active filter-pill" : "filter-pill"}
                  onClick={() => props.setWikiIssueScopeFilter("AUTO")}
                  disabled={props.isBusy}
                >
                  自动 {props.autoFixableIssues.length}
                </button>
                <button
                  className={props.wikiIssueScopeFilter === "MANUAL" ? "active filter-pill" : "filter-pill"}
                  onClick={() => props.setWikiIssueScopeFilter("MANUAL")}
                  disabled={props.isBusy}
                >
                  人工 {props.reviewRequiredIssues.length}
                </button>
              </div>
              <div className="wiki-inline-pills">
                <button
                  className={props.wikiIssueTypeFilter === "ALL" ? "active filter-pill" : "filter-pill"}
                  onClick={() => props.setWikiIssueTypeFilter("ALL")}
                  disabled={props.isBusy}
                >
                  全部类型
                </button>
                {wikiIssueTypes.map((issueType) => (
                  <button
                    key={`issue-type-${issueType}`}
                    className={props.wikiIssueTypeFilter === issueType ? "active filter-pill" : "filter-pill"}
                    onClick={() => props.setWikiIssueTypeFilter(issueType)}
                    disabled={props.isBusy}
                  >
                    {issueType}
                  </button>
                ))}
              </div>
              <div className="wiki-inline-pills">
                <button
                  className={props.wikiIssueSeverityFilter === "ALL" ? "active filter-pill" : "filter-pill"}
                  onClick={() => props.setWikiIssueSeverityFilter("ALL")}
                  disabled={props.isBusy}
                >
                  全部级别
                </button>
                {wikiIssueSeverities.map((severity) => (
                  <button
                    key={`issue-severity-${severity}`}
                    className={props.wikiIssueSeverityFilter === severity ? "active filter-pill" : "filter-pill"}
                    onClick={() => props.setWikiIssueSeverityFilter(severity)}
                    disabled={props.isBusy}
                  >
                    {severity}
                  </button>
                ))}
              </div>
            </div>
            {props.filteredWikiIssues.length === 0 && <p className="empty-state">当前过滤条件下暂无维护问题。</p>}
            {props.filteredWikiIssues.slice(0, 8).map((issue, index) => {
              const issueAction = props.getWikiIssuePrimaryAction(issue);
              return (
                <div className="link-card" key={`${issue.issue_type}-${issue.title}-${index}`}>
                  <strong>{issue.issue_type} · {issue.severity}</strong>
                  <span>{issue.message}</span>
                  <span>{issue.auto_fixable ? "可自动补缺" : "需人工确认"}</span>
                  <span>{issue.suggested_action}</span>
                  <div className="wiki-inline-actions">
                    {issue.auto_fixable ? (
                      <button className="secondary-button" disabled={props.isBusy || !props.workspace} onClick={() => void props.autoFixWiki()}>
                        自动补缺
                      </button>
                    ) : null}
                    {issueAction ? (
                      <button className="secondary-button" disabled={props.isBusy} onClick={issueAction.run}>
                        {issueAction.label}
                      </button>
                    ) : null}
                    {issue.item_id ? (
                      <button className="secondary-button" disabled={props.isBusy} onClick={() => void props.openWikiPageById(issue.item_id)}>
                        打开相关页面
                      </button>
                    ) : null}
                  </div>
                </div>
              );
            })}
            <p className="section-label">Wiki Log</p>
            {props.wikiLog.slice(0, 4).map((entry) => (
              <div className="link-card" key={entry.id}>
                <strong>{entry.event_type}</strong>
                <span>{entry.message}</span>
                <span>{props.formatDateTime(entry.created_at)}</span>
                <div className="wiki-inline-actions">
                  {entry.item_id ? (
                    <button className="secondary-button" disabled={props.isBusy} onClick={() => void props.openWikiPageById(entry.item_id!)}>
                      打开相关页面
                    </button>
                  ) : (
                    <button className="secondary-button" disabled={props.isBusy || !props.workspace} onClick={() => void props.openWikiIndex()}>
                      返回工作台总览
                    </button>
                  )}
                </div>
              </div>
            ))}
          </details>
        </aside>
      </section>
  );
}
