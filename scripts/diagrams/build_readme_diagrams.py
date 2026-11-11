"""生成 README 用的模块架构图，输出到 docs/images。

运行：python scripts/diagrams/build_readme_diagrams.py
图里的类名、阈值和主题名都取自当前源码；源码改动后同步修改这里再重新生成。
"""
from __future__ import annotations

import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from svgkit import Diagram, THEMES, text_width  # noqa: E402

OUT = pathlib.Path(__file__).resolve().parents[2] / "docs" / "images"


# ---------------------------------------------------------------- 模块一：Deep Research
def research_agent() -> Diagram:
    d = Diagram("research-agent", 1400, 960,
                "Deep Research Agent：研究表驱动的规划、执行与分层验证",
                "Backend 持有研究表与全部判定；Worker 无状态，只提交候选与证据；租约与 fencing token 拒绝过期写回")
    d.lane(24, 92, 1352, 418, "Backend 协调面", "indigo", "研究状态全部落在 MySQL 账本，判定由 Backend 作出")
    plan = d.box(44, 132, 250, "研究问题与计划", [
        "PLANNING 角色提出计划提案",
        "研究对象 × 待验证字段",
        "Backend 校验后写入研究表"], "violet", tag="模型", h=116)
    # 研究表示意
    tx, ty = 322, 132
    d.rect(tx, ty, 380, 172, "#ffffff", "#4f46e5", radius=10)
    d.text(tx + 14, ty + 24, "研究表 Table-as-State", size=14.5, weight=700, fill="#312e81")
    cols = ["对象", "字段 1", "字段 2", "字段 3"]
    states = [("emerald", "已验证"), ("emerald", "已验证"), ("amber", "候选"),
              ("emerald", "已验证"), ("rose", "冲突"), ("slate", "缺口"),
              ("amber", "候选"), ("slate", "缺口"), ("emerald", "已验证")]
    cw, ch = 84, 26
    for i, col in enumerate(cols):
        d.text(tx + 20 + i * (cw + 6) + cw / 2, ty + 50, col, size=12, fill="#475569", anchor="middle", weight=600)
    for r in range(3):
        y = ty + 60 + r * (ch + 6)
        d.rect(tx + 20, y, cw, ch, "#f8fafc", "#cbd5e1", radius=5, width=1)
        d.text(tx + 20 + cw / 2, y + 17.5, f"对象 {'ABC'[r]}", size=12, fill="#334155", anchor="middle")
        for c in range(3):
            theme, label = states[r * 3 + c]
            fill, stroke, title, _ = THEMES[theme]
            x = tx + 20 + (c + 1) * (cw + 6)
            d.rect(x, y, cw, ch, fill, stroke, radius=5, width=1)
            d.text(x + cw / 2, y + 17.5, label, size=12, fill=title, anchor="middle")
    d.text(tx + 20, ty + 162, "每格独立记录候选、证据、判定与版本号", size=12, fill="#3730a3")
    tick = d.box(730, 132, 300, "协调器 tick", [
        "为缺口格与冲突格生成子任务",
        "按预算与修复上限决定是否补查",
        "命令先写发件箱，再投递 Kafka"], "indigo", code="ResearchAgentCoordinatorTickService", h=136)
    lease = d.box(1072, 132, 284, "并发控制", [
        "工作台研究槽位：Redis ZSET 租约",
        "分值为到期时间，过期自动回收",
        "子任务：lease_epoch + fencing token",
        "过期执行者的写回一律拒绝"], "teal", h=136)
    commit = d.box(44, 330, 300, "完成提交（单一事务）", [
        "加锁顺序固定：run → task →",
        "完成记录 → 预算预留 → 格 → 子键",
        "合并 SQL 同时比对格版本、",
        "租约代次与 fencing token"], "indigo", code="ResearchAgentCompletionCommitter", h=160)
    verifier = d.box(370, 330, 340, "分层 Verifier", [
        "格级：支持 / 部分支持 / 矛盾 / 不足",
        "全局：跨格冲突检测，标为 CONFLICTED",
        "引用：先比原文片段与数字、日期，",
        "再由模型判蕴含，模型不可用按词项兜底"], "indigo", h=160)
    replan = d.box(736, 330, 310, "局部重规划", [
        "从失败、过期的任务快照推出缺口",
        "缺口：格级补查，次数有上限",
        "冲突：有界的反证任务",
        "审计偏差类型、受影响格与预算变化"], "indigo", h=160)
    gate = d.box(1072, 330, 284, "检查点与完成门", [
        "每波写账本快照与内容摘要",
        "恢复时校验摘要，只重做缺口",
        "仅基础设施不可用才判失败",
        "证据不足如实写进报告"], "indigo", h=160)
    d.arrow([plan.right(-10), (322, plan.cy - 10)])
    d.arrow([(702, 190), tick.left(-12)], "缺口格")
    d.arrow([commit.right(), verifier.left()])
    d.arrow([verifier.right(), replan.left()])
    d.arrow([replan.right(), gate.left()])
    d.arrow([(880, 330), (880, tick.y + tick.h)], "补查", label_dx=26, label_dy=10)
    kafka = d.box(44, 530, 1312, "Kafka 研究命令：以子任务 ID 为消息键分散到各分区，多个 Worker 进程并行消费；同一子任务重复投递按发件箱去重", [], "amber", h=46,
                  center=True)
    d.arrow([tick.right(20), (1052, tick.cy + 20), (1052, kafka.y)], "子任务命令", label_at=(1052, 524),
            label_dy=0)
    d.lane(24, 600, 1352, 286, "Research Worker 执行面", "amber")
    roles = [
        ("WIDE_DISCOVERY", ["扩展研究对象", "只产出范围提案"]),
        ("DEEP_CELL", ["检索、抓取、阅读、抽取", "产出格候选与证据"]),
        ("COUNTERFACTUAL", ["针对冲突寻找反证", "候选或多数表决"]),
        ("EVIDENCE_AUDIT", ["审计证据可靠性", "产出审计结论"]),
        ("SYNTHESIS", ["基于已核验格写报告", "结论逐句带引用编号"]),
    ]
    xs = [44, 312, 580, 848, 1116]
    role_boxes = []
    for x, (name, lines) in zip(xs, roles):
        role_boxes.append(d.box(x, 648, 240, name, lines, "amber", h=100))
        d.arrow([(x + 190, kafka.y + kafka.h), (x + 190, 648)])
    d.arrow([role_boxes[1].left(), (298, role_boxes[1].cy), (298, commit.y + commit.h)],
            "完成信封：候选与证据", color="emerald", dashed=True, label_at=(298, 596), label_anchor="start",
            label_dx=8, label_dy=4)
    d.box(44, 772, 1312, "工具与边界", [
        "联网检索（Serper 与 MCP 搜索）、网页快照、原文抽取与本地校验；每个角色只能使用声明过的工具。",
        "Worker 无状态，按角色执行后以完成信封交回候选与证据；不直接修改研究表，所有合并在 Backend 的完成提交里完成。"], "slate", h=86)
    d.legend(44, 930, [("emerald", "已验证"), ("amber", "候选 / 执行面"), ("rose", "冲突"),
                       ("slate", "缺口"), ("teal", "并发控制")])
    return d


# ---------------------------------------------------------------- 模块二：QA / Note / Wiki
def knowledge_chains() -> Diagram:
    d = Diagram("knowledge-chains", 1400, 940,
                "任务感知的 QA / Note / Wiki 知识链路",
                "三种回答模式按需求粒度选择检索方式，共用范围校验、撤销过滤、有效性复核和证据预算")
    head = d.box(450, 96, 500, "独立查询与范围", [
        "渐进改写得到可独立检索的查询",
        "限定在本工作台、所选资料范围内"], "slate", h=82, center=True)
    lanes = [(40, "问答 QA", "sky", "快速事实查询"), (490, "精读 Note", "teal", "长文档连续阅读"),
             (940, "知识库 Wiki", "violet", "跨资料知识整合")]
    for x, label, theme, note in lanes:
        d.lane(x, 206, 420, 580, label, theme, note)
    qa = [
        ("并行召回", ["ES BM25：中文按相邻两字切词", "ES kNN 向量召回", "两路在线程池里并行执行"], None),
        ("RRF 融合与补召回", ["按名次融合两路结果", "命中少于 3 条时用备选查询补召回"], None),
        ("Rerank 精排", ["交互式回答单次总预算 6 秒", "超时换新连接重试，失败后冷却 30 秒",
                       "冷却期内直接用未重排结果"], "OpenAiCompatibleRerankClient"),
        ("片段证据", ["相邻段落只取靠近命中处的 300 字"], None),
    ]
    note = [
        ("资料级召回", ["先选出与问题相关的资料", "会话工作集里的资料加权"], None),
        ("窗口打分", ["向量命中与重排为候选窗口打分", "按阅读角色与得分选出窗口"], None),
        ("连续原文窗口", ["按原文顺序读取连续窗口", "回答说明窗口覆盖到哪里"], "NoteReadingRetriever"),
        ("原文窗口证据", ["整段原文，不拼接零散片段"], None),
    ]
    wiki = [
        ("BM25F 页面检索", ["标题 3、摘要 2、正文 1 加权", "IDF 在本工作台全部页面上统计"], None),
        ("沿链接传播一次", ["高分页面把 35% 得分传给相邻页", "被引用、反链多的页面小幅加成"], None),
        ("命中判定与截断", ["覆盖足够比例的查询词才算命中", "去掉明显低于最高分的长尾",
                          "没有页面命中时如实返回空"], "KnowledgeWikiSearchEngine"),
        ("页面与来源回链", ["沿知识网络展开，遍历受预算约束"], None),
    ]
    ys = [244, 362, 462, 622]
    hs = [100, 82, 140, 64]
    bottoms = {}
    for (x, _, theme, _), items in zip(lanes, [qa, note, wiki]):
        prev = None
        for (title, lines, code), y, h in zip(items, ys, hs):
            b = d.box(x + 20, y, 380, title, lines, theme, code=code, h=h)
            if prev is not None:
                d.arrow([prev.bottom(), b.top()])
            prev = b
        bottoms[x] = prev
        d.arrow([(head.cx if x == 490 else (head.x + 60 if x == 40 else head.x + head.w - 60), head.y + head.h),
                 (head.cx if x == 490 else (head.x + 60 if x == 40 else head.x + head.w - 60), 194),
                 (x + 210, 194), (x + 210, 244)])
    ingest = d.box(960, 696, 380, "知识页编译", ["资料就绪后经 Kafka 编译知识页；模型规划在事务外，",
                                              "短事务复核输入一致后写入，变化时重新规划"], "violet", h=82,
                   size=11.5, title_size=13)
    shared1 = d.box(40, 806, 650, "撤销过滤与有效性复核", [
        "ES 查询按 Redis 墓碑排除已撤销资料，已撤销片段不占 TopK 名额（见下一张图）",
        "召回后按主键批量回查 MySQL：资料未删除、快照仍是当前版本",
        "复核不通过的证据不进入提示，并记入 ownership_rejected_count"], "rose", h=104)
    shared2 = d.box(710, 806, 650, "证据预算与生成", [
        "三种模式共用证据预算；证据编号与引用卡片一一对应",
        "生成提示只允许引用本轮证据，不补写资料里没有的事实",
        "研究报告引用过的网页原文也可作为问答证据"], "slate", h=104)
    for x in (40, 490, 940):
        b = bottoms[x]
        target = shared1 if x == 40 else shared2
        tx = b.cx if x != 490 else 600
        if x == 490:
            d.arrow([b.bottom(), (b.cx, 796), (600, 796), (600, 806)])
        elif x == 40:
            d.arrow([b.bottom(), (b.cx, 806)])
        else:
            d.arrow([b.left(), (925, b.cy), (925, 806)])
    d.arrow([shared1.right(), shared2.left()])
    return d


def revocation() -> Diagram:
    d = Diagram("revocation-consistency", 1400, 812,
                "资料撤销：墓碑提前排除、MySQL 批量复核、版本号隔离过期缓存",
                "ES 片段异步删除，删除完成前旧片段仍可能被召回；三层机制分别负责召回名额、正确性和缓存")
    d.lane(24, 92, 1352, 330, "撤销（写路径）", "rose")
    user = d.box(44, 150, 220, "用户删除资料", ["删除接口写入撤销请求"], "slate", h=64)
    tx = d.box(292, 132, 320, "MySQL 删除事务", [
        "资料标为已删除",
        "写入 ES 清理任务",
        "发布 SourceDeletedEvent"], "slate", h=100)
    ver = d.box(292, 296, 320, "目录版本号 +1", [
        "提交后用单独事务递增",
        "资料目录缓存的键随之改变（见下方）"], "indigo", h=82)
    tomb = d.box(660, 122, 330, "Redis 墓碑", [
        "按工作台写一个有序集合",
        "成员为资料 ID，分值为到期时间",
        "默认保留 24 小时，失败只记指标"], "emerald", code="RevokedSourceTombstones", h=122)
    clean = d.box(1030, 122, 326, "ES 异步清理", [
        "删除 QA 片段与精读资料投影",
        "失败按退避重试",
        "每次执行前先给墓碑续期，",
        "也挡住删除后迟到的投影写入"], "amber", code="SourceDeletionCleanupListener", h=140)
    d.arrow([user.right(), (tx.x, user.cy)])
    d.arrow([(tx.x + tx.w, tomb.cy), tomb.left()], "提交后")
    d.arrow([tx.bottom(), ver.top()], "提交后", label_dx=34, label_dy=8)
    d.arrow([tx.top(), (tx.cx, 108), (clean.cx, 108), clean.top()], "清理任务", label_at=(900, 108), label_dy=4)
    d.arrow([clean.left(-30), (tomb.x + tomb.w, tomb.cy - 30)], "续期", color="emerald", dashed=True)
    d.lane(24, 442, 1352, 180, "检索（读路径）", "sky")
    q = d.box(44, 492, 200, "问题", ["改写后的独立查询"], "slate", h=64)
    read = d.box(270, 484, 300, "读取墓碑", [
        "先删除已过期的成员",
        "再取未过期的资料 ID",
        "Redis 出错时返回空集"], "emerald", h=100)
    es = d.box(596, 484, 360, "ES 召回", [
        "向量与关键词召回共用作用域过滤",
        "must_not terms source_id 排除",
        "已撤销资料不再占 TopK 名额"], "sky", h=100)
    recheck = d.box(982, 476, 374, "MySQL 批量复核（权威）", [
        "按片段主键批量回查资料与快照",
        "资料未删除且快照为当前版本才保留",
        "Redis 故障时它仍会拒绝已撤销资料",
        "拒绝条数记为 ownership_rejected_count"], "rose", h=124)
    d.arrow([q.right(), (read.x, q.cy)])
    d.arrow([read.right(), es.left()])
    d.arrow([es.right(), (recheck.x, es.cy)])
    d.arrow([tomb.bottom(), (tomb.cx, 430), (420, 430), (420, read.y)], "读", color="emerald",
            label_at=(560, 430), label_dy=4)
    d.lane(24, 642, 1352, 150, "缓存（资料目录）", "indigo")
    c1 = d.box(44, 688, 420, "读取", ["先取当前目录版本号", "再按 工作台 + 版本号 读缓存"], "indigo", h=82)
    c2 = d.box(490, 688, 420, "未命中", ["查库后写到读取时那个版本号的键", "快照里也记着版本号，读取时再核对"],
               "indigo", h=82)
    c3 = d.box(936, 688, 420, "删除之后", ["版本号已变，读取改用新键", "过期结果只会落在旧键上，不会被命中"],
               "rose", h=82)
    d.arrow([c1.right(), c2.left()])
    d.arrow([c2.right(), c3.left()])
    return d


# ---------------------------------------------------------------- 模块三：Context 与 Memory
def context_assembly() -> Diagram:
    d = Diagram("context-assembly", 1400, 960,
                "一轮回答的上下文拼装：改写模型判断话题，冻结后拼接提示",
                "改写在冻结之前完成，同一次调用给出话题编号与独立查询；没有判断时按规则与向量切分")
    d.lane(24, 92, 1352, 222, "后台增量维护", "amber", "上一轮结束后异步执行，不占本轮等待时间")
    a4 = d.box(44, 132, 420, "边界复核", [
        "只处理规则切分时标为不确定的边界",
        "后台每 30 秒批量交给模型",
        "模型不可用时保持不确定"], "amber", code="TopicBoundaryReviewService", tag="模型", h=150)
    a3 = d.box(490, 132, 420, "话题增量摘要", [
        "原文尾部之前的部分写入发件箱",
        "在上一版摘要上只读新增消息",
        "每版不可变，失败退回抽取式摘要"], "amber", code="ConversationTopicSummaryV2Service", tag="Kafka", h=150)
    a1 = d.box(936, 132, 420, "约束抽取", [
        "把用户消息抽成约束操作",
        "ConstraintOpMerger 纯函数折叠成快照",
        "本轮问题的约束在冻结之后补抽"], "amber", code="ConversationConstraintProjectionV2Service",
        tag="模型", h=150)
    d.lane(24, 330, 1352, 316, "本轮准备", "indigo")
    r1 = d.box(44, 372, 230, "判断材料", [
        "最近 8 条原文",
        "已有话题列表，T1 为最近",
        "最近话题的摘要"], "indigo", code="ConversationTopicRoutingService", h=128)
    r2 = d.box(296, 372, 250, "改写与话题判断", [
        "先给话题编号：T1 接着、",
        "Tn 回到旧话题、n 新话题",
        "再给补全主题的独立查询"], "violet", code="QueryRewriteService", tag="模型", h=128)
    r3 = d.box(568, 372, 300, "冻结：本轮归入哪个话题", [
        "有模型判断时以模型为准",
        "首轮、改写失败或跳过改写时，",
        "按规则与向量切分",
        "已落库的划分不回改"], "indigo", code="TopicSegmenterV2", h=140)
    r4 = d.box(890, 372, 230, "窗口规划与快照", [
        "目标话题摘要优先",
        "最近的其他话题带一个",
        "回到旧话题带段尾原文",
        "写入 MySQL 冻结快照"], "indigo", code="ContextWindowPlannerV2", h=140)
    r5 = d.box(1142, 372, 214, "检索与证据", [
        "用改写结果检索",
        "撤销过滤与复核",
        "详见模块二"], "sky", h=128)
    r6 = d.box(1142, 538, 214, "记忆控制包", [
        "按独立查询编译",
        "Redis 按版本缓存"], "emerald", code="MemoryCompilerService", h=102)
    for left, right in ((r1, r2), (r2, r3), (r3, r4)):
        d.arrow([(left.x + left.w, 420), (right.x, 420)])
    d.arrow([r2.bottom(60), (r2.cx + 60, 524), (1170, 524), (1170, r5.y + r5.h)], "独立查询",
            label_at=(720, 524))
    d.arrow([(1150, 524), (1150, r6.y)])
    d.arrow([a3.bottom(), (a3.cx, 322), (r1.cx, 322), (r1.cx, r1.y)], "话题与摘要", label_at=(400, 322),
            label_dy=4)
    d.arrow([a1.bottom(), (a1.cx, r4.y)], "生效约束", label_at=(a1.cx, 344), label_dx=0)
    d.lane(24, 666, 1352, 276, "生成提示拼装", "slate", "按实际拼装顺序从前到后")
    segs = [
        (44, 140, "公共规则", ["回答规范", "引用格式"], "slate"),
        (188, 190, "模式规则", ["问答 / 精读 / Wiki", "按问题追加条件规则", "内部检索边界"], "slate"),
        (382, 240, "最近对话上下文", ["目标话题与最近话题的摘要", "当前有效的用户约束", "原文尾部（可含旧话题段尾）"], "indigo"),
        (626, 170, "资料片段", ["[1] … [n] 编号", "与引用卡片对应"], "sky"),
        (800, 240, "表达控制", ["记忆控制包", "冻结约束（同义去重）", "偏好参数与会话要求"], "emerald"),
        (1044, 110, "当前问题", ["用户消息"], "slate"),
    ]
    seg_boxes = {}
    for x, w, title, lines, theme in segs:
        seg_boxes[title] = d.box(x, 716, w, title, lines, theme, h=146)
    model = d.box(1186, 716, 170, "模型流式生成", ["token 写入 Redis", "Stream，经 SSE", "推送到浏览器"],
                  "violet", h=146)
    d.arrow([seg_boxes["当前问题"].right(), model.left()])
    ctx = seg_boxes["最近对话上下文"]
    d.arrow([r4.bottom(), (r4.cx, 680), (ctx.cx, 680), (ctx.cx, 716)], "冻结快照", label_at=(640, 680))
    d.arrow([r6.bottom(), (r6.cx, 700), (960, 700), (960, 716)], "控制包", label_at=(1100, 700))
    ev = seg_boxes["资料片段"]
    d.arrow([r5.right(), (1366, r5.cy), (1366, 884), (ev.cx, 884), (ev.cx, ev.y + ev.h)],
            "本轮证据", label_at=(1000, 884))
    d.text(44, 924, "事实只来自资料片段；话题摘要、约束和记忆只用于理解追问和决定表达方式。"
                    "话题判断与每项的入选、排除原因都写入冻结快照，可在上下文检查器中查看。", size=12.5, fill="#475569")
    return d


def context_compression() -> Diagram:
    d = Diagram("context-compression", 1400, 940,
                "上下文压缩：按话题分段、增量摘要与原文尾部",
                "更早的消息由所属话题的摘要覆盖，最近的消息保留原文；话题只决定摘要的分组与优先级")
    x0, cw, gap, y = 92, 46, 4, 168
    topic_of = ["A"] * 8 + ["B"] * 6 + ["A"] * 10
    d.text(40, y + 23, "消息", size=13, weight=700, fill="#334155")
    for i, topic in enumerate(topic_of):
        theme = "indigo" if topic == "A" else "amber"
        fill, stroke, title, _ = THEMES[theme]
        x = x0 + i * (cw + gap)
        raw = i >= 16
        d.rect(x, y, cw, 36, fill, stroke, radius=6, width=2.2 if raw else 1)
        d.text(x + cw / 2, y + 23, str(i + 1), size=12, fill=title, anchor="middle", weight=600)

    def span(a, b):
        return x0 + (a - 1) * (cw + gap), x0 + (b - 1) * (cw + gap) + cw

    for (a, b, label, theme) in [(1, 8, "段 1 · 话题 A", "indigo"), (9, 14, "段 2 · 话题 B", "amber"),
                                 (15, 24, "段 3 · 回到话题 A（改写模型判断：T2）", "indigo")]:
        sx, ex = span(a, b)
        stroke = THEMES[theme][1]
        d.parts.append(f'<path d="M{sx},{y - 8} L{sx},{y - 16} L{ex},{y - 16} L{ex},{y - 8}" fill="none" '
                       f'stroke="{stroke}" stroke-width="1.5"/>')
        d.text((sx + ex) / 2, y - 22, label, size=12.5, fill=THEMES[theme][2], anchor="middle", weight=600)
    # 原文尾部与补齐
    sx, ex = span(19, 24)
    d.rect(sx - 4, y + 44, ex - sx + 8, 22, "#eef2ff", "#4f46e5", radius=6, width=1.2)
    d.text((sx + ex) / 2, y + 59, "原文尾部（6 条，按 token 收缩后）", size=11.5, fill="#312e81", anchor="middle")
    bx, bex = span(17, 18)
    d.rect(bx - 4, y + 44, bex - bx + 8, 22, "#ffffff", "#4f46e5", radius=6, width=1.2, dashed=True)
    d.text((bx + bex) / 2, y + 59, "补齐", size=11.5, fill="#312e81", anchor="middle")

    def summary_bar(a, b, row, label, theme, verdict, verdict_theme):
        sx, ex = span(a, b)
        yy = y + 80 + row * 30
        fill, stroke, title, _ = THEMES[theme]
        d.rect(sx, yy, ex - sx, 22, fill, stroke, radius=5, width=1.1)
        d.text(sx + 8, yy + 15.5, label, size=11.5, fill=title)
        vf, vs, vt, _ = THEMES[verdict_theme]
        vw = text_width(verdict, 11.5) + 14
        d.rect(ex + 6, yy, vw, 22, vf, vs, radius=11, width=1)
        d.text(ex + 6 + vw / 2, yy + 15.5, verdict, size=11.5, fill=vt, anchor="middle")

    summary_bar(1, 5, 0, "摘要 v1（1-5）", "slate", "被 v2 取代", "slate")
    summary_bar(1, 8, 1, "摘要 v2（1-8，在 v1 上只读 6-8）", "indigo", "入选：同话题旧段", "emerald")
    summary_bar(9, 14, 0, "摘要（9-14）", "amber", "入选：最近的其他话题", "emerald")
    summary_bar(15, 16, 1, "15-16", "indigo", "入选：当前段，落后 2 条", "emerald")
    d.text(40, y + 96, "摘要", size=13, weight=700, fill="#334155")
    d.legend(92, 330, [("indigo", "话题 A"), ("amber", "话题 B"), ("emerald", "进入本轮上下文"), ("rose", "排除")])
    d.text(500, 330, "例：当前问题在第 24 条之后，上下文 = 段 1 摘要 v2 + 段 2 摘要 + 段 3 摘要（15-16）+ 原文 17-24",
           size=12.5, fill="#334155", weight=600)
    p1 = d.box(40, 362, 420, "原文尾部怎么定", [
        "从最新一条往前数，至少 2 条、最多 8 条",
        "累计超过 6000 token 或总预算一半就停",
        "短消息保留满 8 条，长回答只留最近几轮",
        "摘要任务用同一规则决定覆盖终点"], "indigo", code="ContextWindowPlannerV2.rawTailSize", h=166)
    p2 = d.box(490, 362, 420, "当前话题的摘要落后时", [
        "已覆盖到原文尾部之前：直接使用",
        "落后 1～8 条：原文向前补到摘要终点之后",
        "落后更多或还没有摘要：展开本段原文",
        "仍超长：先按 256 条、再按预算裁掉最旧",
        "至少保留最新一条，只记降级不阻断"], "indigo", h=166)
    p3 = d.box(940, 362, 420, "摘要怎么选", [
        "每段取覆盖到原文尾部之前的最新一版",
        "目标话题的各段优先，最近的其他话题再带一个",
        "话题归属不确定时不引入同话题其他段",
        "与原文重叠或范围过期的版本排除，超预算记降级",
        "回到旧话题：上一段摘要没覆盖的段尾原文",
        "最多带 4 条"], "indigo", h=166)
    d.lane(24, 540, 1352, 196, "增量摘要生成", "amber", "Kafka 异步执行，生成慢只会让原文补齐的范围变大")
    q1 = d.box(44, 584, 220, "发件箱", ["与切分结果同事务写入", "只取原文尾部之前的消息"], "amber", h=82)
    q2 = d.box(290, 584, 230, "Kafka", ["noteweave.conversation", ".summary"], "amber", h=82)
    q3 = d.box(546, 576, 300, "增量摘要", [
        "上一版摘要 + 新增消息",
        "长消息分块全部放进提示",
        "输出更新后的完整摘要"], "violet", tag="模型", h=100)
    q4 = d.box(872, 576, 250, "模型失败时兜底", [
        "上一版 + 新增原文",
        "超长保留首尾并标注截断"], "rose", h=82)
    q5 = d.box(1148, 576, 208, "写入版本", ["状态 READY，内容不可变", "段 + 终点唯一"], "amber", h=82)
    d.arrow([q1.right(), q2.left()])
    d.arrow([q2.right(), q3.left()])
    d.arrow([q3.right(), (q4.x, q3.cy)])
    d.arrow([q3.bottom(), (q3.cx, 700), (q5.cx, 700), (q5.cx, q5.y + q5.h)], "成功",
            label_at=(1000, 700))
    d.arrow([q4.right(), q5.left()])
    d.box(40, 770, 1320, "为什么这样切", [
        "话题由查询改写模型在同一次调用里判断，规则与向量兜底；话题只决定摘要的分组与优先级，原文尾部每轮都带，"
        "最近的其他话题也带一个，判断偶尔出错也不会丢前文。",
        "摘要按版本追加、从不原地修改，冻结快照引用的是具体版本，重新生成时上下文和当时完全一致。"], "slate", h=88)
    return d


def memory_write() -> Diagram:
    d = Diagram("memory-write", 1400, 900,
                "长期记忆写入：抽取、冲突检测与门控晋升",
                "槽位由模型给出，代码只做校验与比对；任一门控不通过就进入待确认，冲突不会自动覆盖旧记忆")
    m1 = d.box(40, 104, 200, "用户消息", ["回答结束后进入异步任务"], "slate", h=64)
    m2 = d.box(264, 104, 230, "Kafka 抽取任务", ["noteweave.memory", ".extraction"], "amber", h=82)
    m3 = d.box(518, 104, 236, "短分类", ["是否包含对回答方式的要求，", "或对上一轮回答的反馈"], "violet",
               tag="模型", h=82)
    m4 = d.box(778, 104, 320, "结构化抽取", [
        "数据围栏包住用户消息与上一轮回答",
        "槽位只取模型输出，限 8 个受控槽位",
        "槽位不合规整批作废，失败重试一次"], "violet", tag="模型", h=100)
    m5 = d.box(1122, 104, 238, "记忆信号", [
        "显式要求 / 反馈 / 推断",
        "按证据档位写入 Beta 先验",
        "反馈的方面由模型判断"], "emerald", h=100)
    for a, b in ((m1, m2), (m2, m3), (m3, m4), (m4, m5)):
        d.arrow([a.right(), (b.x, a.cy)])
    m6 = d.box(40, 330, 300, "候选", [
        "计算边际效用",
        "常驻与工作台范围有加成",
        "记录适用的任务邻域"], "emerald", h=100)
    d.arrow([m5.bottom(), (m5.cx, 290), (m6.cx, 290), (m6.cx, m6.y)], "生成候选", label_at=(700, 290))
    m7 = d.box(370, 330, 340, "冲突检测", [
        "1. 同槽位：比较取值与极性",
        "2. 没有同槽位：记忆向量 Top5 + 模型判定",
        "向量索引不可用：记为未检查",
        "冲突或未检查都会阻断自动晋升"], "rose", code="MemoryConflictService", h=140)
    m8 = d.box(740, 330, 330, "四项门控", [
        "证据：置信度达标且后验方差足够小",
        "效用：不低于自动晋升阈值",
        "风险：禁忌、工作台范围、冲突加分",
        "范围：工作台、用户、任务邻域齐全"], "emerald", code="MemoryCandidateGate", h=140)
    d.arrow([m6.right(), m7.left(-20)])
    d.arrow([m7.right(), m8.left()])
    m9 = d.box(1100, 318, 260, "自动晋升", ["四项全部通过，风险分低于 0.70"], "emerald", tag="READY", h=64)
    m10 = d.box(1100, 430, 260, "待确认", ["对话内的候选卡片", "或在记忆页确认、拒绝"], "rose",
                tag="NEEDS_REVIEW", h=82)
    d.arrow([m8.right(-20), (1085, m8.cy - 20), (1085, m9.cy), m9.left()])
    d.arrow([m8.right(20), (1085, m8.cy + 20), (1085, m10.cy), m10.left()], "任一不通过", label_at=(1085, 500),
            label_anchor="end", label_dx=-6)
    d.arrow([m10.top(), (m10.cx, m9.y + m9.h)], "确认", color="emerald", dashed=True, label_dx=24, label_dy=4)
    m11 = d.box(370, 600, 340, "版本化写入", [
        "memory_item + 不可变的 revision",
        "冲突的旧记忆标为已替代",
        "记下失效时间，历史版本可查"], "emerald", code="CanonicalMemoryReviewService", h=120)
    m12 = d.box(740, 600, 330, "索引发件箱", [
        "与晋升在同一事务入队",
        "代次作为 ES external version",
        "租约认领，投递前重读权威内容"], "amber", code="MemoryIndexOutbox", h=120)
    m13 = d.box(1100, 600, 260, "记忆向量索引", ["noteweave-memory", "供冲突检测与按需召回"], "sky", h=82)
    d.arrow([m9.right(), (1380, m9.cy), (1380, 560), (m11.cx, 560), (m11.cx, m11.y)], "生效", label_at=(900, 560))
    d.arrow([m11.right(), m12.left()])
    d.arrow([m12.right(), (m13.x, m12.cy)])
    d.arrow([m13.bottom(), (m13.cx, 760), (355, 760), (355, 490), (450, 490), (450, m7.y + m7.h)],
            "冲突检测查询", color="sky", dashed=True, label_at=(800, 760))
    d.box(40, 600, 300, "会话约束与长期记忆分开", [
        "以后用表格这类说法由约束",
        "抽取处理，在本会话立即生效",
        "跨会话的长期记忆要经过门控"], "indigo", h=100)
    d.box(40, 796, 1320, "设计取舍", [
        "长期记忆只存回答偏好（语言、篇幅、风格、结构、表格、术语、交互方式），事实一律来自资料证据；"
        "模型调用全部在异步任务里，不占回答等待时间。"], "slate", h=70)
    return d


def memory_read() -> Diagram:
    d = Diagram("memory-read-feedback", 1400, 900,
                "记忆读取与效果反馈：按价值密度装入预算，效果变差就退回复核",
                "记忆只决定怎么说，不提供事实；每次回答记下实际注入的记忆版本，反馈记到这些记忆上")
    r1 = d.box(40, 104, 250, "生效记忆", ["当前版本为 ACTIVE", "按工作台与任务邻域读取"], "emerald", h=82)
    r2 = d.box(314, 104, 280, "读路径过滤", ["来源未就绪或不可用的记忆排除", "排除原因写入编译记录"], "rose", h=82)
    r3 = d.box(618, 104, 340, "分类与相关度", [
        "常驻：风格、结构、禁忌、交互",
        "按需：术语类，按问题相关度选入",
        "向量与 BM25 各排一次，RRF 融合",
        "相关度为 0 的按需记忆直接排除"], "emerald", h=120)
    r4 = d.box(982, 104, 378, "价值与预算", [
        "价值 = 置信度 × 效用 × 相关度 × 新近度",
        "置信度取 Beta 后验 α / (α + β)",
        "新近度按上次强化时间的半衰期衰减",
        "按价值除以篇幅从高到低装入预算",
        "装不下的记为超出预算"], "emerald", code="MemoryCompilerService", h=160)
    d.arrow([r1.right(), r2.left()])
    d.arrow([r2.right(), r3.left(-19)])
    d.arrow([r3.right(), (r4.x, r3.cy)])
    r5 = d.box(40, 330, 430, "Redis 编译缓存", [
        "键：工作台、身份指纹、包类型、请求指纹、",
        "策略版本、记忆状态指纹",
        "记忆变化后状态指纹改变，旧缓存不再命中",
        "TTL 180 秒加随机抖动"], "sky", code="MemoryCompiledPackCache", h=140)
    r6 = d.box(500, 330, 420, "控制包", [
        "风格、结构、术语、禁用写法",
        "证据口径、交互策略、复核清单",
        "与冻结的会话约束按归一化文本去重",
        "写入生成提示的表达控制一节"], "emerald", h=120)
    r7 = d.box(950, 330, 410, "资料证据走另一条通道", [
        "事实与引用只来自资料检索",
        "记忆不提供事实，只控制表达",
        "回答记录实际注入的记忆版本"], "sky", h=100)
    d.arrow([r4.bottom(-100), (r4.cx - 100, 300), (r6.cx + 60, 300), (r6.cx + 60, r6.y)], "编译结果")
    d.arrow([r5.right(), (r6.x, r5.cy - 10)], "命中直接返回", color="sky", dashed=True, label_at=(485, 322), label_dy=0)
    d.lane(24, 500, 1352, 260, "效果反馈闭环", "rose")
    f1 = d.box(44, 548, 300, "反馈来源", [
        "点赞、点踩",
        "重新生成",
        "下一轮追问：模型判断正负与方面",
        "产物编辑幅度"], "slate", h=120)
    f2 = d.box(370, 548, 340, "记到实际注入的记忆", [
        "同一回答的点赞点踩以最后一次为准",
        "效用 = 0.75 × 旧值 + 0.25 × 本次",
        "新会话的正向证据累加到 Beta 后验"], "emerald", code="MemoryOutcomePolicy", h=120)
    f3 = d.box(736, 548, 300, "退回复核", [
        "单次负反馈，或累计 2 次不良",
        "使用不少于 3 次且效用 ≤ 0.40",
        "或不良比例 ≥ 0.67 时标为 STALE"], "rose", h=120)
    f4 = d.box(1062, 548, 294, "后台整理", [
        "模型合并近似重复的记忆",
        "长期没有强化的新记忆送回复核",
        "模型不可用时不合并"], "violet", code="MemoryConsolidationService", tag="模型", h=120)
    d.arrow([f1.right(), f2.left()])
    d.arrow([f2.right(), f3.left()])
    d.arrow([r6.bottom(), (r6.cx, 520), (f1.cx, 520), (f1.cx, f1.y)], "回答注入", label_at=(440, 520))
    d.arrow([f3.bottom(), (f3.cx, 730), (32, 730), (32, r1.cy), r1.left()], "复核通过后才重新生效", color="rose",
            dashed=True, label_at=(500, 730))
    d.box(40, 790, 1320, "为什么这样设计", [
        "常驻记忆每轮都带，按需记忆只在相关时带，避免把无关偏好塞进提示；编译结果按状态指纹缓存，"
        "记忆一变缓存自然失效，不需要主动清理。"], "slate", h=70)
    return d


# ---------------------------------------------------------------- 模块四：资料流水线与流式回答
def source_pipeline() -> Diagram:
    d = Diagram("source-pipeline", 1400, 760,
                "资料异步处理流水线：分阶段 Kafka 任务与幂等重试",
                "上传只负责收文件；解析、切片、向量化、索引拆成独立阶段，阶段之间只传快照 ID")
    u1 = d.box(40, 104, 280, "分片上传", [
        "单文件最大 128 MB",
        "流式读取，固定大小缓冲区",
        "边读边校验格式与大小"], "slate", code="UploadSecurityPolicy", h=120)
    u2 = d.box(346, 104, 230, "MinIO", ["原始文件与大对象", "阶段之间按对象路径交接"], "sky", h=82)
    u3 = d.box(602, 104, 330, "资料与快照（MySQL）", [
        "资料版本不可变",
        "阶段任务写入发件箱，与业务数据同事务",
        "发件箱投递 Kafka，失败重投"], "indigo", h=100)
    u4 = d.box(958, 104, 400, "media-mcp（MCP 服务）", [
        "B 站解析、字幕、faster-whisper 转写",
        "抓帧、时间轴、OCR",
        "转写结果由模型逐批校对识别错误"], "violet", h=100)
    d.arrow([(u1.x + u1.w, u2.cy), u2.left()])
    d.arrow([u2.right(), (u3.x, u2.cy)])
    stages = [
        (40, "解析", ["parse-worker（Docling）转换文档", "扫描页按需 OCR，保留页码与位置", "音视频调用 media-mcp 转写"], "noteweave.source.parse"),
        (380, "切片", ["按结构切成检索片段", "生成精读用的原文窗口"], "noteweave.source.chunk"),
        (720, "向量化", ["批量调用 Embedding", "失败只重试这一阶段"], "noteweave.source.embed"),
        (1060, "索引", ["写入 ES 问答片段与精读资料", "全部写完才标为可检索"], "noteweave.source.index"),
    ]
    d.lane(24, 270, 1352, 210, "Kafka 阶段任务", "amber")
    sb = []
    for x, title, lines, topic in stages:
        sb.append(d.box(x, 316, 300, title, lines, "amber", code=topic, h=122))
    for a, b in zip(sb, sb[1:]):
        d.arrow([a.right(), b.left()])
    d.arrow([u3.bottom(-100), (u3.cx - 100, 240), (sb[0].cx, 240), (sb[0].cx, sb[0].y)], "资料快照 ID",
            label_at=(400, 240), label_dy=4)
    d.arrow([sb[0].top(80), (sb[0].cx + 80, 256), (u4.cx, 256), (u4.cx, u4.y + u4.h)], "音视频", color="violet",
            dashed=True, label_at=(900, 256), label_dy=4)
    g1 = d.box(40, 512, 420, "幂等与重试", [
        "按快照、阶段与版本生成幂等键去重",
        "索引失败记录下次重试时间，退避重试",
        "超过次数进入死信，状态回写资料"], "rose", h=100)
    g2 = d.box(490, 512, 420, "状态可见", [
        "资料的处理阶段、重试次数实时回写",
        "前端显示阶段与下次重试时间",
        "检索投影就绪前不对检索可见"], "indigo", h=100)
    g3 = d.box(940, 512, 420, "下游任务", [
        "noteweave.wiki.ingest：编译知识页",
        "资料撤销：墓碑与异步清理（见模块二）",
        "产物生成按冻结的快照读取原文与关键帧"], "sky", h=100)
    d.box(40, 640, 1320, "为什么拆成阶段", [
        "大文件解析与向量化都很慢，同步处理会占住请求线程；拆成阶段后每个阶段可以单独扩容、单独重试，"
        "失败时能精确定位到阶段。",
        "阶段之间只传快照 ID，大对象走 MinIO，消息体积固定；同一快照重复投递时按幂等键跳过。"], "slate", h=88)
    return d


def answer_streaming() -> Diagram:
    d = Diagram("answer-streaming", 1400, 760,
                "流式回答：Redis Stream 跨实例中转 SSE，按序号断线续读",
                "MySQL 保存最终快照；Redis 只负责实时传输，按条数裁剪控制内存，游标过期时从快照恢复")
    a1 = d.box(40, 104, 220, "浏览器", ["POST 发送消息"], "slate", h=64)
    a2 = d.box(286, 104, 300, "提交回合", [
        "写入用户消息与回答占位",
        "立即返回回合回执",
        "按提交 ID 幂等，可恢复"], "indigo", h=100)
    a3 = d.box(612, 104, 330, "后台准备与生成", [
        "冻结、改写、检索、记忆编译",
        "每个阶段发布进度事件",
        "模型 token 逐段发布"], "violet", h=100)
    a4 = d.box(968, 96, 392, "Redis Stream（每个回答一条）", [
        "Lua 脚本：INCR 分配单调序号，XADD 写入",
        "超过 512 条时 XDEL 删除最旧的事件",
        "记录已裁剪到的位置，TTL 600 秒"], "sky", code="RedisAnswerRealtimeBridge", tag="Lua", h=120)
    d.arrow([a1.right(), a2.left(-18)])
    d.arrow([a2.right(), a3.left()])
    d.arrow([a3.right(), (a4.x, a3.cy)])
    b1 = d.box(968, 300, 392, "多实例转发", [
        "处理连接的实例按序号从 Stream 读取",
        "本地有界回放缓存兜底",
        "每个订阅者一条串行队列，慢消费者不拖累其他人"], "sky", code="SessionEventMux", h=120)
    b2 = d.box(500, 308, 420, "SSE 推送", [
        "事件 id 就是序号",
        "完成、失败、取消为终止事件",
        "终止后关闭连接"], "indigo", h=100)
    b3 = d.box(40, 308, 420, "浏览器渲染", [
        "按序号渲染，重复序号直接丢弃",
        "阶段事件显示准备进度"], "slate", h=82)
    d.arrow([a4.bottom(), b1.top()])
    d.arrow([b1.left(), (b2.x + b2.w, b1.cy)])
    d.arrow([b2.left(), (b3.x + b3.w, b2.cy)])
    c1 = d.box(40, 500, 420, "断线重连", [
        "请求头带 Last-Event-ID",
        "取它与 after 参数中较大的一个作游标"], "slate", h=82)
    c2 = d.box(500, 500, 420, "续读", [
        "读取 Stream 中序号之后的事件",
        "与本地回放合并去重后补发",
        "续上之后继续接收实时事件"], "indigo", h=100)
    c3 = d.box(960, 500, 400, "游标早于已裁剪位置", [
        "返回 409 ANSWER_EVENT_CURSOR_EXPIRED",
        "前端改从 MySQL 最终快照恢复正文"], "rose", h=82)
    d.arrow([b3.bottom(), c1.top()], "网络中断", color="rose", dashed=True, label_dx=40, label_dy=4)
    d.arrow([c1.right(), c2.left(-9)])
    d.arrow([c2.right(), (c3.x, c2.cy)], color="rose")
    d.arrow([c2.top(), (c2.cx, b2.y + b2.h)], "补发", label_dx=24, label_dy=4)
    d.box(40, 650, 1320, "为什么这样设计", [
        "回答在哪个实例生成和浏览器连在哪个实例无关，Redis Stream 让任意实例都能按序号续读；按条数裁剪让单个回答"
        "的内存有上限。",
        "回答正文最终以 MySQL 为准，Redis 数据丢失只影响实时性，不影响结果。"],
        "slate", h=88)
    return d


# ---------------------------------------------------------------- 模块五：产物框架
def artifact_framework() -> Diagram:
    d = Diagram("artifact-framework", 1400, 960,
                "Schema 驱动的产物生成框架：SKILL.md 声明、Java Host 管控、Python Worker 五段流水线",
                "新增同形态产物只加一个 SKILL.md；模型输出必须通过 Schema、引用和数字一致性校验")
    d.lane(24, 92, 1352, 150, "Skill 声明", "violet", "Host 与 Worker 读取同一目录")
    s1 = d.box(44, 132, 420, "skills/<name>/SKILL.md", ["形态、用户可选项、输出契约", "新增同形态产物只需加一个目录"],
               "violet", h=82)
    s2 = d.box(490, 132, 420, "schemas/*.json", ["产物内容的 JSON Schema", "校验阶段逐项比对"], "violet", h=82)
    s3 = d.box(936, 132, 420, "14 种产物", ["报告、简报、测验、闪卡、思维导图、时间线", "课程笔记、演示文稿、视频 PDF 讲义等"],
               "violet", h=82)
    d.lane(24, 262, 1352, 226, "Java Host", "indigo")
    h1 = d.box(44, 306, 300, "请求校验", [
        "选项按 Skill 声明校验",
        "未给数量时从需求原文推断",
        "冻结来源资料的版本"], "indigo", code="ArtifactSkillCatalogService", h=120)
    h2 = d.box(370, 306, 300, "并发准入", [
        "工作台并发租约：Redis ZSET",
        "默认每工作台 4 个，租约 300 秒",
        "超出时排队并显示位置"], "teal", code="WorkloadQuotaService", h=120)
    h3 = d.box(696, 306, 300, "发件箱与投递", [
        "任务与发件箱同一事务",
        "Kafka noteweave.artifact.job",
        "失败进入死信队列"], "amber", h=120)
    h4 = d.box(1022, 306, 334, "候选验收与版本", [
        "引用必须属于冻结的来源",
        "核对文件清单后入库",
        "产物版本化，文件存 MinIO",
        "可写回 Note，旧版本可恢复"], "indigo", h=120)
    d.arrow([h1.right(), h2.left()])
    d.arrow([h2.right(), h3.left()])
    d.arrow([(h1.cx, s1.y + s1.h), h1.top()], "选项契约", label_dx=40, label_dy=4)
    d.lane(24, 508, 1352, 250, "Python Worker 五段流水线", "amber")
    stages = [
        ("取材", ["按冻结来源检索证据", "视频读取文字与关键帧"], "20%"),
        ("规划", ["生成大纲与标题", "标题按资料主题命名"], "35%"),
        ("写作", ["按 Schema 生成内容", "每条内容带证据引用"], "65%"),
        ("校验", ["Schema、引用、数字一致性", "不通过时修复一次"], "85%"),
        ("渲染", ["Markdown、CSV、PPTX、PDF", "XeLaTeX 编译讲义"], "100%"),
    ]
    xs = [44, 312, 580, 848, 1116]
    st = []
    for x, (title, lines, pct) in zip(xs, stages):
        st.append(d.box(x, 556, 240, title, lines, "amber", tag=pct, h=100))
    for a, b in zip(st, st[1:]):
        d.arrow([a.right(), b.left()])
    d.arrow([h3.bottom(), (h3.cx, 498), (st[0].cx + 90, 498), (st[0].cx + 90, st[0].y)], "任务消息", label_at=(560, 498), label_dy=4)
    d.arrow([st[4].right(-20), (1366, st[4].cy - 20), (1366, 460), (h4.cx, 460), (h4.cx, h4.y + h4.h)],
            "候选内容与文件清单", color="emerald", label_at=(1250, 460))
    d.text(330, 690, "多条消费通道并行处理任务，每段完成推送进度；校验不通过时把错误项交给模型修复一次，仍不通过则给出用户能看懂的失败原因。",
           size=12.5, fill="#92400e")
    d.lane(24, 778, 1352, 162, "媒体工具（MCP）", "violet")
    m1 = d.box(44, 820, 620, "media-mcp", [
        "B 站解析、字幕、faster-whisper 转写、抓帧、时间轴、OCR",
        "Worker 通过 MCP 协议调用，转写与关键帧随资料快照保存"], "violet", h=82)
    m2 = d.box(690, 820, 666, "视频产物", [
        "演示文稿每页配视频原画面，并标注时间点与字幕证据",
        "PDF 讲义插入关键帧；资料没有关键帧时直接说明原因"], "violet", h=82)
    d.arrow([m1.right(), m2.left()])
    d.arrow([(st[0].cx, st[0].y + st[0].h), (st[0].cx, m1.y)], "关键帧", color="violet", dashed=True,
            label_dx=34, label_dy=4)
    return d


# ---------------------------------------------------------------- 总览
def architecture_overview() -> Diagram:
    d = Diagram("architecture-overview", 1400, 830, "NoteWeave 系统架构",
                "Backend 是唯一的业务真源；Worker 无状态，只执行并提交候选；Redis、ES、SSE 都是可重建的投影或传输面")
    fe = d.box(40, 92, 1320, "前端工作台 · React 19 + TypeScript + Vite", [
        "资料库、问答 / 精读 / Wiki、深度研究、产物工作室、记忆与上下文检查器；HTTPS /api 请求，SSE 接收回答流与任务事件"],
        "slate", h=64, center=True)
    d.lane(24, 180, 1352, 254, "Backend · Java 17 · Spring Boot 3.3", "indigo", "业务真源与协调面")
    mods = [
        ("资料接入", ["分片上传、阶段任务", "撤销墓碑与异步清理"]),
        ("问答与精读", ["BM25 + kNN + RRF + Rerank", "资料级召回、连续原文窗口"]),
        ("Wiki 知识库", ["BM25F、链接传播", "页面关系与来源回链"]),
        ("深度研究协调", ["研究表、分层 Verifier", "局部重规划、检查点"]),
        ("Context 与 Memory", ["话题窗口、增量摘要", "门控晋升、控制包编译"]),
        ("流式回答", ["回合提交、渐进改写", "Redis Stream、SSE 续读"]),
        ("产物宿主", ["Skill 目录、并发准入", "候选验收与版本"]),
        ("任务与发件箱", ["可靠投递、幂等去重", "配额、限流与租约"]),
    ]
    for i, (title, lines) in enumerate(mods):
        x = 44 + (i % 4) * 330
        y = 220 + (i // 4) * 104
        d.box(x, y, 312, title, lines, "indigo", h=88)
    d.arrow([(700, fe.y + fe.h), (700, 180)], "HTTPS / SSE", label_dx=50, label_dy=14)
    stores = [
        ("MySQL 8.4", ["业务真源、审计账本", "Flyway 迁移"], "slate"),
        ("Redis 7.2", ["缓存、限流、ZSET 租约", "撤销墓碑、回答 Stream"], "rose"),
        ("Kafka", ["资料阶段、研究、产物", "摘要与记忆抽取"], "amber"),
        ("Elasticsearch 8.15", ["BM25 + kNN 检索索引", "记忆向量索引"], "sky"),
        ("MinIO", ["原件、快照", "产物文件"], "teal"),
    ]
    for i, (title, lines, theme) in enumerate(stores):
        d.box(40 + i * 268, 458, 248, title, lines, theme, h=86)
    d.arrow([(700, 434), (700, 458)])
    d.lane(24, 568, 1352, 150, "Python Worker 与 MCP 服务", "amber", "无状态执行面，结果以候选形式交回 Backend")
    workers = [
        ("Research Worker", ["按角色执行研究子任务", "检索、抓取、抽取、本地校验"]),
        ("Artifact Worker", ["取材、规划、写作、校验、渲染", "导出 Markdown / PPTX / PDF"]),
        ("Parse Worker", ["Docling 文档转换", "扫描页按需 OCR"]),
        ("media-mcp", ["B 站解析、字幕、转写", "抓帧、时间轴、OCR"]),
    ]
    for i, (title, lines) in enumerate(workers):
        d.box(44 + i * 330, 608, 312, title, lines, "amber", h=88)
    d.arrow([(700, 544), (700, 568)], "命令消息", label_dx=46, label_dy=14)
    d.box(40, 742, 1320, "外部服务", [
        "OpenAI 兼容大模型（聊天、研究、产物各自配置）、Embedding、Rerank、Serper 联网检索、Bilibili（yt-dlp）"],
        "slate", h=64, center=True)
    return d


BUILDERS = [research_agent, knowledge_chains, revocation, context_assembly, context_compression,
            memory_write, memory_read, source_pipeline, answer_streaming, artifact_framework,
            architecture_overview]


def main() -> int:
    OUT.mkdir(parents=True, exist_ok=True)
    warnings = []
    for build in BUILDERS:
        diagram = build()
        (OUT / f"{diagram.name}.svg").write_text(diagram.render(), encoding="utf-8")
        warnings.extend(diagram.warnings)
        print(f"wrote {diagram.name}.svg")
    for warning in warnings:
        print("WARN", warning)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
