import { createElement, type ReactNode } from "react";
import { type WikiGraphMode, type WikiPage, type WikiTaskSummary } from "./model";

type WikiOverviewCardActions = {
  uiBusy: boolean;
  formatDateTime: (value: string) => string;
  openWikiPageById: (itemId: string) => Promise<void>;
  selectWikiPage: (page: WikiPage, nextGraphMode?: WikiGraphMode) => Promise<void>;
  openWikiGraphPage: (itemId: string) => Promise<void>;
};

export function renderWikiTaskCard(
  task: WikiTaskSummary,
  key: string,
  actions: WikiOverviewCardActions
): ReactNode {
  return createElement(
    "div",
    { className: "link-card", key },
    createElement("strong", null, `${task.task_type} · ${task.task_status}`),
    createElement("span", null, `${task.progress_phase} · ${task.progress_message}`),
    createElement(
      "span",
      null,
      `${task.target_type || "WORKSPACE"} · ${task.target_title || task.target_id || "当前工作台"} · ${actions.formatDateTime(task.updated_at)}`
    ),
    task.related_pages.length
      ? createElement(
        "div",
        { className: "wiki-inline-actions" },
        ...task.related_pages.map((page) => createElement(
          "button",
          {
            key: `${task.task_id}-${page.item_id}`,
            className: "secondary-button",
            disabled: actions.uiBusy,
            onClick: () => void actions.openWikiPageById(page.item_id)
          },
          `打开 ${page.title}`
        ))
      )
      : null
  );
}

export function renderWikiRecentUpdateCard(
  page: WikiPage,
  key: string,
  actions: WikiOverviewCardActions
): ReactNode {
  return createElement(
    "div",
    { className: "link-card", key },
    createElement("strong", null, page.title),
    createElement(
      "span",
      null,
      `${page.page_kind || "TOPIC"} · v${page.latest_version_no} · 出链 ${page.outgoing_count} · 反链 ${page.backlink_count} · 引用 ${page.citation_count}${page.unresolved_count > 0 ? ` · 断链 ${page.unresolved_count}` : ""}`
    ),
    createElement("span", null, actions.formatDateTime(page.updated_at)),
    createElement(
      "div",
      { className: "wiki-inline-actions" },
      createElement(
        "button",
        {
          className: "secondary-button",
          disabled: actions.uiBusy,
          onClick: () => void actions.selectWikiPage(page)
        },
        "打开页面"
      ),
      createElement(
        "button",
        {
          className: "secondary-button",
          disabled: actions.uiBusy,
          onClick: () => void actions.openWikiGraphPage(page.item_id)
        },
        "查看子图"
      )
    )
  );
}
