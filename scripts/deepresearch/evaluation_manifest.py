"""固定评测输入和源码指纹；只记录允许公开的模型标识，不读取密钥。"""

from __future__ import annotations

import hashlib
import json
import os
import shutil
import subprocess
from datetime import datetime, timezone
from pathlib import Path


SOURCE_ROOTS = ("backend/src/main", "workers/research-worker/app", "frontend/src", "scripts/deepresearch")
SOURCE_SUFFIXES = {".java", ".py", ".sql", ".yaml", ".yml", ".ts", ".tsx", ".json", ".css", ".sh"}
CONFIG_FILES = ("backend/pom.xml", "backend/Dockerfile", "frontend/Dockerfile", "workers/research-worker/Dockerfile",
    "workers/research-worker/pyproject.toml", "workers/research-worker/uv.lock", "frontend/package.json", "frontend/pnpm-lock.yaml", "docker-compose.yml", ".env.example")
PUBLIC_MODEL_KEYS = ("NOTEWEAVE_RESEARCH_LLM_MODEL", "NOTEWEAVE_LLM_MODEL", "NOTEWEAVE_EVAL_JUDGE_MODEL")
PUBLIC_CONFIG_KEYS = (*PUBLIC_MODEL_KEYS, "NOTEWEAVE_RESEARCH_REQUIREMENT_COVERAGE_V1",
    "NOTEWEAVE_RESEARCH_REQUIREMENT_SHADOW_MODE", "NOTEWEAVE_RESEARCH_REQUIREMENT_FACT_ACCUMULATION",
    "NOTEWEAVE_RESEARCH_REQUIREMENT_DRIVEN_SCHEDULING", "NOTEWEAVE_RESEARCH_EVIDENCE_AUDIT_V1",
    "NOTEWEAVE_RESEARCH_WORKER_SYNTHESIS_V1", "NOTEWEAVE_RESEARCH_WIDE_DISCOVERY_V1",
    "NOTEWEAVE_EVAL_RUNTIME_CONTAINERS")


def runtime_fingerprint(root: Path) -> dict:
    """只保留镜像身份与明确允许公开的环境变量，绝不保存完整 inspect 输出。"""
    try:
        prefix = ["docker"]
        if os.name == "nt" and not shutil.which("docker") and shutil.which("wsl"):
            prefix = ["wsl", "-d", os.environ.get("NOTEWEAVE_EVAL_WSL_DISTRO", "Ubuntu-24.04"), "--", "docker"]
        explicit = os.environ.get("NOTEWEAVE_EVAL_RUNTIME_CONTAINERS", "").strip()
        if explicit:
            import re
            identities = explicit.split(",")
            if any(not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,127}", name) for name in identities):
                return {"state": "UNAVAILABLE", "containers": []}
        else:
            listing = subprocess.run([*prefix, "compose", "ps", "-q"], cwd=root,
                                     capture_output=True, text=True, timeout=15, check=False)
            identities = listing.stdout.split() if listing.returncode == 0 else []
        if not identities:
            return {"state": "UNAVAILABLE", "containers": []}
        result = subprocess.run([*prefix, "inspect", *identities], cwd=root,
                                capture_output=True, text=True, timeout=15, check=False)
        if result.returncode:
            return {"state": "UNAVAILABLE", "containers": []}
        containers = []
        for value in json.loads(result.stdout):
            env = dict(item.split("=", 1) for item in value.get("Config", {}).get("Env", []) if "=" in item)
            containers.append({"id": value["Id"], "image_id": value["Image"],
                "image_name": value.get("Config", {}).get("Image"), "started_at": value.get("State", {}).get("StartedAt"),
                "public_config": {key: env[key] for key in PUBLIC_CONFIG_KEYS if key in env},
                "role_options": public_role_options(env)})
        return {"state": "CAPTURED", "containers": sorted(containers, key=lambda item: item["id"])}
    except (OSError, subprocess.TimeoutExpired, ValueError, KeyError):
        return {"state": "UNAVAILABLE", "containers": []}


def public_role_options(env):
    try:
        raw = json.loads(env.get("NOTEWEAVE_RESEARCH_LLM_PURPOSE_OPTIONS", "{}"))
        return {purpose: {key: value for key,value in options.items()
                         if key in {"model", "temperature", "max_tokens", "max_attempts", "timeout_seconds", "thinking"}}
                for purpose,options in raw.items() if isinstance(purpose,str) and isinstance(options,dict)}
    except (ValueError, AttributeError):
        return {"state": "INVALID"}


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def source_fingerprint(root: Path) -> dict[str, str]:
    paths = {root / name for name in CONFIG_FILES if (root / name).is_file()}
    for name in SOURCE_ROOTS:
        directory = root / name
        if directory.is_dir():
            paths.update(path for path in directory.rglob("*") if path.is_file()
                         and (path.suffix in SOURCE_SUFFIXES or path.name == "Dockerfile") and "__pycache__" not in path.parts)
    return {path.relative_to(root).as_posix(): sha256(path) for path in sorted(paths)}


class EvaluationManifest:
    def __init__(self, root: Path, runs_dir: Path, dataset: Path, case_keys: list[str], *, attached: bool = False, holdout: Path | None = None):
        self.root = root
        self.dataset = dataset
        self.holdout = holdout
        self.path = runs_dir / "metadata" / "manifest.json"
        if self.path.exists():
            raise ValueError("结果目录已有运行清单，请新建结果目录；接回已有任务时使用其他目录保存")
        revision = subprocess.run(["git", "rev-parse", "HEAD"], cwd=root, capture_output=True,
                                  text=True, check=False)
        self.data = {
            "schema_version": "research-evaluation-manifest.v1",
            "started_at": datetime.now(timezone.utc).isoformat(),
            "git_head": revision.stdout.strip() if revision.returncode == 0 else None,
            "dataset_sha256": sha256(dataset),
            "holdout_sha256": sha256(holdout) if holdout else None,
            "case_keys": case_keys,
            "completed_case_keys": [],
            "source_hashes": source_fingerprint(root),
            "models": {key: os.environ[key] for key in PUBLIC_MODEL_KEYS if os.environ.get(key)},
            "runtime_identity": runtime_fingerprint(root),
            "public_config": {key: os.environ[key] for key in PUBLIC_CONFIG_KEYS if key in os.environ},
            "role_options": public_role_options(os.environ),
            "attached_run": attached,
            "state": "RUNNING",
        }
        self._save(initial=True)

    def verify(self) -> None:
        actual = source_fingerprint(self.root)
        expected = self.data["source_hashes"]
        changed = sorted(name for name in set(actual) | set(expected) if actual.get(name) != expected.get(name))
        dataset_changed = not self.dataset.is_file() or sha256(self.dataset) != self.data["dataset_sha256"]
        holdout_changed = self.holdout is not None and (not self.holdout.is_file() or sha256(self.holdout) != self.data["holdout_sha256"])
        models = {key: os.environ[key] for key in PUBLIC_MODEL_KEYS if os.environ.get(key)}
        runtime = runtime_fingerprint(self.root)
        config = {key: os.environ[key] for key in PUBLIC_CONFIG_KEYS if key in os.environ}
        role_options = public_role_options(os.environ)
        if changed or dataset_changed or holdout_changed or models != self.data["models"] or runtime != self.data["runtime_identity"] or config != self.data["public_config"] or role_options != self.data["role_options"]:
            self.data.update(state="INVALID", changed_sources=changed, dataset_changed=dataset_changed,
                             models_changed=models != self.data["models"], runtime_changed=runtime != self.data["runtime_identity"],
                             config_changed=config != self.data["public_config"] or role_options != self.data["role_options"], holdout_changed=holdout_changed)
            self._save()
            raise ValueError("评测期间源码、数据集、镜像、配置或模型发生变化，本轮已标记为不可比较")

    def completed(self, case_key: str) -> None:
        self.verify()
        self.data["completed_case_keys"].append(case_key)
        self._save()

    def finish(self) -> None:
        self.verify()
        self.data["state"] = "COMPLETED"
        self.data["runtime_comparable"] = self.data["runtime_identity"]["state"] == "CAPTURED" and not self.data["attached_run"]
        self.data["finished_at"] = datetime.now(timezone.utc).isoformat()
        self._save()

    def interrupt(self, reason: str) -> None:
        if self.data["state"] != "INVALID":
            self.data["state"] = "INCOMPLETE"
        self.data["stop_reason"] = reason
        self._save()

    def _save(self, *, initial: bool = False) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        if initial:
            with self.path.open("x", encoding="utf-8") as handle:
                handle.write(json.dumps(self.data, ensure_ascii=False, indent=2) + "\n")
            return
        temporary = self.path.with_suffix(".tmp")
        temporary.write_text(json.dumps(self.data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        temporary.replace(self.path)
