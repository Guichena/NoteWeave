// @vitest-environment jsdom

import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { WikiGraphPanel } from "./WikiGraphPanel";

afterEach(cleanup);

describe("WikiGraphPanel", () => {
  const baseProps = {
    isBusy: false,
    workspace: { workspace_id: "workspace" },
    selectedWikiItemId: "",
    wikiGraph: null,
    wikiGraphMode: "overview",
    switchWikiGraphMode: vi.fn(),
    availableWikiKinds: [],
    wikiGraphKindFilters: [],
    resetWikiGraphKinds: vi.fn(),
    toggleWikiGraphKind: vi.fn(),
    graphFilterLabel: "全部页面类型",
    wikiGraphSearch: "",
    setWikiGraphSearch: vi.fn(),
    graphSearchHits: [],
    openWikiPageById: vi.fn(),
    openWikiGraphPage: vi.fn(),
    prepareWikiLinkRepair: vi.fn(),
    formatWikiRelationType: vi.fn()
  };

  it("uses an accessible domain-aware view toggle", () => {
    const switchWikiGraphMode = vi.fn();
    render(<WikiGraphPanel {...({
      ...baseProps,
      selectedWikiItemId: "",
      switchWikiGraphMode
    } as any)} />);

    const overview = screen.getByRole("button", { name: "总览图" });
    const currentPage = screen.getByRole("button", { name: "当前页面" });
    expect(overview.getAttribute("aria-pressed")).toBe("true");
    expect(overview.classList.contains("wiki-view-option")).toBe(true);
    expect(currentPage.hasAttribute("disabled")).toBe(true);

    fireEvent.click(overview);
    expect(switchWikiGraphMode).toHaveBeenCalledWith("overview");
  });

  it("renders a directional graph and exposes node actions", () => {
    const openWikiPageById = vi.fn();
    const prepareWikiLinkRepair = vi.fn();
    render(<WikiGraphPanel {...({
      ...baseProps,
      selectedWikiItemId: "center",
      wikiGraphMode: "ego",
      openWikiPageById,
      prepareWikiLinkRepair,
      wikiGraph: {
        nodes: [
          {
            item_id: "center",
            title: "知识图谱",
            page_kind: "CONCEPT",
            version_no: 2,
            degree: 5,
            outgoing_count: 2,
            backlink_count: 1,
            citation_count: 3,
            unresolved_count: 1
          },
          {
            item_id: "source",
            title: "研究方法",
            page_kind: "TOPIC",
            version_no: 1,
            degree: 2,
            outgoing_count: 1,
            backlink_count: 1,
            citation_count: 0,
            unresolved_count: 0
          }
        ],
        edges: [
          {
            source_item_id: "source",
            source_title: "研究方法",
            target_item_id: "center",
            target_title: "知识图谱",
            relation_type: "WIKI_LINK",
            relation_status: "RESOLVED",
            mention_count: 2
          },
          {
            source_item_id: "center",
            source_title: "知识图谱",
            target_item_id: null,
            target_title: "待补概念",
            relation_type: "WIKI_LINK",
            relation_status: "UNRESOLVED",
            mention_count: 1
          }
        ],
        meta: {
          mode: "ego",
          center_item_id: "center",
          depth: 1,
          total_nodes: 2,
          returned_nodes: 2,
          truncated: false
        }
      }
    } as any)} />);

    expect(screen.getByRole("img", { name: "当前页面知识关系图" })).toBeTruthy();
    expect(screen.getByLabelText("关系图例")).toBeTruthy();
    expect(document.querySelector(".wiki-graph-edge.is-in")).toBeTruthy();
    expect(document.querySelector(".wiki-graph-edge.is-unresolved")).toBeTruthy();

    const sourceNode = screen.getByRole("button", { name: /研究方法/ });
    fireEvent.click(sourceNode);
    expect(screen.getByText("2 条关系 · 1 条反链 · 0 条引用")).toBeTruthy();
    fireEvent.doubleClick(sourceNode);
    expect(openWikiPageById).toHaveBeenCalledWith("source");

    fireEvent.click(screen.getByRole("button", { name: /待补概念/ }));
    fireEvent.click(screen.getByRole("button", { name: "补全页面" }));
    expect(prepareWikiLinkRepair).toHaveBeenCalledWith("待补概念", "关系图");
  });

  it("shows a useful empty graph state", () => {
    render(<WikiGraphPanel {...(baseProps as any)} />);
    expect(screen.getByText("还没有可视化关系")).toBeTruthy();
    expect(screen.queryByRole("img", { name: /知识关系图/ })).toBeNull();
  });

  it("declutters dense overview graphs while preserving the total relationship count", () => {
    const edges = Array.from({ length: 90 }, (_, index) => ({
      source_item_id: index % 2 === 0 ? "center" : "related",
      source_title: index % 2 === 0 ? "中心页面" : "关联页面",
      target_item_id: index % 2 === 0 ? "related" : "center",
      target_title: index % 2 === 0 ? "关联页面" : "中心页面",
      relation_type: "WIKI_LINK",
      relation_status: "RESOLVED",
      mention_count: 1
    }));
    render(<WikiGraphPanel {...({
      ...baseProps,
      wikiGraph: {
        nodes: [
          {
            item_id: "center", title: "中心页面", page_kind: "TOPIC", version_no: 1,
            degree: 90, outgoing_count: 45, backlink_count: 45, citation_count: 1, unresolved_count: 0
          },
          {
            item_id: "related", title: "关联页面", page_kind: "CONCEPT", version_no: 1,
            degree: 90, outgoing_count: 45, backlink_count: 45, citation_count: 1, unresolved_count: 0
          }
        ],
        edges,
        meta: {
          mode: "overview", center_item_id: "center", depth: 1,
          total_nodes: 2, returned_nodes: 2, truncated: false
        }
      }
    } as any)} />);

    expect(document.querySelectorAll(".wiki-graph-edge")).toHaveLength(68);
    expect(screen.getByText("突出显示 68 / 90 条关系")).toBeTruthy();
    expect(screen.getByLabelText("图谱规模").textContent).toContain("90 边");
  });
});
