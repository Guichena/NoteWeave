import { ArrowDownLeft, ArrowUpRight, Plus } from "lucide-react";
import type { WikiRelationsPanelProps } from "./wikiRelationsPanel.contract";
import { WikiGraphPanel } from "./WikiGraphPanel";

/** 页面右侧的关系栏：局部图谱 + 直接关系（出链 / 反链）。 */
export function WikiRelationsPanel(props: WikiRelationsPanelProps) {
  const outgoing = props.selectedWikiDetail?.outgoing_links ?? [];
  const backlinks = props.selectedWikiDetail?.backlinks ?? [];
  const pageTitleById = new Map((props.wikiHome?.pages ?? []).map((page) => [page.item_id, page.title]));

  return (
    <aside className="wiki-links" id="wiki-relations-panel" aria-label="Wiki 关系面板">
      <WikiGraphPanel {...props} />

      {props.selectedWikiPage ? (
        <section className="wiki-direct-relations" aria-labelledby="wiki-direct-relations-heading">
          <h3 id="wiki-direct-relations-heading">直接关系</h3>
          {outgoing.length === 0 && backlinks.length === 0 ? (
            <p className="phase-note">当前页面暂无直接关系。</p>
          ) : null}
          {outgoing.length > 0 ? (
            <ul className="wiki-relation-list" aria-label="出链">
              {outgoing.map((link, index) => (
                <li key={`${link.source_item_id}-${link.target_title}-${index}`}>
                  {link.target_item_id ? (
                    <button
                      type="button"
                      className="wiki-relation-row"
                      disabled={props.isBusy}
                      title={`${props.formatWikiRelationType(link.relation_type)} · ${link.mention_count} 次提及`}
                      onClick={() => void props.openWikiPageById(link.target_item_id!)}
                    >
                      <ArrowUpRight size={14} aria-hidden="true" />
                      <span>{link.target_title}</span>
                    </button>
                  ) : (
                    <button
                      type="button"
                      className="wiki-relation-row is-unresolved"
                      disabled={props.isBusy || !props.selectedWikiPage}
                      title="链接的页面尚不存在，点击预填补页草稿"
                      onClick={() => props.selectedWikiPage
                        ? props.prepareWikiLinkRepair(link.target_title, props.selectedWikiPage.title)
                        : undefined}
                    >
                      <Plus size={14} aria-hidden="true" />
                      <span>{link.target_title}</span>
                      <small>待补</small>
                    </button>
                  )}
                </li>
              ))}
            </ul>
          ) : null}
          {backlinks.length > 0 ? (
            <ul className="wiki-relation-list" aria-label="反链">
              {backlinks.map((link, index) => (
                <li key={`backlink-${link.source_item_id}-${link.target_title}-${index}`}>
                  <button
                    type="button"
                    className="wiki-relation-row is-backlink"
                    disabled={props.isBusy || !link.source_item_id}
                    title={`${props.formatWikiRelationType(link.relation_type)} · ${link.mention_count} 次提及`}
                    onClick={() => link.source_item_id ? void props.openWikiPageById(link.source_item_id) : undefined}
                  >
                    <ArrowDownLeft size={14} aria-hidden="true" />
                    <span>{pageTitleById.get(link.source_item_id) ?? link.target_title}</span>
                  </button>
                </li>
              ))}
            </ul>
          ) : null}
        </section>
      ) : null}
    </aside>
  );
}
