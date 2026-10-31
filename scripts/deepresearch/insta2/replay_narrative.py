"""离线重放成文并保存 Worker 生成的报告，供后端校验逐句比对。

用法（在 worker 容器内）：python replay_narrative.py <输出目录> <次数> < synthesis_context.json
"""
import json
import sys
from types import SimpleNamespace

from app.deep_cell_executor import _build_execution_llm
from app.narrative_report import NarrativeReportPolisher


def main(out_dir, times):
    context = json.loads(sys.stdin.read())
    synthesis_input = context["query_policy"]["synthesis_input"]
    for index in range(times):
        llm = _build_execution_llm(lambda: None, SimpleNamespace(temperature=0.2, max_llm_calls=2))
        result = NarrativeReportPolisher(llm).polish(synthesis_input)
        claims = [{"claim_text": str(cell["candidate_value"]), "cell_key": cell["cell_key"],
                   "evidence_keys": sorted(set(cell["evidence_keys"])), "guarded": bool(cell.get("guarded", False))}
                  for cell in sorted(synthesis_input["cells"], key=lambda item: str(item["cell_key"]))]
        with open(f"{out_dir}/narrative-{index}.json", "w", encoding="utf-8") as handle:
            json.dump({"synthesis_input": synthesis_input, "narrative": result.narrative, "markdown": result.markdown,
                       "claims": claims, "mode": result.mode}, handle, ensure_ascii=False)
        print(index, result.mode, result.reason)


if __name__ == "__main__":
    main(sys.argv[1], int(sys.argv[2]))
