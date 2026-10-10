import { useEffect, useMemo, useRef, useState, type PointerEvent as ReactPointerEvent } from "react";
import {
  ExternalLink,
  Focus,
  LocateFixed,
  Network,
  Plus,
  Search,
  SlidersHorizontal,
  ZoomIn,
  ZoomOut
} from "lucide-react";
import type { WikiGraph } from "./model";
import type { WikiRelationsPanelProps } from "./wikiRelationsPanel.contract";
import { formatWikiKind, wikiKindTone } from "./wikiUtils";

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
> & {
  /** wide：总览里的大图；compact：页面右侧关系栏里的小图。 */
  variant?: "wide" | "compact";
};

type GraphNode = WikiGraph["nodes"][number] & {
  id: string;
  unresolved: boolean;
  x: number;
  y: number;
  r: number;
};

type Size = { width: number; height: number };

const SIZES: Record<"wide" | "compact", Size> = {
  wide: { width: 760, height: 380 },
  compact: { width: 300, height: 300 }
};
const MAX_NODES = 28;
const MAX_OVERVIEW_EDGES = 68;
const MAX_EGO_EDGES = 96;
const MIN_ZOOM = 0.6;
const MAX_ZOOM = 2.2;

function unresolvedNodeId(title: string) {
  return `unresolved:${title}`;
}

function shortLabel(title: string, max: number) {
  return title.length > max ? `${title.slice(0, max - 1)}…` : title;
}

function nodeRadius(node: { degree: number; unresolved: boolean }, isCenter: boolean) {
  if (node.unresolved) return 6;
  return Math.min(15, 6.5 + Math.sqrt(Math.max(node.degree, 1)) * 1.6) + (isCenter ? 2.5 : 0);
}

/**
 * 力导向布局（确定性，不依赖随机数）：节点互斥、边相吸、整体向中心收拢，
 * 中心页面固定在画布中央。节点不超过 28 个，300 次迭代开销可以忽略。
 */
/** 估算标签宽度：中日韩字符按整字宽，其余按约 0.58 字宽。 */
function labelWidth(text: string, fontSize: number) {
  let width = 0;
  for (const char of text) width += /[\u2E80-\uFFFF]/.test(char) ? fontSize : fontSize * 0.58;
  return width + 8;
}

function layoutGraph(graph: WikiGraph | null, selectedWikiItemId: string, size: Size) {
  if (!graph) return { nodes: [] as GraphNode[], centerId: "", labelled: new Set<string>() };

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

  const center = graph.nodes.find((node) => node.item_id === centerId);
  const others = graph.nodes
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

  const base = [
    ...(center ? [{ ...center, id: center.item_id, unresolved: false }] : []),
    ...[...others, ...unresolvedNodes].slice(0, MAX_NODES - (center ? 1 : 0))
  ];
  const count = base.length;
  if (count === 0) return { nodes: [] as GraphNode[], centerId, labelled: new Set<string>() };

  const index = new Map(base.map((node, i) => [node.id, i]));
  const links: Array<[number, number]> = [];
  graph.edges.forEach((edge) => {
    const a = index.get(edge.source_item_id);
    const b = index.get(edge.target_item_id ?? unresolvedNodeId(edge.target_title));
    if (a !== undefined && b !== undefined && a !== b) links.push([a, b]);
  });

  // 初始位置：黄金角螺旋，保证每次结果一致
  const px = base.map((_, i) => (i === 0 && center ? 0 : Math.cos(i * 2.39996) * (30 + i * 9)));
  const py = base.map((_, i) => (i === 0 && center ? 0 : Math.sin(i * 2.39996) * (30 + i * 9)));
  const k = Math.sqrt((size.width * size.height) / Math.max(count, 1)) * 0.7;
  const aspect = size.width / size.height;
  let temperature = size.width / 8;

  for (let step = 0; step < 300; step += 1) {
    const dx = new Array<number>(count).fill(0);
    const dy = new Array<number>(count).fill(0);
    for (let i = 0; i < count; i += 1) {
      for (let j = i + 1; j < count; j += 1) {
        let vx = px[i] - px[j];
        let vy = py[i] - py[j];
        let dist = Math.hypot(vx, vy);
        if (dist < 0.01) {
          vx = 0.01 * (i - j);
          vy = 0.01;
          dist = Math.hypot(vx, vy);
        }
        const force = (k * k) / dist;
        dx[i] += (vx / dist) * force;
        dy[i] += (vy / dist) * force;
        dx[j] -= (vx / dist) * force;
        dy[j] -= (vy / dist) * force;
      }
    }
    links.forEach(([a, b]) => {
      const vx = px[a] - px[b];
      const vy = py[a] - py[b];
      const dist = Math.max(Math.hypot(vx, vy), 0.01);
      const force = (dist * dist) / k;
      dx[a] -= (vx / dist) * force;
      dy[a] -= (vy / dist) * force;
      dx[b] += (vx / dist) * force;
      dy[b] += (vy / dist) * force;
    });
    for (let i = 0; i < count; i += 1) {
      // 向中心的弱引力，避免孤立节点飘到画布外
      // 椭圆形引力：纵向收得更紧，让图谱铺满宽画布
      dx[i] -= (px[i] * 0.012 * k) / aspect;
      dy[i] -= py[i] * 0.012 * k * aspect * 0.8;
      if (i === 0 && center) continue;
      const disp = Math.max(Math.hypot(dx[i], dy[i]), 0.01);
      const limited = Math.min(disp, temperature);
      px[i] += (dx[i] / disp) * limited;
      py[i] += (dy[i] / disp) * limited;
    }
    temperature = Math.max(temperature * 0.985, 0.5);
  }

  // 缩放进画布，四周留出标签空间
  const padX = 60;
  const padY = 42;
  const minX = Math.min(...px);
  const maxX = Math.max(...px);
  const minY = Math.min(...py);
  const maxY = Math.max(...py);
  const spanX = Math.max(maxX - minX, 1);
  const spanY = Math.max(maxY - minY, 1);
  let scaleX = Math.min((size.width - padX * 2) / spanX, 3);
  let scaleY = Math.min((size.height - padY * 2) / spanY, 3);
  // 允许适度的非等比拉伸以铺满画布，但不让形状变形得太厉害
  if (scaleX > scaleY * 1.6) scaleX = scaleY * 1.6;
  if (scaleY > scaleX * 1.6) scaleY = scaleX * 1.6;
  const offsetX = (size.width - spanX * scaleX) / 2;
  const offsetY = (size.height - spanY * scaleY) / 2;

  const compact = size.width < 480;
  const xs = base.map((_, i) => (count === 1 ? size.width / 2 : offsetX + (px[i] - minX) * scaleX));
  const ys = base.map((_, i) => (count === 1 ? size.height / 2 : offsetY + (py[i] - minY) * scaleY));

  // 屏幕空间的标签避让：按“圆点 + 下方标签”的包围盒把重叠的节点推开
  // 与渲染保持一致：节点少时全部显示标签；否则只给中心、直接邻居（宽图再加高连接度节点）留标签位
  const labelAll = count <= (compact ? 9 : 18);
  const topIds = new Set(
    compact ? [] : [...base].filter((node) => !node.unresolved).sort((l, r) => r.degree - l.degree).slice(0, 5).map((node) => node.id)
  );
  const labelled = new Set(
    base
      .filter((node) => labelAll || node.id === centerId || connectedToCenter.has(node.id) || topIds.has(node.id))
      .map((node) => node.id)
  );
  const boxW = base.map((node) => (labelled.has(node.id)
    ? Math.max(labelWidth(shortLabel(node.title, compact ? 9 : 14), compact ? 11 : 12), 28)
    : 26));
  const radii = base.map((node) => nodeRadius(node, node.id === centerId));
  // 标签画在圆点下方：包围盒 = 圆点 + 标签行，中心相应下移
  const boxH = base.map((node, i) => (labelled.has(node.id) ? radii[i] * 2 + (compact ? 24 : 28) : radii[i] * 2 + 10));
  const boxOffset = base.map((node, i) => (labelled.has(node.id) ? (compact ? 10 : 12) : 0));
  const pinned = center ? 0 : -1;
  for (let pass = 0; pass < 240; pass += 1) {
    let moved = false;
    for (let i = 0; i < count; i += 1) {
      for (let j = i + 1; j < count; j += 1) {
        const overlapX = (boxW[i] + boxW[j]) / 2 + 6 - Math.abs(xs[i] - xs[j]);
        const overlapY = (boxH[i] + boxH[j]) / 2 - Math.abs(ys[i] + boxOffset[i] - ys[j] - boxOffset[j]);
        if (overlapX <= 0 || overlapY <= 0) continue;
        moved = true;
        // 沿重叠较小的方向推开，固定的中心节点不动
        const share = i === pinned ? [0, 1] : j === pinned ? [1, 0] : [0.5, 0.5];
        // 沿两盒中心连线方向推开（横向按盒宽归一），比单轴推移更容易散开
        let vx = (xs[j] - xs[i]) / Math.max(boxW[i] + boxW[j], 1);
        let vy = (ys[j] + boxOffset[j] - ys[i] - boxOffset[i]) / Math.max(boxH[i] + boxH[j], 1);
        if (Math.abs(vx) + Math.abs(vy) < 1e-6) {
          vx = 0.1 * (j % 2 ? 1 : -1);
          vy = 0.1;
        }
        const len = Math.hypot(vx, vy);
        const push = Math.min(overlapX, overlapY) * 0.6 + 0.5;
        const ux = (vx / len) * push;
        const uy = (vy / len) * push;
        xs[i] -= ux * share[0];
        ys[i] -= uy * share[0];
        xs[j] += ux * share[1];
        ys[j] += uy * share[1];
      }
    }
    for (let i = 0; i < count; i += 1) {
      xs[i] = Math.min(Math.max(xs[i], boxW[i] / 2 + 6), size.width - boxW[i] / 2 - 6);
      ys[i] = Math.min(Math.max(ys[i], 18), size.height - 26);
    }
    if (!moved) break;
  }

  // 避让后整体居中，避免图形偏向一侧
  if (count > 1) {
    const shiftX = size.width / 2 - (Math.min(...xs) + Math.max(...xs)) / 2;
    const shiftY = (size.height - 8) / 2 - (Math.min(...ys) + Math.max(...ys)) / 2;
    for (let i = 0; i < count; i += 1) {
      xs[i] += shiftX;
      ys[i] += shiftY;
    }
  }

  return {
    centerId,
    labelled,
    nodes: base.map((node, i) => ({
      ...node,
      x: xs[i],
      y: ys[i],
      r: nodeRadius(node, node.id === centerId)
    }))
  };
}

/** 两节点之间的轻微弧线；端点收缩到圆外，让箭头不被节点盖住。 */
function edgePath(source: GraphNode, target: GraphNode, bend: number) {
  const vx = target.x - source.x;
  const vy = target.y - source.y;
  const dist = Math.max(Math.hypot(vx, vy), 0.01);
  const ux = vx / dist;
  const uy = vy / dist;
  const sx = source.x + ux * (source.r + 2);
  const sy = source.y + uy * (source.r + 2);
  const tx = target.x - ux * (target.r + 4);
  const ty = target.y - uy * (target.r + 4);
  const mx = (sx + tx) / 2 - uy * dist * bend;
  const my = (sy + ty) / 2 + ux * dist * bend;
  return `M${sx.toFixed(1)},${sy.toFixed(1)} Q${mx.toFixed(1)},${my.toFixed(1)} ${tx.toFixed(1)},${ty.toFixed(1)}`;
}

export function WikiGraphPanel(props: WikiGraphPanelProps) {
  const variant = props.variant ?? "compact";
  const size = SIZES[variant];
  const { nodes, centerId, labelled } = useMemo(
    () => layoutGraph(props.wikiGraph, props.selectedWikiItemId, size),
    [props.wikiGraph, props.selectedWikiItemId, size]
  );
  const [activeNodeId, setActiveNodeId] = useState("");
  const [hoverNodeId, setHoverNodeId] = useState("");
  const [zoom, setZoom] = useState(1);
  const [pan, setPan] = useState({ x: 0, y: 0 });
  const [dragging, setDragging] = useState(false);
  const dragRef = useRef<{ x: number; y: number; panX: number; panY: number } | null>(null);
  const svgRef = useRef<SVGSVGElement | null>(null);

  useEffect(() => {
    setActiveNodeId(centerId);
    setZoom(1);
    setPan({ x: 0, y: 0 });
  }, [centerId, props.wikiGraphMode]);

  const activeNode = nodes.find((node) => node.id === activeNodeId) ?? nodes[0];
  const focusNodeId = hoverNodeId || activeNode?.id || centerId;
  const positions = new Map(nodes.map((node) => [node.id, node]));
  const visibleNodeIds = new Set(nodes.map((node) => node.id));
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
  const kindsInGraph = Array.from(new Set(nodes.filter((node) => !node.unresolved).map((node) => formatWikiKind(node.page_kind))));
  const hasUnresolved = nodes.some((node) => node.unresolved);
  const pageCount = props.wikiGraph?.nodes.length ?? 0;
  const edgeCount = props.wikiGraph?.edges.length ?? 0;
  const cx = size.width / 2;
  const cy = size.height / 2;

  function openNode(node: GraphNode) {
    if (node.unresolved) {
      props.prepareWikiLinkRepair(node.title, "关系图");
      return;
    }
    if (node.item_id) void props.openWikiPageById(node.item_id);
  }

  function changeZoom(delta: number) {
    setZoom((value) => Math.min(MAX_ZOOM, Math.max(MIN_ZOOM, Number((value + delta).toFixed(2)))));
  }

  function resetView() {
    setZoom(1);
    setPan({ x: 0, y: 0 });
  }

  function handlePointerDown(event: ReactPointerEvent<SVGSVGElement>) {
    if ((event.target as Element).closest?.(".wiki-graph-node")) return;
    dragRef.current = { x: event.clientX, y: event.clientY, panX: pan.x, panY: pan.y };
    setDragging(true);
    event.currentTarget.setPointerCapture?.(event.pointerId);
  }

  function handlePointerMove(event: ReactPointerEvent<SVGSVGElement>) {
    const drag = dragRef.current;
    const svg = svgRef.current;
    if (!drag || !svg) return;
    const rect = svg.getBoundingClientRect();
    const ratio = rect.width > 0 ? size.width / rect.width : 1;
    setPan({
      x: drag.panX + ((event.clientX - drag.x) * ratio) / zoom,
      y: drag.panY + ((event.clientY - drag.y) * ratio) / zoom
    });
  }

  function endDrag(event?: ReactPointerEvent<SVGSVGElement>) {
    if (!dragRef.current) return;
    dragRef.current = null;
    setDragging(false);
    if (event) event.currentTarget.releasePointerCapture?.(event.pointerId);
  }

  return (
    <section className={`wiki-graph-shell is-${variant}`} aria-labelledby={`wiki-graph-title-${variant}`}>
      <header className="wiki-graph-header">
        <div className="wiki-graph-heading">
          <strong id={`wiki-graph-title-${variant}`}>知识关系图</strong>
          <span className="wiki-graph-count" aria-label="图谱规模">
            {pageCount} 页 · {edgeCount} 边
          </span>
        </div>
        <div className="wiki-view-toggle" role="group" aria-label="图谱视角">
          <button
            type="button"
            className={props.wikiGraphMode === "overview" ? "wiki-view-option active" : "wiki-view-option"}
            aria-pressed={props.wikiGraphMode === "overview"}
            onClick={() => void props.switchWikiGraphMode("overview")}
            disabled={props.isBusy || !props.workspace}
          >
            <Network size={13} aria-hidden="true" />总览图
          </button>
          <button
            type="button"
            className={props.wikiGraphMode === "ego" ? "wiki-view-option active" : "wiki-view-option"}
            aria-pressed={props.wikiGraphMode === "ego"}
            onClick={() => void props.switchWikiGraphMode("ego")}
            disabled={props.isBusy || !props.workspace || !props.selectedWikiItemId}
          >
            <Focus size={13} aria-hidden="true" />当前页面
          </button>
        </div>
      </header>

      <div className={`wiki-graph-stage${dragging ? " is-dragging" : ""}`}>
        {isEmpty ? (
          <div className="wiki-graph-empty">
            <span className="wiki-graph-empty-icon" aria-hidden="true"><Network size={22} /></span>
            <strong>还没有可视化关系</strong>
            <span>页面之间建立 [[链接]] 后，关系会在这里形成网络。</span>
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
                title="缩小"
                onClick={() => changeZoom(-0.2)}
                disabled={zoom <= MIN_ZOOM}
              >
                <ZoomOut size={14} aria-hidden="true" />
              </button>
              <button type="button" className="icon-button" aria-label="重置图谱缩放" title="重置视图" onClick={resetView}>
                <LocateFixed size={14} aria-hidden="true" />
              </button>
              <button
                type="button"
                className="icon-button"
                aria-label="放大图谱"
                title="放大"
                onClick={() => changeZoom(0.2)}
                disabled={zoom >= MAX_ZOOM}
              >
                <ZoomIn size={14} aria-hidden="true" />
              </button>
            </div>
            <svg
              ref={svgRef}
              className="wiki-graph-canvas"
              viewBox={`0 0 ${size.width} ${size.height}`}
              role="img"
              aria-label={`${props.wikiGraph?.meta.mode === "ego" ? "当前页面" : "工作台"}知识关系图`}
              onPointerDown={handlePointerDown}
              onPointerMove={handlePointerMove}
              onPointerUp={endDrag}
              onPointerLeave={() => endDrag()}
            >
              <defs>
                {(["out", "in", "neutral"] as const).map((direction) => (
                  <marker
                    key={direction}
                    id={`wiki-graph-arrow-${direction}-${variant}`}
                    viewBox="0 0 10 10"
                    markerWidth="7"
                    markerHeight="7"
                    refX="8"
                    refY="5"
                    orient="auto"
                  >
                    <path d="M1,1 L9,5 L1,9 Z" className={`wiki-graph-arrow wiki-graph-arrow-${direction}`} />
                  </marker>
                ))}
              </defs>
              <g
                className="wiki-graph-viewport"
                transform={`translate(${cx} ${cy}) scale(${zoom}) translate(${(-cx + pan.x).toFixed(1)} ${(-cy + pan.y).toFixed(1)})`}
              >
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
                      <path
                        key={`${edge.source_item_id}-${targetId}-${index}`}
                        d={edgePath(source, target, 0.12)}
                        className={`wiki-graph-edge is-${direction}${touchesFocus ? " is-focus" : " is-muted"}${edge.target_item_id ? "" : " is-unresolved"}`}
                        style={{ strokeWidth: Math.min(2.6, 1 + edge.mention_count * 0.3) }}
                        markerEnd={touchesFocus ? `url(#wiki-graph-arrow-${direction}-${variant})` : undefined}
                      />
                    );
                  })}
                </g>
                <g className="wiki-graph-nodes">
                  {nodes.map((node) => {
                    const isCenter = node.id === centerId;
                    const isActive = node.id === activeNode?.id;
                    const isHovered = node.id === hoverNodeId;
                    const isMuted = !isActive
                      && !isHovered
                      && node.id !== focusNodeId
                      && !connectedToFocus.has(node.id);
                    const showLabel = labelled.has(node.id) || isCenter || isActive || isHovered
                      || (focusNodeId !== centerId && connectedToFocus.has(node.id));
                    const tone = node.unresolved ? "pending" : wikiKindTone(node.page_kind);
                    return (
                      <g
                        key={node.id}
                        className={`wiki-graph-node tone-${tone}${isCenter ? " is-center" : ""}${isActive ? " is-active" : ""}${isMuted ? " is-muted" : ""}${node.unresolved ? " is-unresolved" : ""}`}
                        transform={`translate(${node.x.toFixed(1)} ${node.y.toFixed(1)})`}
                        role="button"
                        tabIndex={0}
                        aria-label={`${node.title}，${node.unresolved ? "待补页面" : formatWikiKind(node.page_kind)}，${node.degree} 条关系`}
                        onClick={() => setActiveNodeId(node.id)}
                        onDoubleClick={() => openNode(node)}
                        onPointerEnter={() => setHoverNodeId(node.id)}
                        onPointerLeave={() => setHoverNodeId("")}
                        onKeyDown={(event) => {
                          if (event.key === "Enter" || event.key === " ") {
                            event.preventDefault();
                            setActiveNodeId(node.id);
                          }
                        }}
                      >
                        <circle className="wiki-graph-node-hit" r={Math.max(16, node.r + 8)} />
                        {isActive ? <circle className="wiki-graph-node-halo" r={node.r + 6} /> : null}
                        <circle className="wiki-graph-node-dot" r={node.r} />
                        {isCenter && !node.unresolved ? <circle className="wiki-graph-node-core" r={Math.max(2.5, node.r * 0.32)} /> : null}
                        {showLabel ? (
                          <text className="wiki-graph-node-label" y={node.r + 14} textAnchor="middle">
                            {shortLabel(node.title, variant === "wide" ? 14 : 9)}
                          </text>
                        ) : null}
                      </g>
                    );
                  })}
                </g>
              </g>
            </svg>
          </>
        )}
      </div>

      {!isEmpty ? (
        <div className="wiki-graph-legend" aria-label="关系图例">
          {kindsInGraph.map((kind) => (
            <span key={kind} className={`tone-${wikiKindTone(kind)}`}>{kind}</span>
          ))}
          {hasUnresolved ? <span className="tone-pending">待补</span> : null}
        </div>
      ) : null}

      {activeNode ? (
        <div className={`wiki-graph-selection tone-${activeNode.unresolved ? "pending" : wikiKindTone(activeNode.page_kind)}`} aria-live="polite">
          <span className="wiki-graph-selection-dot" aria-hidden="true" />
          <div className="wiki-graph-selection-copy">
            <strong title={activeNode.title}>{activeNode.title}</strong>
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
                <Plus size={13} aria-hidden="true" />补全页面
              </button>
            ) : (
              <>
                <button
                  type="button"
                  className="icon-button"
                  aria-label="打开"
                  title="打开页面"
                  disabled={props.isBusy}
                  onClick={() => void props.openWikiPageById(activeNode.item_id)}
                >
                  <ExternalLink size={14} aria-hidden="true" />
                </button>
                <button
                  type="button"
                  className="icon-button"
                  aria-label="聚焦"
                  title="以此页面为中心"
                  disabled={props.isBusy}
                  onClick={() => void props.openWikiGraphPage(activeNode.item_id)}
                >
                  <Focus size={14} aria-hidden="true" />
                </button>
              </>
            )}
          </div>
        </div>
      ) : null}

      <details className="wiki-graph-filters">
        <summary>
          <span><SlidersHorizontal size={13} aria-hidden="true" />筛选与查找</span>
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
