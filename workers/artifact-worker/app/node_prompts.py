"""节点提示词配方注册表；配方不携带宿主权限或完整运行状态。"""

from dataclasses import dataclass


@dataclass(frozen=True)
class NodePromptRecipe:
    recipe_id: str
    purpose: str
    instruction: str


_INSTRUCTIONS = {
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
