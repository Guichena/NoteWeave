"""宿主机侧：取比较题里 point_evidence.py 判为 NONE 的缺失要点，连同题目和各研究对象名，写成 vs_probe.py 的输入。

用法：python3 vs_input.py <输出文件> <point_evidence 输出>:<gather_synth 输出>...
"""
import json
import re
import sys


def main(out_path, pairs):
    cases = {}
    for pair in pairs:
        evidence_path, synth_path = pair.split(":", 1)
        synth = {(item["label"], item["case"]): item for item in json.load(open(synth_path, encoding="utf-8"))}
        for line in open(evidence_path, encoding="utf-8"):
            match = re.match(r"(\S+)\s+(\S+)\s+NONE\s+(.+?) \|", line)
            if not match or "cmp-" not in match.group(2):
                continue
            label = "final-" + match.group(1)
            item = next(value for (lab, case), value in synth.items() if lab == label and case.startswith(match.group(2)))
            entry = cases.setdefault(item["case"], {
                "case": item["case"], "question": item["synthesis_input"]["question"],
                "entities": sorted({cell.get("entity_label", "") for cell in item["synthesis_input"].get("cells") or []
                                    if cell.get("entity_label")}),
                "points": []})
            if match.group(3) not in [point["point"] for point in entry["points"]]:
                entry["points"].append({"point": match.group(3), "label": label})
    json.dump(list(cases.values()), open(out_path, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print("cases", len(cases), "points", sum(len(case["points"]) for case in cases.values()))


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2:])
