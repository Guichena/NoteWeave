"""生成 docs/context-design.md 用的配图，输出到 docs/images/context-doc-*.svg。

运行：python scripts/diagrams/build_context_doc_diagrams.py
示例是一段按设计编写的科研调研对话：用户在写长上下文压缩的综述，前 4 轮梳理三篇论文，
中间插问 3 轮综述的引用格式，第 8 轮回到论文，追问早先回答里的具体数字。论文名与数字都是示例。
"""
from __future__ import annotations

import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from svgkit import Diagram, THEMES, text_width  # noqa: E402

OUT = pathlib.Path(__file__).resolve().parents[2] / "docs" / "images"

CARD_FILL = {
    "slate": "#f1f5f9",
    "sky": "#eff6ff",
    "amber": "#fff7ed",
    "emerald": "#ecfdf5",
    "violet": "#f5f3ff",
}
BAR = {
    "slate": "#94a3b8",
    "sky": "#2563eb",
    "amber": "#ea580c",
    "emerald": "#10b981",
    "violet": "#7c3aed",
}
INK = {
    "slate": "#334155",
    "sky": "#1e40af",
    "amber": "#9a3412",
    "emerald": "#065f46",
    "violet": "#5b21b6",
}

TOPIC_A = "长上下文压缩论文梳理"
TOPIC_B = "综述的引用格式"
QUESTION = "好。回到那几篇论文，乙在 8 倍压缩下准确率到底掉了多少来着？"
STANDALONE = "论文乙摘要式压缩在 8 倍压缩率下的准确率下降幅度"

# (序号, 角色, 话题, 文本, 话题判断, 判断配色)
CHAT = [
    (1, "用户", "A", "我在写一篇长上下文压缩的综述，传了三篇论文，帮我先捋一下各自用的什么方法，压缩了多少，效果咋样",
     "新话题（首条消息）", "emerald"),
    (2, "助手", "A", "甲是 prompt pruning，压缩 4 倍；乙是摘要式压缩，8 倍，LongBench 从 46.3 降到 44.2；丙是 KV cache……",
     None, None),
    (3, "用户", "A", "多跳问答那部分呢，哪篇掉得最少？对了，后面术语都保留英文就行，不用翻", "接着当前话题  + 1 条约束", "slate"),
    (4, "助手", "A", "乙掉得最少，HotpotQA 上只降了 1.3；丙最多，降了 6.8，主要是 KV cache 丢了跨段的信息……",
     None, None),
    (5, "用户", "A", "它们实验用的什么模型？", "接着当前话题", "slate"),
    (6, "助手", "A", "都是 7B 级：甲和丙用 Llama-2-7B，乙用 Mistral-7B，所以横向比较时要注明基座不同……", None, None),
    (7, "用户", "A", "行，帮我列个对比表的提纲，按方法、压缩率、效果、开销这几列", "接着当前话题", "slate"),
    (8, "助手", "A", "提纲：方法类别 / 压缩率 / LongBench 与多跳问答 / 额外开销……丙的显存数据论文里没给全，需要再核对",
     None, None),
    (9, "用户", "B", "诶另外问一下，中文综述的参考文献一般用 GB/T 7714 还是 APA？", "新话题", "emerald"),
    (10, "助手", "B", "中文期刊和学位论文一般按 GB/T 7714 著录，APA 多见于心理学和英文期刊……", None, None),
    (11, "用户", "B", "那是按作者排还是按出现顺序排？", "接着当前话题", "slate"),
    (12, "助手", "B", "综述常用顺序编码制，按正文里首次引用的顺序编号，不按作者排……", None, None),
    (13, "用户", "B", "英文文献混在里面怎么写，要翻译成中文吗？", "接着当前话题", "slate"),
    (14, "助手", "B", "不用翻译，按原文语种著录，作者姓在前、名字缩写……", None, None),
    (15, "用户", "A", QUESTION, "回到论文梳理  + 需要细节", "amber"),
]


def card_height(lines) -> int:
    return 44 + 23 * len(lines)


def card(d: Diagram, x, y, w, theme, title, lines, badge=None, colored=()):
    """左侧色条的卡片，模仿上下文窗口示意图；colored 里的行号用主题色书写。"""
    h = card_height(lines)
    d.rect(x, y, w, h, CARD_FILL[theme], CARD_FILL[theme], radius=10, width=0)
    d.rect(x, y, 6, h, BAR[theme], BAR[theme], radius=3, width=0)
    d.text(x + 24, y + 29, title, size=16.5, weight=700, fill="#0f172a")
    limit = w - 48
    if badge:
        bw = text_width(badge, 12.5) + 26
        d.rect(x + w - bw - 16, y + 12, bw, 26, "#ffffff", "#e2e8f0", radius=13, width=1)
        d.text(x + w - bw / 2 - 16, y + 29.5, badge, size=12.5, weight=700, fill=INK[theme],
               anchor="middle")
        d._check(title, 16.5, limit - bw - 16, title + "（含标签）")
    else:
        d._check(title, 16.5, limit, title)
    base = y + 55
    for i, line in enumerate(lines):
        d.text(x + 24, base, line, size=13.5, fill=INK[theme] if i in colored else "#475569")
        d._check(line, 13.5, limit, title)
        base += 23
    return h


# ---------------------------------------------------------------- 全局图：三类信息的来源、保留时长与去向
def overall() -> Diagram:
    d = Diagram("context-doc-overall", 1400, 860, "整体：三类信息各自从哪来、保留多久、怎样进入一次模型调用",
                "事实每轮从资料重新检索；会话内的对话用来理解追问；跨会话只保留表达偏好")
    bands = [
        ("violet", "每轮重新获取 · 事实来源", 100,
         ("资料：用户上传的三篇论文", ["解析、切块后写入 Elasticsearch", "BM25 与向量检索，重排后取片段"]),
         ("按本轮独立查询检索", ["#15 的查询：论文乙 8 倍压缩下", "准确率的下降幅度"]),
         ("编号证据 [1]", ["论文乙 · 表 2：44.2，基线 46.3", "回答里的事实只能引用这里"])),
        ("sky", "会话内保留 · 理解追问", 290,
         ("消息原文与话题", ["15 条消息按序号存在 MySQL", "改写模型判断每条消息的话题"]),
         ("后台维护 + 冻结时规划", ["话题摘要经 Kafka 增量生成", "约束抽取成整表快照", "规划器按目标话题挑选"]),
         ("历史话题块与持续约束", ["论文话题：完整摘要 + 回放 #1-#2", "引用格式话题：两条结论 + 保底原文",
                             "约束：术语保留英文"])),
        ("emerald", "跨会话保留 · 决定表达方式", 480,
         ("长期记忆", ["从多次对话里抽出的表达要求", "限 8 个槽位，通过门控才生效"]),
         ("按问题编译", ["风格、结构类每轮都带", "术语类按相关度选入", "结果按版本缓存在 Redis"]),
         ("受控表达参数", ["先给结论再列要点", "对比类问题优先用表格"])),
    ]
    outputs = []
    for theme, label, y, src, proc, out in bands:
        d.lane(24, y, 990, 170, label, theme)
        b1 = d.box(44, y + 40, 290, src[0], src[1], theme, h=114)
        b2 = d.box(374, y + 40, 290, proc[0], proc[1], theme, h=114)
        b3 = d.box(704, y + 40, 290, out[0], out[1], theme, h=114)
        d.arrow([b1.right(), b2.left()])
        d.arrow([b2.right(), b3.left()])
        outputs.append(b3)
    # 右侧：一次模型调用的输入
    d.rect(1044, 100, 332, 550, "#ffffff", "#cbd5e1", radius=14, width=1.4)
    d.text(1064, 130, "一次模型调用的输入", size=15, weight=700, fill="#0f172a")
    blocks = [
        ("slate", "系统提示"), ("sky", "持续约束"), ("sky", "历史话题块"),
        ("violet", "资料片段"), ("emerald", "表达参数"), ("slate", "当前问题与独立查询"),
    ]
    centers = {}
    by = 148
    for theme, name in blocks:
        d.rect(1064, by, 292, 62, CARD_FILL[theme], CARD_FILL[theme], radius=8, width=0)
        d.rect(1064, by, 5, 62, BAR[theme], BAR[theme], radius=2, width=0)
        d.text(1084, by + 37, name, size=14, weight=700, fill="#0f172a")
        centers[name] = by + 31
        by += 80
    d.arrow([outputs[0].right(), (1030, outputs[0].cy), (1030, centers["资料片段"]), (1064, centers["资料片段"])],
            color="violet")
    d.arrow([outputs[1].right(), (1022, outputs[1].cy), (1022, centers["历史话题块"]), (1064, centers["历史话题块"])],
            color="sky")
    d.arrow([outputs[2].right(), (1038, outputs[2].cy), (1038, centers["表达参数"]), (1064, centers["表达参数"])],
            color="emerald")
    # 底部：两次会话之间带过去什么
    ty = 690
    d.text(44, ty, "两次会话之间带过去什么", size=15, weight=700, fill="#0f172a")
    s1 = d.box(44, ty + 20, 400, "会话一 · 第一天", ["梳理三篇论文，插问引用格式，", "回到论文追问准确率（#1-#15）"],
               "slate", h=90)
    s2 = d.box(956, ty + 20, 420, "会话二 · 第三天", ["新开对话：把上次的对比表", "扩写成综述里的一段正文"],
               "slate", h=90)
    d.arrow([(444, ty + 42), (956, ty + 42)], "长期记忆：术语保留英文、先给结论（通过门控后）", color="emerald")
    d.arrow([(444, ty + 66), (956, ty + 66)], "论文资料：每轮按新问题重新检索", color="violet")
    d.arrow([(444, ty + 90), (956, ty + 90)], "对话原文、话题摘要、会话约束：留在会话一，不带过去", color="slate",
            dashed=True)
    return d


# ---------------------------------------------------------------- 图零：对话全貌
def chat_overview() -> Diagram:
    row, gap = 40, 8
    top = 112
    height = top + len(CHAT) * (row + gap) + 70
    d = Diagram("context-doc-chat", 1200, height, "示例对话：写综述时梳理论文，中间插问引用格式，再回到论文",
                "左侧色条表示消息所属话题；右侧是改写模型对每条用户消息的话题判断")
    d.legend(150, 98, [("sky", f"话题：{TOPIC_A}"), ("amber", f"话题：{TOPIC_B}")])
    y = top
    for seq, role, topic, text, route, theme in CHAT:
        bar = BAR["sky"] if topic == "A" else BAR["amber"]
        user = role == "用户"
        d.text(44, y + 25, f"#{seq}", size=13, weight=700, fill="#64748b")
        d.text(88, y + 25, role, size=13, weight=700 if user else 400, fill="#0f172a" if user else "#64748b")
        fill = "#ffffff" if user else "#f8fafc"
        stroke = "#cbd5e1" if user else "#f1f5f9"
        highlight = seq == 15
        if highlight:
            fill, stroke = "#fff7ed", "#fdba74"
        d.rect(140, y, 760, row, fill, stroke, radius=8, width=1.1)
        d.rect(140, y, 5, row, bar, bar, radius=2, width=0)
        d.text(158, y + 25, text, size=13, fill="#0f172a" if user else "#64748b",
               weight=700 if highlight else 400)
        d._check(text, 13, 730, f"#{seq}")
        if route:
            d.pill(920, y + 8, route, theme, size=12)
        y += row + gap
    d.text(600, y + 26, "#11 省略了主语，#15 隔了 3 轮才回到旧话题、还要早先的具体数字：这两类判断交给改写模型。",
           size=13, fill="#475569", anchor="middle")
    return d


# ---------------------------------------------------------------- 图一：一次回答的上下文
def context_window() -> Diagram:
    cards = [
        ("slate", "系统提示：公共规则 + 问答模式规则",
         ["事实只来自下方的资料片段；对话内容、摘要和记忆只用来理解追问、决定表达方式。"], None, ()),
        ("slate", "会话持续约束",
         ["生效要求：术语保留英文原文，不翻译（用户在第 3 条提出）。未经核实的前提：无。"],
         "持续约束", ()),
        ("sky", f"话题块 · {TOPIC_A}（第 1-8 条）  目标话题：回到",
         ["目标：为综述比较三篇论文的压缩方法，整理成对比表。",
          "已定结论：甲 prompt pruning 压缩 4 倍，乙摘要式压缩 8 倍，丙 KV cache 压缩 16 倍〔#2〕；多跳问答上乙下降最少〔#4〕；",
          "实验模型均为 7B 级〔#6〕；对比表按方法、压缩率、效果、开销四列〔#8〕。未决问题：丙的显存开销还没核对。"],
         "Spine · 目标话题展开", ()),
        ("amber", "回放原文 · 第 1-2 条（属于论文梳理话题）",
         ["ReFold 出处：问题要具体数字，命中要点〔#2〕，按序号读出这一组问答的原文。",
          "用户：我在写一篇长上下文压缩的综述，传了三篇论文，帮我先捋一下各自用的什么方法，压缩了多少，效果咋样",
          "助手：……乙是摘要式压缩，8 倍，LongBench 平均准确率从 46.3 降到 44.2……"],
         "ReFold · 按出处回放", (0,)),
        ("amber", f"话题块 · {TOPIC_B}（第 9-14 条）  其他话题：折叠",
         ["Spine 短结果：中文综述按 GB/T 7714 著录〔#10〕；顺序编码制按正文首次引用的顺序编号〔#12〕。",
          "保底原文 · 第 13-14 条：用户问英文文献要不要翻译；助手答不用翻译，按原文语种著录。"],
         "Spine · 折叠为结论", (0,)),
        ("violet", "资料片段（编号证据）",
         ["[1] 论文乙 · 表 2：8 倍压缩时 LongBench 平均准确率 44.2，未压缩基线 46.3。"],
         "事实来源", ()),
        ("emerald", "受控表达参数（由长期记忆编译）",
         ["结构：先给结论再列要点；对比类问题优先用表格。只调节表达方式，不提供事实。"],
         "ACE 式 · 偏好复用", ()),
        ("slate", "当前问题（第 15 条）",
         [QUESTION, "改写给出的独立查询：" + STANDALONE], None, ()),
    ]
    gap = 14
    inner = sum(card_height(c[2]) for c in cards) + gap * (len(cards) - 1)
    frame_y = 104
    frame_h = 52 + inner + 74
    height = frame_y + frame_h + 140
    d = Diagram("context-doc-window", 1200, height, "NoteWeave · 一次回答的上下文",
                "示例：第 15 条消息回到论文梳理话题，追问早先回答里的具体数字  |  只展示模型这一轮看到的内容")
    d.rect(48, frame_y, 1104, frame_h, "#ffffff", "#cbd5e1", radius=16, width=1.4)
    d.text(76, frame_y + 34, "上下文窗口（冻结快照，截止第 15 条消息）", size=16, weight=700, fill="#0f172a")
    y = frame_y + 52
    for theme, title, lines, badge, colored in cards:
        # 回放原文缩进，表示挂在目标话题块下面
        x, w = (100, 1028) if title.startswith("回放原文") else (72, 1056)
        y += card(d, x, y, w, theme, title, lines, badge, colored) + gap
    d.text(76, y + 20, "历史按话题就地排列：目标话题给完整摘要和回放原文，其他话题只给结论，最近一组原文保底。",
           size=14, weight=700, fill="#0f172a")
    d.text(76, y + 44, "〔#n〕出处标注、按话题分块排列和按出处回放属于本轮设计，代码尚未实现；其余层为现有实现。",
           size=12.5, fill="#b45309")
    oy = frame_y + frame_h + 22
    d.rect(48, oy, 1104, 96, "#f1f5f9", "#f1f5f9", radius=12, width=0)
    d.text(76, oy + 36, "窗口之外", size=15.5, weight=700, fill="#0f172a")
    d.text(190, oy + 36, "全部消息原文（MySQL，按序号）、各版本话题摘要、长期记忆与历史版本：保存在库里，不全部发给模型。",
           size=14, fill="#334155")
    d.text(190, oy + 64, "回放原文 = 按序号读出当时保存的消息，不重新生成，也不重新检索资料。", size=13, fill="#64748b")
    return d


# ---------------------------------------------------------------- 图二：一轮对话的处理顺序
def turn_flow() -> Diagram:
    d = Diagram("context-doc-turn", 1400, 640, "一轮对话的处理顺序",
                "改写模型先给出话题判断，读到后立即冻结上下文；摘要、约束和记忆在回答结束后由后台更新")
    d.lane(24, 96, 1352, 262, "用户等待的路径", "indigo", "话题判断随改写一起流式输出，不增加模型调用次数")
    xs = [44, 310, 576, 842, 1108]
    w = 248
    b1 = d.box(xs[0], 140, w, "用户消息 #15", ["好。回到那几篇论文，", "乙在 8 倍压缩下准确率", "到底掉了多少来着？"],
               "slate", h=196)
    b2 = d.box(xs[1], 140, w, "查询改写（流式）", ["输入：最近 8 条原文和", "话题列表，前 3 个附摘要",
                                                "先出 r：回到论文梳理话题", "再出 d=1：需要早先细节",
                                                "最后出 s：补全主题的独立查询"], "violet", tag="模型", h=196)
    b3 = d.box(xs[2], 140, w, "冻结上下文", ["读到 r 就冻结；d=1 时", "多等 s，用它定位回放原文",
                                          "按 r 落库本条消息的话题", "规划原文尾部、摘要、段尾",
                                          "与回放，快照写入 MySQL"], "indigo", h=196)
    b4 = d.box(xs[3], 140, w, "检索与记忆编译", ["按独立查询检索论文片段", "编译受控表达参数",
                                             "编译结果按策略版本和", "记忆状态指纹缓存在 Redis"], "sky", h=196)
    b5 = d.box(xs[4], 140, w, "生成回答（流式）", ["事实只来自资料片段", "回答属性记下回放的",
                                              "消息序号，界面据此显示", "对话依据并支持跳转"], "emerald",
               tag="模型", h=196)
    for a, b in [(b1, b2), (b2, b3), (b3, b4), (b4, b5)]:
        d.arrow([a.right(), (b.x, a.cy)])
    d.lane(24, 396, 1352, 176, "回答结束后，后台异步执行（Kafka）", "amber", "不占用户等待时间，下一轮读取已就绪的结果")
    bw = 316
    bx = [44, 378, 712, 1046]
    d.box(bx[0], 438, bw, "会话约束抽取", ["模型把用户消息转成增删操作", "纯函数折叠成整表快照"], "amber", h=110)
    a2 = d.box(bx[1], 438, bw, "话题摘要增量更新", ["在上一版上只读新增消息", "按固定小节输出，要点带出处",
                                                "每一版不可变，记下覆盖范围"], "amber", h=110)
    d.box(bx[2], 438, bw, "记忆候选抽取", ["限定 8 个表达方式槽位", "冲突检查加四项门控"], "amber", h=110)
    d.box(bx[3], 438, bw, "话题边界复核", ["只针对规则切分的不确定边界", "每 30 秒交给模型批量复核"],
          "amber", h=110)
    d.arrow([b5.bottom(), (b5.cx, 382), (a2.cx, 382), (a2.cx, a2.y)], "回答完成", color="amber",
            label_at=((a2.cx + b5.cx) / 2, 382))
    d.arrow([(a2.cx - 60, a2.y), (a2.cx - 60, 372), (b3.cx, 372), b3.bottom()], "下一轮读取：落后 8 条以内用原文补齐",
            color="amber", dashed=True, label_at=((a2.cx - 60 + b3.cx) / 2 - 20, 372), label_dy=-6)
    d.text(700, 606, "不需要细节时，读到话题编号就冻结，不等独立查询；需要细节时才多等这一步，用独立查询定位回放原文。",
           size=13, fill="#475569", anchor="middle")
    return d


# ---------------------------------------------------------------- 图三：话题摘要与按出处回放
def summary_replay() -> Diagram:
    d = Diagram("context-doc-replay", 1400, 700, "话题摘要带出处，需要细节时按出处回放原文",
                "摘要是折叠后的短结果，每条要点记下来源消息序号；原文一直在库里，回放只按序号读取")
    d.lane(24, 96, 360, 568, "会话消息（MySQL）", "slate")
    rows = [
        ("sky", "#1-#2", "方法、压缩率和效果"),
        ("sky", "#3-#4", "多跳问答谁掉得少"),
        ("sky", "#5-#6", "实验用的什么模型"),
        ("sky", "#7-#8", "对比表提纲"),
        ("amber", "#9-#10", "GB/T 7714 还是 APA"),
        ("amber", "#11-#12", "按作者还是按出现顺序"),
        ("amber", "#13-#14", "英文文献要不要翻译"),
        ("slate", "#15", "乙准确率掉了多少来着"),
    ]
    y = 140
    for theme, seq, text in rows:
        fill, stroke, title, _ = THEMES[theme]
        d.rect(44, y, 320, 50, fill, stroke, radius=8, width=1.1)
        d.text(58, y + 31, seq, size=13, weight=700, fill=title)
        d.text(132, y + 31, text, size=13, fill="#334155")
        y += 62
    d.text(204, 640, "蓝色：论文梳理话题  橙色：引用格式话题", size=12, fill="#64748b", anchor="middle")
    d.lane(408, 96, 470, 568, "论文梳理话题的摘要", "sky", "Kafka 增量生成")
    v1 = d.box(428, 140, 430, "第 1 版 · 覆盖 #1-#4", ["由 #1-#4 生成"], "slate", h=70)
    d.box(428, 238, 430, "第 2 版 · 覆盖 #1-#8（当前就绪）", [
        "目标：为综述比较三篇论文，整理成对比表",
        "已定结论：甲 prompt pruning 4 倍，",
        "乙摘要式压缩 8 倍，丙 KV cache 16 倍〔#2〕",
        "多跳问答上乙下降最少〔#4〕",
        "实验模型均为 7B 级〔#6〕",
        "对比表按方法、压缩率、效果、开销四列〔#8〕",
        "未决问题：丙的显存开销还没核对"], "sky", h=196)
    d.arrow([v1.bottom(), (v1.cx, 238)], "只读新增的 #5-#8", label_dx=60, label_dy=14)
    d.box(428, 462, 430, "生成规则", [
        "按固定小节输出：目标、已定结论、",
        "关键数字与清单、未决问题",
        "每条要点末尾标注来源消息序号",
        "新内容与旧摘要冲突时以新内容为准"], "slate", h=124)
    d.text(643, 618, "小节、〔#n〕出处属于本轮设计，现有摘要为自由要点", size=12, fill="#b45309",
           anchor="middle")
    d.lane(902, 96, 474, 568, "需要细节时的两级定位", "amber", "只在目标话题内找")
    q = d.box(922, 140, 434, "改写结果", ["r：回到论文梳理话题    d=1：需要细节",
                                       "s：论文乙 8 倍压缩下的准确率下降幅度"], "violet", h=84)
    s1 = d.box(922, 254, 434, "① 在目标话题摘要的要点里匹配", ["命中：乙摘要式压缩 8 倍〔#2〕",
                                                           "摘要只留了压缩率，准确率数字已被折叠"], "amber", h=84)
    s2 = d.box(922, 368, 434, "② 按出处读原文", ["#2 所在的一组问答：#1 用户提问、#2 回答",
                                              "已在原文尾部的消息跳过"], "amber", h=84)
    s3 = d.box(922, 482, 434, "放进上下文（话题块下方）", ["最多 3 组问答，合计不超过 1500 token",
                                                     "快照记下入选原因：话题原文回放"], "emerald", h=84)
    for a, b in [(q, s1), (s1, s2), (s2, s3)]:
        d.arrow([a.bottom(), b.top()])
    d.text(1139, 606, "退路：没有命中要点，或旧摘要没有出处时，", size=12.5, fill="#9a3412", anchor="middle")
    d.text(1139, 626, "按独立查询与原文的词项重合度打分（现有实现）", size=12.5, fill="#9a3412", anchor="middle")
    d.arrow([(860, 300), (922, 296)], color="sky", dashed=True)
    return d


# ---------------------------------------------------------------- 图四：出处保留到界面
def ui_jump() -> Diagram:
    d = Diagram("context-doc-jump", 1400, 600, "回答下方显示对话依据，点击跳回原消息",
                "对话依据与资料证据分两行显示：资料证据说明事实从哪来，对话依据说明回答接的是之前哪句话")
    d.rect(40, 100, 660, 460, "#ffffff", "#cbd5e1", radius=14, width=1.4)
    d.text(64, 130, "对话界面（示意）", size=14.5, weight=700, fill="#0f172a")
    d.rect(64, 146, 560, 40, "#f1f5f9", "#f1f5f9", radius=10, width=0)
    d.text(80, 171, "#1 我在写长上下文压缩的综述，帮我捋一下三篇论文的方法、压缩率和效果", size=12.5,
           fill="#334155")
    d.rect(64, 196, 612, 58, "#fef3c7", "#f59e0b", radius=10, width=1.6)
    d.text(80, 220, "#2 甲是 prompt pruning，压缩 4 倍；乙是摘要式压缩，8 倍，", size=12.5, fill="#334155")
    d.text(80, 242, "LongBench 从 46.3 降到 44.2；丙是 KV cache……", size=12.5, fill="#334155")
    d.pill(560, 225, "跳转后高亮", "amber", size=11)
    d.text(80, 284, "……（第 3-14 条）", size=12.5, fill="#94a3b8")
    d.rect(196, 302, 480, 40, "#eef2ff", "#eef2ff", radius=10, width=0)
    d.text(210, 327, "#15 " + QUESTION, size=12.5, fill="#312e81")
    d.rect(64, 354, 612, 186, "#f8fafc", "#e2e8f0", radius=10, width=1)
    d.text(80, 382, "#16 掉了 2.1 个点：从 46.3 降到 44.2 [1]。", size=13.5, fill="#0f172a")
    d.text(80, 410, "这是 LongBench 平均准确率，对比的是同一模型不压缩的基线。", size=13, fill="#334155")
    d.text(80, 432, "对比表里这一格可以记为 -2.1。", size=13, fill="#334155")
    d.rect(80, 452, 580, 32, "#ffffff", "#c4b5fd", radius=8, width=1)
    d.text(94, 473, "资料证据  [1] 论文乙 · 表 2", size=12.5, fill="#5b21b6")
    d.rect(80, 494, 580, 32, "#ffffff", "#fdba74", radius=8, width=1)
    d.text(94, 515, "对话依据  第 1-2 条 · 你第一轮问到的压缩效果（点击跳转）", size=12.5, fill="#9a3412")
    d.arrow([(660, 510), (688, 510), (688, 225), (676, 225)], color="amber", width=1.8)
    steps = [
        ("indigo", "冻结快照", ["规划器记下每条入选原文的序号和原因", "原因为话题原文回放的就是对话依据"]),
        ("sky", "回答属性", ["回答完成时写入回放的消息序号", "重新生成沿用同一份快照，依据不变"]),
        ("amber", "前端渲染", ["每条消息按序号加锚点", "点击依据：滚动到该消息并短暂高亮"]),
        ("slate", "边界", ["只显示回放原文，不显示原文尾部", "被删除的消息显示为已删除，不可跳转"]),
    ]
    y = 104
    prev = None
    for theme, title, lines in steps:
        b = d.box(744, y, 616, title, lines, theme, h=96)
        if prev:
            d.arrow([prev.bottom(), b.top()])
        prev = b
        y += 116
    d.text(1052, 580, "整页内容属于本轮设计，代码尚未实现；快照里的入选记录与原因已经存在。", size=12.5,
           fill="#b45309", anchor="middle")
    return d


BUILDERS = [overall, chat_overview, context_window, turn_flow, summary_replay, ui_jump]


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
