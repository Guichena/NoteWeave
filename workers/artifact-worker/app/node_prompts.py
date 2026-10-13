"""节点提示词配方注册表；配方不携带宿主权限或完整运行状态。"""

from dataclasses import dataclass


@dataclass(frozen=True)
class NodePromptRecipe:
    recipe_id: str
    purpose: str
    instruction: str


_INSTRUCTIONS = {
    "statement_support_judge": (
        "逐条判断输入 statements 是否被其 citation_chunk_ids 对应的原文充分支持。"
        "只使用本次 material_passages，禁止引入其他章节、常识补充或新的引用。"
        "返回 assessments，逐陈述包含 statement_id、label、sufficiently_supported、reason 和 citations。"
        "陈述 label 使用 SUPPORTED、CONTRADICTED、UNSUPPORTED、NON_FACTUAL、UNDETERMINED；"
        "只有 SUPPORTED 的 sufficiently_supported 为 true，其他标签为 false。"
        "每条陈述必须覆盖原有全部引用，不能新增、遗漏或重复。"
        "每条 citation 包含 chunk_id、label、quote，label 允许 SUPPORTED、PARTIAL、CONTRADICTED、"
        "UNSUPPORTED、UNDETERMINED；quote 必须逐字复制对应原文中的连续片段。"
        "词语相似不表示含义相同，须检查否定、数字、单位、主体、范围和因果关系。"
        "单引用 PARTIAL 表示只支持部分内容，不能据此认定整个陈述充分支持。"
        "没有真实充分引用时不能返回 SUPPORTED；非事实性指令、问题或建议可返回 NON_FACTUAL。"
    ),
    "section_repair": (
        "只修复 inputs.failed_sections 中明确指定的失败章节，返回 sections。"
        "不能返回、改写或删除其他章节，不改变标题集合和原冻结执行意图。"
        "仅根据声明的失败检查与已提供素材修复，不引入新的事实、数字或引用。"
        "简历亮点修复返回的 bullets 仍只能引用既有 facts 的 fact_id，"
        "bullets 文本与对应亮点章节列表逐条一致，不得新增事实或指标。"
        "材料不足时保持可追溯的实际信息，不按数量编造内容。"
    ),
    "resume_highlight_extractor": (
        "从 material_passages 的原文抽取与 generation_context 相关的真实事实。"
        "返回 facts，每项包含唯一 fact_id、事实 text、真实 chunk_id 和连续原文 quote。"
        "quote 必须逐字复制对应片段，不能拼接、改写或把用户要求当成事实。"
        "材料不足时返回实际找到的事实，不能为了数量补造事实。"
    ),
    "resume_impact_normalizer": (
        "整理输入 facts，返回 normalized_facts。保留 fact_id、chunk_id、quote，不能新增事实。"
        "text 可改善表达；metrics 为原文中实际出现的数字指标字符串。"
        "没有指标时 metrics 返回空数组，不推算、换单位或补造数字。"
    ),
    "resume_bullet_writer": (
        "仅依据 normalized_facts 写简历要点，返回 bullets 和 sections。"
        "每条 bullet 包含 text 和非空 fact_ids，只引用输入中的事实。"
        "数字和指标必须来自引用事实的 quote，不能因用户期待而增加结果或贡献。"
        "按 generation_context.outline 输出章节，简历亮点章节按顺序逐条使用 bullets.text，"
        "每行以 '- ' 开头。各章节 source_refs 仅使用被引用事实片段的资料 title，"
        "简历亮点章节须包含所有要点的资料 title。材料不足时保持实际内容，不能补数量。"
    ),
    "generic_section_writer": (
        "依据 generation_context 和按章节分配的素材生成 sections。"
        "严格遵循 outline、提示词配方和输出契约，事实只取自声明的 material_passages。"
        "每章只使用 section_materials 中为该章分配的片段与长度，source_refs 使用资料 title。"
    ),
    "quiz_designer": (
        "依据 generation_context 和章节素材生成有资料依据的测验 sections，"
        "题目、选项与答案必须互相一致，并遵循声明的结构和难度要求。"
        "source_refs 仅使用给定资料 title，不能编造资料之外的结论。"
    ),
    "quiz_difficulty_normalizer": (
        "仅调整输入 sections 中确有必要的题目难度和表达，保留题目与答案的资料依据。"
        "遵循 generation_context 的结构、难度和引用要求，不补造题目数量或事实。"
    ),
    "wiki_structure_enforcer": (
        "依据给定 sections 和 generation_context 整理 Wiki 章节。"
        "遵循主题定义、边界与关联资料要求，不加入固定产品术语或输入之外的事实。"
    ),
    "style_polisher": (
        "依据 generation_context 调整输入 sections 的表达、术语和格式。"
        "保留事实、数字、结构与资料引用，不增加资料之外的内容。"
    ),
}

NODE_PROMPT_RECIPES = {
    f"node.{skill_key}.v1": NodePromptRecipe(
        recipe_id=f"node.{skill_key}.v1",
        purpose=f"artifact.node.{skill_key}",
        instruction=instruction,
    )
    for skill_key, instruction in _INSTRUCTIONS.items()
}


def resolve_node_prompt_recipe(recipe_id: str) -> NodePromptRecipe:
    try:
        return NODE_PROMPT_RECIPES[recipe_id]
    except KeyError as exc:
        raise ValueError(f"节点提示词配方未注册：{recipe_id}") from exc
