"""DR-000：生成可重建当前环境的 Experiment Manifest。

该脚本读取仓库内的配置与**正在运行的 Worker 容器**，产出 `experiment-manifest.json`：

- 代码版本：commit SHA、是否脏工作区、最大数据库迁移版本；
- 开关快照：`noteweave.research.*` 的解析值（进程环境优先，否则取默认值）；
- Provider 声明值：`docker-compose.yml` 中 Research Worker 的环境变量模板（密钥脱敏）；
- Provider **生效值**：`docker exec <container> printenv` 的真实环境变量（密钥脱敏），
  并与声明值做漂移比较——只声明不进容器不足以复现运行期配置；
- 运行环境：Python / Node / Java 版本。

两类 digest 分开，互不掩盖：

- `manifest_digest` 只覆盖可从仓库重新推导的部分，因此同一仓库状态重复执行应得到相同值；
- `effective_environment.digest` 覆盖运行期生效值，容器配置变化时它会变化——这是正确行为。

用法：
    python scripts/deepresearch/build_experiment_manifest.py
    python scripts/deepresearch/build_experiment_manifest.py --stdout
    python scripts/deepresearch/build_experiment_manifest.py --no-live
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import platform
import re
import shutil
import subprocess
import sys
import unicodedata
from pathlib import Path

REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
APPLICATION_YML = REPOSITORY_ROOT / "backend/src/main/resources/application.yml"
DOCKER_COMPOSE_YML = REPOSITORY_ROOT / "docker-compose.yml"
MIGRATION_DIR = REPOSITORY_ROOT / "backend/src/main/resources/db/migration"
DEFAULT_OUTPUT = (
    REPOSITORY_ROOT / "experiments/deep-research/manifest/experiment-manifest.json"
)

MANIFEST_SCHEMA = "research-experiment-manifest.v2"
DEFAULT_WORKER_CONTAINER = "noteweave-v2-research-worker-consumer"
DEFAULT_BACKEND_CONTAINER = "noteweave-v2-backend"

#: 第 2.2 节要求验收环境「高级特性关闭」。这些开关必须能被**测量**，而不是只在
#: Manifest 里声明——声明与运行期不一致正是需要被暴露的风险。
ADVANCED_FEATURE_OFF_KEYS = (
    "NOTEWEAVE_RESEARCH_INTENT_MATRIX_V2",
    "NOTEWEAVE_RESEARCH_RUNNABLE_WORK_V2",
    "NOTEWEAVE_RESEARCH_CHECKPOINT_HYDRATION_V2",
    "NOTEWEAVE_RESEARCH_EVIDENCE_AUDIT_V1",
    "NOTEWEAVE_RESEARCH_WORKER_SYNTHESIS_V1",
    "NOTEWEAVE_RESEARCH_WIDE_DISCOVERY_V1",
    "NOTEWEAVE_RESEARCH_AGENT_EVIDENCE_AUDIT_ENABLED",
    "NOTEWEAVE_RESEARCH_AGENT_SYNTHESIS_ENABLED",
    "NOTEWEAVE_RESEARCH_AGENT_WIDE_DISCOVERY_ENABLED",
    "NOTEWEAVE_RESEARCH_ENABLE_URL_READER",
)
_TRUTHY = frozenset({"1", "true", "yes", "on"})

_ENV_TEMPLATE = re.compile(r"^\$\{([A-Za-z0-9_]+)(?::(-?))?([^}]*)\}$")
_MIGRATION_VERSION = re.compile(r"^V(\d+)__")
_SENSITIVE_KEY = re.compile(
    r"(api[_-]?key|token|password|secret|cookie|credential)", re.IGNORECASE
)


class ManifestError(RuntimeError):
    """Manifest 无法反映真实环境时直接失败，而不是降级写出误导性快照。"""


# --------------------------------------------------------------------------- #
# 最小 YAML 子集解析：只支持嵌套映射与标量，足够读取 application.yml / compose。
# --------------------------------------------------------------------------- #
def parse_yaml_mapping(text: str) -> dict[str, object]:
    """解析由缩进嵌套的映射；忽略注释、空行和列表项。"""
    root: dict[str, object] = {}
    # 每一层是 (缩进宽度, 该层的字典)
    stack: list[tuple[int, dict[str, object]]] = [(-1, root)]
    for raw_line in text.splitlines():
        line = raw_line.split("#", 1)[0].rstrip()
        if not line.strip():
            continue
        indent = len(line) - len(line.lstrip(" "))
        stripped = line.strip()
        if stripped.startswith("- "):
            # 列表项（如 compose 的 ports）不参与 Manifest。
            continue
        key, separator, raw_value = stripped.partition(":")
        if not separator:
            continue
        key = key.strip().strip("'\"")
        value = raw_value.strip()
        while stack and indent <= stack[-1][0]:
            stack.pop()
        if not stack:
            raise ManifestError(f"YAML indentation cannot be resolved at: {raw_line!r}")
        parent = stack[-1][1]
        if not value:
            child: dict[str, object] = {}
            parent[key] = child
            stack.append((indent, child))
            continue
        parent[key] = value.strip("'\"")
    return root


def resolve_env_template(value: object) -> object:
    """把 `${NAME:default}` / `${NAME:-default}` 解析成生效值。"""
    if not isinstance(value, str):
        return value
    match = _ENV_TEMPLATE.match(value.strip())
    if match is None:
        return value
    name, _, default = match.group(1), match.group(2), match.group(3)
    return os.environ.get(name, default)


def resolve_tree(node: object) -> object:
    if isinstance(node, dict):
        return {key: resolve_tree(item) for key, item in node.items()}
    return resolve_env_template(node)


def redact(node: object, key_hint: str = "") -> object:
    """密钥只保留“是否已配置”，绝不写入 Manifest。"""
    if isinstance(node, dict):
        return {key: redact(value, key) for key, value in node.items()}
    if isinstance(node, str):
        if _SENSITIVE_KEY.search(key_hint):
            return "SET" if node.strip() else "UNSET"
        return node
    return node


def select_research_flags(application: dict[str, object]) -> dict[str, object]:
    noteweave = application.get("noteweave")
    if not isinstance(noteweave, dict):
        raise ManifestError("application.yml is missing the noteweave root")
    research = noteweave.get("research")
    if not isinstance(research, dict):
        raise ManifestError("application.yml is missing noteweave.research")
    return redact(resolve_tree(research))


def select_worker_provider_environment(compose: dict[str, object]) -> dict[str, object]:
    """选取声明了 Research Worker 环境变量的服务，而不是硬编码服务名。"""
    services = compose.get("services")
    if not isinstance(services, dict):
        raise ManifestError("docker-compose.yml is missing services")
    matches: dict[str, object] = {}
    for service_name, service in services.items():
        if not isinstance(service, dict) or not isinstance(service.get("environment"), dict):
            continue
        environment = redact(resolve_tree(service["environment"]))
        research_keys = {
            key: value for key, value in environment.items() if key.startswith("NOTEWEAVE_RESEARCH")
        }
        # Backend 也会声明 Research Feature Flag；只有携带 Provider 端点的服务才是 Worker。
        declares_provider = any(
            "_SEARCH_" in key or "_LLM_" in key or "_JINA_" in key for key in research_keys
        )
        if research_keys and declares_provider:
            if matches:
                raise ManifestError("multiple compose services declare Research provider environment")
            matches[service_name] = research_keys
    if not matches:
        raise ManifestError("docker-compose.yml has no research worker service environment")
    return matches


def latest_migration_version() -> dict[str, object]:
    if not MIGRATION_DIR.is_dir():
        raise ManifestError(f"migration directory not found: {MIGRATION_DIR}")
    versions: list[tuple[int, str]] = []
    for path in MIGRATION_DIR.iterdir():
        match = _MIGRATION_VERSION.match(path.name)
        if match is not None:
            versions.append((int(match.group(1)), path.name))
    if not versions:
        raise ManifestError("no versioned migrations found")
    number, filename = max(versions)
    return {"version": f"V{number}", "file": filename, "count": len(versions)}


def _run(command: list[str]) -> str:
    try:
        completed = subprocess.run(
            command,
            cwd=REPOSITORY_ROOT,
            capture_output=True,
            text=True,
            timeout=30,
            check=False,
        )
    except (OSError, subprocess.TimeoutExpired):
        return ""
    if completed.returncode != 0:
        return ""
    return completed.stdout.strip()


def git_state() -> dict[str, object]:
    commit = _run(["git", "rev-parse", "HEAD"])
    status = _run(["git", "status", "--porcelain"])
    branch = _run(["git", "rev-parse", "--abbrev-ref", "HEAD"])
    changed = [line for line in status.splitlines() if line.strip()]
    return {
        "commit": commit or "UNKNOWN",
        "branch": branch or "UNKNOWN",
        "dirty": bool(changed),
        "changed_path_count": len(changed),
    }


def toolchain_versions() -> dict[str, object]:
    java = _run(["java", "-version"])
    node = _run(["node", "--version"])
    return {
        "python": platform.python_version(),
        "node": node or ("UNKNOWN" if shutil.which("node") is None else "ERROR"),
        "java": (java.splitlines()[0].strip() if java else "UNKNOWN"),
        "os": f"{platform.system()} {platform.release()}",
    }


def canonical_digest(payload: object) -> str:
    encoded = json.dumps(
        payload,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
        allow_nan=False,
    ).encode("utf-8")
    return "sha256:" + hashlib.sha256(encoded).hexdigest()


def read_live_container_environment(container: str) -> dict[str, str]:
    """读取**正在运行**的 Worker 容器的生效环境变量。

    只声明不进容器是不够的：`docker-compose.yml` 里的值是模板，真正的生效值由
    `.env` 与运行期覆盖决定。实验 Manifest 必须记录生效值，否则「可重建当前环境」
    是空话。读取失败时返回空字典，由调用方标记为不可用，而不是伪造。
    """
    command = ["docker", "exec", container, "printenv"]
    try:
        completed = subprocess.run(
            command,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=30,
            check=False,
        )
    except (OSError, subprocess.TimeoutExpired):
        return {}
    if completed.returncode != 0:
        return {}
    environment: dict[str, str] = {}
    for line in completed.stdout.splitlines():
        key, separator, value = line.partition("=")
        if separator and key.startswith("NOTEWEAVE_RESEARCH"):
            environment[key] = value
    return environment


def environment_drift(
    declared: dict[str, object], effective: dict[str, str]
) -> dict[str, object]:
    """比较声明值与生效值。

    未解析的 `${...}` 模板不参与比较（它本来就无法与生效值对齐），单独计数，
    避免把「模板未解析」误报成「配置漂移」。
    """
    differences: list[dict[str, object]] = []
    unresolved: list[str] = []
    for key in sorted(set(declared) & set(effective)):
        declared_value = declared[key]
        effective_value = effective[key]
        if not isinstance(declared_value, str):
            continue
        if declared_value.startswith("${"):
            unresolved.append(key)
            continue
        if declared_value != effective_value:
            differences.append(
                {"key": key, "declared": declared_value, "effective": effective_value}
            )
    return {
        "differences": differences,
        "unresolved_template_keys": unresolved,
        "effective_only_keys": sorted(set(effective) - set(declared)),
        "declared_only_keys": sorted(set(declared) - set(effective)),
    }


def measure_acceptance_flags(
    effective_environments: dict[str, dict[str, str]],
) -> dict[str, object]:
    """测量「高级特性关闭」是否成立。

    未出现在任何容器环境里的开关取 `application.yml` 的默认值（本仓库全部为
    `false`），因此状态记为 `UNSET_DEFAULT_OFF` 而不是「未知」——否则会把正常
    的默认配置误报成未验证。
    """
    flags: dict[str, object] = {}
    violations: list[str] = []
    for key in ADVANCED_FEATURE_OFF_KEYS:
        raw: str | None = None
        for values in effective_environments.values():
            if key in values:
                raw = values[key]
                break
        if raw is None:
            flags[key] = {"state": "UNSET_DEFAULT_OFF", "value": False}
            continue
        truthy = raw.strip().lower() in _TRUTHY
        flags[key] = {"state": "SET", "value": truthy}
        if truthy:
            violations.append(key)
    return {
        "requirement": "第 2.2 节：验收环境固定为高级特性关闭",
        "flags": flags,
        "violations": sorted(violations),
        "compliant": None if not effective_environments else not violations,
    }


def build_manifest(
    *,
    containers: dict[str, str] | None = None,
    include_live: bool = True,
) -> dict[str, object]:
    application = parse_yaml_mapping(APPLICATION_YML.read_text(encoding="utf-8"))
    compose = parse_yaml_mapping(DOCKER_COMPOSE_YML.read_text(encoding="utf-8"))
    declared_environments = select_worker_provider_environment(compose)
    service_name = next(iter(declared_environments))
    declared_environment = declared_environments[service_name]
    if not isinstance(declared_environment, dict):
        raise ManifestError("declared worker environment must be an object")

    # manifest_digest 只覆盖「可从仓库重新推导」的部分，因此它与运行期容器状态无关；
    # 生效环境单独记录并带自己的 digest，两者都不允许互相掩盖。
    declared: dict[str, object] = {
        "manifest_schema": MANIFEST_SCHEMA,
        "code": {
            **git_state(),
            "database_migration": latest_migration_version(),
        },
        "toolchain": toolchain_versions(),
        "feature_flags": select_research_flags(application),
        "worker_provider_environment_declared": declared_environments,
        "acceptance_environment_declared": {
            "advanced_features_disabled": True,
            "worker_replicas": 1,
            "concurrency": 1,
            "note": "第 2.2 节：验收环境固定为高级特性关闭、单 Worker、并发度 1。",
        },
    }
    declared = json.loads(unicodedata.normalize("NFC", json.dumps(declared, ensure_ascii=False)))
    declared["manifest_digest"] = canonical_digest(declared)

    targets = containers if containers is not None else {
        "worker": DEFAULT_WORKER_CONTAINER,
        "backend": DEFAULT_BACKEND_CONTAINER,
    }
    live_environments: dict[str, dict[str, str]] = {}
    container_blocks: dict[str, object] = {}
    for role, container in targets.items():
        if not include_live or not container:
            continue
        raw_values = read_live_container_environment(container)
        if not raw_values:
            container_blocks[role] = {
                "available": False,
                "container": container,
                "source": "unavailable",
                "note": "容器未运行或 docker 不可用；不能据此复现运行期配置。",
            }
            continue
        effective_values = {key: redact(value, key) for key, value in raw_values.items()}
        live_environments[role] = {key: str(value) for key, value in raw_values.items()}
        block: dict[str, object] = {
            "available": True,
            "container": container,
            "source": f"docker exec {container} printenv",
            "values": dict(sorted(effective_values.items())),
            "digest": canonical_digest(dict(sorted(effective_values.items()))),
        }
        if role == "worker":
            block["drift_vs_declared"] = environment_drift(declared_environment, effective_values)
        container_blocks[role] = block

    declared["effective_environment"] = {
        "available": bool(live_environments),
        "containers": container_blocks,
        "acceptance_compliance": measure_acceptance_flags(live_environments),
    }
    return declared


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--stdout", action="store_true", help="只打印，不写文件")
    parser.add_argument(
        "--worker-container",
        default=DEFAULT_WORKER_CONTAINER,
        help="读取 Worker 生效环境变量的容器名",
    )
    parser.add_argument(
        "--backend-container",
        default=DEFAULT_BACKEND_CONTAINER,
        help="读取 Backend 生效环境变量的容器名",
    )
    parser.add_argument(
        "--no-live",
        action="store_true",
        help="不读取运行中容器，只记录仓库声明值",
    )
    args = parser.parse_args(argv)

    containers = {
        "worker": args.worker_container,
        "backend": args.backend_container,
    }
    manifest = build_manifest(containers=containers, include_live=not args.no_live)
    rendered = json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    if args.stdout:
        sys.stdout.write(rendered)
        return 0
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(rendered, encoding="utf-8", newline="\n")
    print(f"wrote {args.output.relative_to(REPOSITORY_ROOT)}")
    print(f"manifest_digest={manifest['manifest_digest']}")

    effective = manifest["effective_environment"]
    if not isinstance(effective, dict):
        raise ManifestError("effective environment block is malformed")
    for role, block in sorted(effective["containers"].items()):  # type: ignore[union-attr]
        if isinstance(block, dict) and block.get("available"):
            print(f"effective_environment_digest[{role}]={block['digest']}")
        else:
            print(f"effective_environment[{role}]=UNAVAILABLE")
    compliance = effective["acceptance_compliance"]
    if isinstance(compliance, dict):
        print(f"advanced_feature_violations={compliance['violations']}")
        print(f"acceptance_compliant={compliance['compliant']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
