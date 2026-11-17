"""冻结用户提供的留出集；封存评分要求，防止与开发题重复或运行后修改。"""
import argparse
import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def question_hash(text):
    return hashlib.sha256(" ".join(text.split()).casefold().encode()).hexdigest()


def freeze(dataset, development, destination):
    data = json.loads(dataset.read_text(encoding="utf-8"))
    dev = json.loads(development.read_text(encoding="utf-8"))
    cases = data.get("cases", [])
    hashes = [question_hash(case["question"]) for case in cases]
    dev_hashes = {question_hash(case["question"]) for case in dev["cases"]}
    categories = {case["question_type"] for case in cases}
    category_counts = {category:sum(case["question_type"] == category for case in cases) for category in categories}
    if len(cases) < 20 or len(set(hashes)) != len(cases) or set(hashes) & dev_hashes or categories != {"COMPARISON","SURVEY","FACT","CONFLICT"} or min(category_counts.values(),default=0) < 4:
        raise ValueError("留出集至少需要四类共 20 道不重复的新题，且不得与开发题相同")
    if any(not case.get("case_key") or not case.get("must_cover") for case in cases):
        raise ValueError("每道留出题必须具有唯一编号和预先确定的评分要求")
    if len({case["case_key"] for case in cases}) != len(cases):
        raise ValueError("留出题编号不能重复")
    manifest = {"schema_version": "research-holdout-manifest.v1", "state": "FROZEN",
        "frozen_at": datetime.now(timezone.utc).isoformat(), "dataset_sha256": digest(dataset),
        "development_sha256": digest(development), "case_keys": [case["case_key"] for case in cases],
        "question_hashes": hashes, "categories": sorted(categories), "category_counts":category_counts,"used_for_tuning": False}
    destination.parent.mkdir(parents=True, exist_ok=True)
    with destination.open("x", encoding="utf-8") as handle:
        json.dump(manifest, handle, ensure_ascii=False, indent=2)


def verify(dataset, path, *, development=None):
    manifest = json.loads(path.read_text(encoding="utf-8"))
    if (manifest.get("schema_version") != "research-holdout-manifest.v1" or manifest.get("state") != "FROZEN"
            or manifest.get("used_for_tuning") is not False or manifest.get("dataset_sha256") != digest(dataset)
            or development is not None and manifest.get("development_sha256") != digest(development)):
        raise ValueError("留出集已变更、参与调优或未按要求冻结，不能用于正式验收")
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", type=Path, required=True)
    parser.add_argument("--development-dataset", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--verify", action="store_true")
    args = parser.parse_args()
    if args.verify:
        verify(args.dataset, args.manifest, development=args.development_dataset)
    else:
        freeze(args.dataset, args.development_dataset, args.manifest)
    print("留出集清单核验通过" if args.verify else "留出集清单已冻结")


if __name__ == "__main__":
    main()
