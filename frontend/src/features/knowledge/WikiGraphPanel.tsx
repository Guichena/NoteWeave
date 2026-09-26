import { formatWikiKind } from "./wikiUtils";
import { useEffect, useMemo, useState } from "react";
import {
  ExternalLink,
  Focus,
  LocateFixed,
  Network,
  Search,
  SlidersHorizontal,
  ZoomIn,
  ZoomOut
} from "lucide-react";
import type { WikiGraph } from "./model";
import type { WikiRelationsPanelProps } from "./wikiRelationsPanel.contract";

type WikiGraphPanelProps = Pick<
  WikiRelationsPanelProps,
  | "isBusy"
  | "workspace"
  | "selectedWikiItemId"
  | "wikiGraph"
  | "wikiGraphMode"
  | "switchWikiGraphMode"
  | "availableWikiKinds"
  | "wikiGraphKindFilters"
  | "resetWikiGraphKinds"
  | "toggleWikiGraphKind"
  | "graphFilterLabel"
  | "wikiGraphSearch"
  | "setWikiGraphSearch"
  | "graphSearchHits"
  | "openWikiPageById"
  | "openWikiGraphPage"
  | "prepareWikiLinkRepair"
>;

type GraphNode = WikiGraph["nodes"][number] & {
  id: string;
  unresolved: boolean;
  x: number;
  y: number;
};

const GRAPH_WIDTH = 320;
const GRAPH_HEIGHT = 360;
const GRAPH_CENTER_X = GRAPH_WIDTH / 2;
const GRAPH_CENTER_Y = 164;
const MAX_OVERVIEW_EDGES = 68;
const MAX_EGO_EDGES = 96;

function unresolvedNodeId(title: string) {
  return `unresolved:${title}`;
}

function shortLabel(title: string) {
  return title.length > 11 ? `${title.slice(0, 10)}...` : title;
}

function buildGraphNodes(graph: WikiGraph | null, selectedWikiItemId: string): { nodes: GraphNode[]; centerId: string } {
  if (!graph) return { nodes: [], centerId: "" };

  const requestedCenter = graph.meta.center_item_id || selectedWikiItemId;
  const centerId = graph.nodes.some((node) => node.item_id === requestedCenter)
    ? requestedCenter
    : [...graph.nodes].sort((left, right) => right.degree - left.degree)[0]?.item_id ?? "";

  const unresolved = new Map<string, string>();
  graph.edges.forEach((edge) => {
    if (!edge.target_item_id) unresolved.set(unresolvedNodeId(edge.target_title), edge.target_title);
  });

  const connectedToCenter = new Set<string>();
  graph.edges.forEach((edge) => {
    if (edge.source_item_id === centerId) connectedToCenter.add(edge.target_item_id ?? unresolvedNodeId(edge.target_title));
    if (edge.target_item_id === centerId) connectedToCenter.add(edge.source_item_id);
  });

  const resolvedNodes = graph.nodes
    .filter((node) => node.item_id !== centerId)
    .sort((left, right) => {
      const connectedDelta = Number(connectedToCenter.has(right.item_id)) - Number(connectedToCenter.has(left.item_id));
      return connectedDelta || right.degree - left.degree || left.title.localeCompare(right.title, "zh-CN");
    })
    .map((node) => ({ ...node, id: node.item_id, unresolved: false }));

  const unresolvedNodes = Array.from(unresolved, ([id, title]) => ({
    id,
    item_id: "",
    title,
    page_kind: "待补页面",
    version_no: 0,
    degree: 0,
    outgoing_count: 0,
    backlink_count: 0,
    citation_count: 0,
    unresolved_count: 1,
    unresolved: true
  }));

  const surrounding = [...resolvedNodes, ...unresolvedNodes].slice(0, 26);
  const positioned = surrounding.map((node, index) => {
    const ring = index < 7 ? 0 : index < 17 ? 1 : 2;
    const ringStart = ring === 0 ? 0 : ring === 1 ? 7 : 17;
    const ringLength = Math.min(ring === 0 ? 7 : ring === 1 ? 10 : 9, surrounding.length - ringStart);
    const ringIndex = index - ringStart;
    const radiusX = [72, 111, 139][ring];
    const radiusY = [70, 112, 148][ring];
    const angle = -Math.PI / 2 + (Math.PI * 2 * ringIndex) / Math.max(ringLength, 1) + ring * 0.22;
    return {
      ...node,
      x: GRAPH_CENTER_X + Math.cos(angle) * radiusX,
      y: GRAPH_CENTER_Y + Math.sin(angle) * radiusY
    };
  });

  const center = graph.nodes.find((node) => node.item_id === centerId);
  return {
    centerId,
    nodes: center
      ? [{ ...center, id: center.item_id, unresolved: false, x: GRAPH_CENTER_X, y: GRAPH_CENTER_Y }, ...positioned]
      : positioned
  };
}

export function WikiGraphPanel(props: WikiGraphPanelProps) {
  const { nodes, centerId } = useMemo(
    () => buildGraphNodes(props.wikiGraph, props.selectedWikiItemId),
    [props.wikiGraph, props.selectedWikiItemId]
  );
  const [activeNodeId, setActiveNodeId] = useState("");
  const [zoom, setZoom] = useState(1);

  useEffect(() => {
    setActiveNodeId(centerId);
    setZoom(1);
  }, [centerId, props.wikiGraphMode]);

  const activeNode = nodes.find((node) => node.id === activeNodeId) ?? nodes[0];
  const focusNodeId = activeNode?.id || centerId;
  const positions = new Map(nodes.map((node) => [node.id, node]));
  const visibleNodeIds = new Set(nodes.map((node) => node.id));
  const topLabelIds = new Set(
    [...nodes]
      .filter((node) => !node.unresolved)
      .sort((left, right) => right.degree - left.degree)
      .slice(0, 4)
      .map((node) => node.id)
  );
  const visibleEdges = (props.wikiGraph?.edges ?? []).filter((edge) => {
    const targetId = edge.target_item_id ?? unresolvedNodeId(edge.target_title);
    return visibleNodeIds.has(edge.source_item_id) && visibleNodeIds.has(targetId);
  });
  const edgeLimit = props.wikiGraphMode === "ego" ? MAX_EGO_EDGES : MAX_OVERVIEW_EDGES;
  const edges = [...visibleEdges]
    .sort((left, right) => {
      const leftTarget = left.target_item_id ?? unresolvedNodeId(left.target_title);
      const rightTarget = right.target_item_id ?? unresolvedNodeId(right.target_title);
      const leftTouchesFocus = left.source_item_id === focusNodeId || leftTarget === focusNodeId;
      const rightTouchesFocus = right.source_item_id === focusNodeId || rightTarget === focusNodeId;
      return Number(rightTouchesFocus) - Number(leftTouchesFocus)
        || right.mention_count - left.mention_count
        || Number(Boolean(right.target_item_id)) - Number(Boolean(left.target_item_id))
        || left.source_title.localeCompare(right.source_title, "zh-CN")
        || left.target_title.localeCompare(right.target_title, "zh-CN");
    })
    .slice(0, edgeLimit);
  const connectedToFocus = new Set<string>();
  edges.forEach((edge) => {
    const targetId = edge.target_item_id ?? unresolvedNodeId(edge.target_title);
    if (edge.source_item_id === focusNodeId) connectedToFocus.add(targetId);
    if (targetId === focusNodeId) connectedToFocus.add(edge.source_item_id);
  });
  const hiddenEdgeCount = Math.max(0, visibleEdges.length - edges.length);
  const isEmpty = nodes.length === 0;

  function activateNode(node: GraphNode) {
    setActiveNodeId(node.id);
  }

  function openNode(node: GraphNode) {
    if (node.item_id) void props.openWikiPageById(node.item_id);
  }

  return (
    <section className="wiki-graph-shell" aria-labelledby="wiki-graph-title">
      <header className="wiki-graph-header">
        <div>
          <span className="wiki-graph-kicker">知识图谱</span>
          <strong id="wiki-graph-title">知识关系图</strong>
        </div>
        <span className="wiki-graph-count" aria-label="图谱规模">
          {props.wikiGraph?.nodes.length ?? 0} 点 · {props.wikiGraph?.edges.length ?? 0} 边
        </span>
      </header>

      <div className="wiki-view-toggle" role="group" aria-label="图谱视角">
        <button
          type="button"
          className={props.wikiGraphMode === "overview" ? "wiki-view-option active" : "wiki-view-option"}
          aria-pressed={props.wikiGraphMode === "overview"}
          onClick={() => void props.switchWikiGraphMode("overview")}
          disabled={props.isBusy || !props.workspace}
        >
          <Network size={14} aria-hidden="true" />总览图
        </button>
        <button
          type="button"
          className={props.wikiGraphMode === "ego" ? "wiki-view-option active" : "wiki-view-option"}
          aria-pressed={props.wikiGraphMode === "ego"}
          onClick={() => void props.switchWikiGraphMode("ego")}
          disabled={props.isBusy || !props.workspace || !props.selectedWikiItemId}
        >
          <Focus size={14} aria-hidden="true" />当前页面
        </button>
      </div>

      <div className="wiki-graph-stage">
        {isEmpty ? (
          <div className="wiki-graph-empty">
            <Network size={28} aria-hidden="true" />
            <strong>还没有可视化关系</strong>
            <span>页面建立 Wiki 链接后，关系会在这里形成网络。</span>
          </div>
        ) : (
          <>
            {hiddenEdgeCount > 0 ? (
              <span className="wiki-graph-density-note">
                突出显示 {edges.length} / {visibleEdges.length} 条关系
              </span>
            ) : null}
            <div className="wiki-graph-tools" role="group" aria-label="图谱缩放">
              <button
                type="button"
                className="icon-button"
                aria-label="缩小图谱"
                title="缩小图谱"
                onClick={() => setZoom((value) => Math.max(0.78, Number((value - 0.12).toFixed(2))))}
                disabled={zoom <= 0.78}
              >
                <ZoomOut size={14} aria-hidden="true" />
              </button>
              <button type="button" className="icon-button" aria-label="重置图谱缩放" title="重置图谱缩放" onClick={() => setZoom(1)}>
                <LocateFixed size={14} aria-hidden="true" />
              </button>
              <button
                type="button"
                className="icon-button"
                aria-label="放大图谱"
                title="放大图谱"
                onClick={() => setZoom((value) => Math.min(1.42, Number((value + 0.12).toFixed(2))))}
                disabled={zoom >= 1.42}
              >
                <ZoomIn size={14} aria-hidden="true" />
              </button>
            </div>
            <svg
              className="wiki-graph-canvas"
              viewBox={`0 0 ${GRAPH_WIDTH} ${GRAPH_HEIGHT}`}
              role="img"
              aria-label={`${props.wikiGraph?.meta.mode === "ego" ? "当前页面" : "工作台"}知识关系图`}
            >
              <defs>
                <marker id="wiki-graph-arrow-out" markerWidth="6" markerHeight="6" refX="5" refY="3" orient="auto">
                  <path d="M0,0 L6,3 L0,6 Z" className="wiki-graph-arrow wiki-graph-arrow-out" />
                </marker>
                <marker id="wiki-graph-arrow-in" markerWidth="6" markerHeight="6" refX="5" refY="3" orient="auto">
                  <path d="M0,0 L6,3 L0,6 Z" className="wiki-graph-arrow wiki-graph-arrow-in" />
                </marker>
                <marker id="wiki-graph-arrow-neutral" markerWidth="6" markerHeight="6" refX="5" refY="3" orient="auto">
                  <path d="M0,0 L6,3 L0,6 Z" className="wiki-graph-arrow wiki-graph-arrow-neutral" />
                </marker>
              </defs>
              <g transform={`translate(${GRAPH_CENTER_X} ${GRAPH_CENTER_Y}) scale(${zoom}) translate(${-GRAPH_CENTER_X} ${-GRAPH_CENTER_Y})`}>
                <g className="wiki-graph-edges" aria-hidden="true">
                  {edges.map((edge, index) => {
                    const source = positions.get(edge.source_item_id);
                    const targetId = edge.target_item_id ?? unresolvedNodeId(edge.target_title);
                    const target = positions.get(targetId);
                    if (!source || !target) return null;
                    const touchesFocus = edge.source_item_id === focusNodeId || targetId === focusNodeId;
                    const direction = edge.source_item_id === focusNodeId
                      ? "out"
                      : targetId === focusNodeId ? "in" : "neutral";
                    return (
                      <line
                        key={`${edge.source_item_id}-${targetId}-${index}`}
                        x1={source.x}
                        y1={source.y}
                        x2={target.x}
                        y2={target.y}
                        className={`wiki-graph-edge is-${direction}${touchesFocus ? " is-focus" : " is-muted"}${edge.target_item_id ? "" : " is-unresolved"}`}
                        style={{ strokeWidth: Math.min(3.2, 0.8 + edge.mention_count * 0.42) }}
                        markerEnd={touchesFocus ? `url(#wiki-graph-arrow-${direction})` : undefined}
                      />
                    );
                  })}
                </g>
                <g className="wiki-graph-nodes">
                  {nodes.map((node) => {
                    const isCenter = node.id === centerId;
                    const isActive = node.id === activeNode?.id;
                    const isMuted = Boolean(focusNodeId)
                      && !isActive
                      && !isCenter
                      && !connectedToFocus.has(node.id);
                    const showLabel = isCenter || isActive || node.unresolved || topLabelIds.has(node.id);
                    const radius = node.unresolved ? 6 : Math.min(12, 5.5 + Math.sqrt(Math.max(node.degree, 1)) * 1.45) + (isCenter ? 2 : 0);
                    return (
                      <g
                        key={node.id}
                        className={`wiki-graph-node${isCenter ? " is-center" : ""}${isActive ? " is-active" : ""}${isMuted ? " is-muted" : ""}${node.unresolved ? " is-unresolved" : ""}`}
                        transform={`translate(${node.x} ${node.y})`}
                        role="button"
                        tabIndex={0}
                        aria-label={`${node.title}，${node.page_kind || "页面"}，${node.degree} 条关系`}
                        onClick={() => activateNode(node)}
                        onDoubleClick={() => openNode(node)}
                        onKeyDown={(event) => {
                          if (event.key === "Enter" || event.key === " ") {
                            event.preventDefault();
                            activateNode(node);
                          }
                        }}
                      >
                        <circle className="wiki-graph-node-hit" r={Math.max(18, radius + 8)} />
                        {isActive ? <circle className="wiki-graph-node-halo" r={radius + 6} /> : null}
                        <circle className="wiki-graph-node-dot" r={radius} />
                        {showLabel ? (
                          <text className="wiki-graph-node-label" y={radius + 14} textAnchor="middle">
                            {shortLabel(node.title)}
                          </text>
                        ) : null}
                      </g>
                    );
                  })}
                </g>
              </g>
            </svg>
            <div className="wiki-graph-legend" aria-label="关系图例">
              <span className="is-current">当前</span>
              <span className="is-outgoing">出链</span>
              <span className="is-incoming">反链</span>
              <span className="is-unresolved">待补</span>
            </div>
          </>
        )}
      </div>

      {activeNode ? (
        <div className="wiki-graph-selection" aria-live="polite">
          <div>
            <span>{formatWikiKind(activeNode.page_kind)}</span>
            <strong>{activeNode.title}</strong>
            <small>
              {activeNode.unresolved
                ? "链接目标尚未创建"
                : `${activeNode.degree} 条关系 · ${activeNode.backlink_count} 条反链 · ${activeNode.citation_count} 条引用`}
            </small>
          </div>
          <div className="wiki-graph-selection-actions">
            {activeNode.unresolved ? (
              <button
                type="button"
                className="secondary-button"
                disabled={props.isBusy}
                onClick={() => props.prepareWikiLinkRepair(activeNode.title, "关系图")}
              >
                补全页面
              </button>
            ) : (
              <>
                <button
                  type="button"
                  className="secondary-button"
                  disabled={props.isBusy}
                  onClick={() => void props.openWikiPageById(activeNode.item_id)}
                >
                  <ExternalLink size={13} aria-hidden="true" />打开
                </button>
                <button
                  type="button"
                  className="secondary-button"
                  disabled={props.isBusy}
                  onClick={() => void props.openWikiGraphPage(activeNode.item_id)}
                >
                  <Focus size={13} aria-hidden="true" />聚焦
                </button>
              </>
            )}
          </div>
        </div>
      ) : null}

      <details className="wiki-graph-filters">
        <summary>
          <span><SlidersHorizontal size={14} aria-hidden="true" />筛选与查找</span>
          <small>{props.graphFilterLabel}</small>
        </summary>
        <div className="wiki-graph-filter-body">
          <div className="wiki-inline-pills">
            <button
              type="button"
              className={props.wikiGraphKindFilters.length === 0 ? "active filter-pill" : "filter-pill"}
              disabled={props.isBusy || !props.workspace}
              onClick={() => void props.resetWikiGraphKinds()}
            >
              全部
            </button>
            {props.availableWikiKinds.map((kind) => (
              <button
                type="button"
                key={`graph-kind-${kind}`}
                className={props.wikiGraphKindFilters.includes(kind) ? "active filter-pill" : "filter-pill"}
                disabled={props.isBusy || !props.workspace}
                onClick={() => void props.toggleWikiGraphKind(kind)}
              >
                {formatWikiKind(kind)}
              </button>
            ))}
          </div>
          <label className="wiki-graph-search-field">
            <Search size={14} aria-hidden="true" />
            <input
              value={props.wikiGraphSearch}
              onChange={(event) => props.setWikiGraphSearch(event.target.value)}
              placeholder="查找图谱页面"
              aria-label="查找图谱页面"
            />
          </label>
          {props.graphSearchHits.length ? (
            <div className="wiki-search-hit-list">
              {props.graphSearchHits.map((page) => (
                <button
                  type="button"
                  className="wiki-graph-search-hit"
                  key={`graph-search-${page.item_id}`}
                  onClick={() => void props.openWikiGraphPage(page.item_id)}
                >
                  <span><strong>{page.title}</strong><small>{formatWikiKind(page.page_kind)} · v{page.latest_version_no}</small></span>
                  <Focus size={14} aria-hidden="true" />
                </button>
              ))}
            </div>
          ) : props.wikiGraphSearch.trim() ? <span className="wiki-graph-no-results">没有匹配的图谱页面。</span> : null}
        </div>
      </details>
    </section>
  );
}
