"""固定顺序的五个生成阶段。"""

import json
from pathlib import Path


def call_model(client, skill, stage, inputs, output_schema):
    prompt = (Path(__file__).resolve().parents[1] / "prompts" / skill.kind / f"{stage}.md").read_text(encoding="utf-8")
    raw = client.complete_json(f"artifact.node.pipeline.{stage}", {
        "instruction": prompt + "\n" + skill.instructions(),
        "inputs": inputs,
        "output_schema": output_schema,
    })
    try:
        value = json.loads(raw)
    except (TypeError, ValueError) as exc:
        raise ValueError(f"{stage} 阶段没有返回合法 JSON") from exc
    if not isinstance(value, dict):
        raise ValueError(f"{stage} 阶段必须返回 JSON 对象")
    return value
