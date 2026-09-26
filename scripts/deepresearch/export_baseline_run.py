"""DR-001：把失败基线的 Run 状态、日志与数据库导出固化成可离线复盘的证据包。

脚本不写业务状态，只读取 MySQL。它通过 `information_schema` 反射列名，因此
不需要为每张表硬编码字段，也就不会因为迁移演进悄悄漏掉新列。

输出目录：
    experiments/deep-research/baseline/runs/<runId>/
        index.json        # Run 终态、时间线、各表行数
        tables/<table>.json
        log/
            run.log       # 若提供了 --log-file 则复制过来

用法：
    python scripts/deepresearch/export_baseline_run.py \
        --run 31d1d44f-85b9-44e3-b1fd-89dcbefa3c14 \
        --run 2f2e25c9-54a0-4993-837f-822e4e633b54 \
        --run b53ee5dd-c630-42d0-9b61-35ed4eff81a8
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_OUTPUT_ROOT = REPOSITORY_ROOT / "experiments/deep-research/baseline/runs"
DEFAULT_CONTAINER = "noteweave-v2-mysql"
DEFAULT_DATABASE = "noteweave"
DEFAULT_USER = "root"

# 与本次简历主张直接相关的表；缺表只记录 skipped，不静默通过。
RESEARCH_TABLES = (
    "research_run",
    "research_run_stage",
    "research_matrix_plan",
    "research_row",
    "research_cell",
    "research_cell_evidence",
    "source_evidence",
    "research_evidence_validation",
    "research_verifier_decision",
    "research_agent_task",
    "research_agent_execution",
    "research_agent_completion",
    "research_agent_candidate",
    "research_cell_merge",
    "research_agent_checkpoint",
    "research_checkpoint_hydration_snapshot",
    "research_budget_reservation",
    "research_agent_outbox",
    "research_agent_run_advancement",
    "research_agent_role_result",
    "research_agent_report_artifact",
)


class ExportError(RuntimeError):
    """导出失败必须显式失败，禁止写出半份无法复盘的证据包。"""


def load_dotenv(path: Path) -> dict[str, str]:
    if not path.is_file():
        return {}
    values: dict[str, str] = {}
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        values[key.strip()] = value.strip().strip('"').strip("'")
    return values


def run_mysql(container: str, user: str, password: str, database: str, sql: str) -> str:
    command = [
        "docker", "exec", "-i", container,
        "mysql", f"-u{user}", f"-p{password}", "-D", database,
        "--default-character-set=utf8mb4",
        "--batch", "--raw", "--skip-column-names", "-e", sql,
    ]
    try:
        completed = subprocess.run(
            command,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="strict",
            timeout=120,
            check=False,
        )
    except FileNotFoundError as error:
        raise ExportError("docker CLI is not available; start MySQL and retry") from error
    except subprocess.TimeoutExpired as error:
        raise ExportError("mysql export timed out") from error
    if completed.returncode != 0:
        message = (completed.stderr or completed.stdout).strip()
        raise ExportError(f"mysql export failed: {message}")
    return completed.stdout


def list_columns(container: str, user: str, password: str, database: str, table: str) -> list[str]:
    sql = (
        "select column_name from information_schema.columns "
        f"where table_schema = '{database}' and table_name = '{table}' order by ordinal_position"
    )
    output = run_mysql(container, user, password, database, sql)
    return [line.strip() for line in output.splitlines() if line.strip()]


def list_tables(container: str, user: str, password: str, database: str) -> set[str]:
    sql = f"select table_name from information_schema.tables where table_schema = '{database}'"
    output = run_mysql(container, user, password, database, sql)
    return {line.strip() for line in output.splitlines() if line.strip()}


def choose_filter(columns: list[str], table: str) -> tuple[str, str] | None:
    """返回 (过滤说明, WHERE 子句)。任务子表通过任务外键回溯到 Run。"""
    if table == "research_run":
        return ("id", "`id` = :run") if "id" in columns else None
    for candidate in ("research_run_id", "run_id", "research_agent_run_id"):
        if candidate in columns:
            return (candidate, f"{_quoted_identifier(candidate)} = :run")
    if "research_agent_task_id" in columns:
        return (
            "research_agent_task_id<task_subquery>",
            "`research_agent_task_id` in "
            "(select id from research_agent_task where research_run_id = :run)",
        )
    return None


def export_table(
    *,
    container: str,
    user: str,
    password: str,
    database: str,
    table: str,
    run_id: str,
    columns: list[str],
    where_clause: str,
) -> list[dict[str, object]]:
    json_object = ", ".join(
        f"{json.dumps(column)}, {_quoted_identifier(column)}" for column in columns
    )
    sql = (
        f"select json_object({json_object}) from {_quoted_identifier(table)} "
        f"where {where_clause.replace(':run', json.dumps(run_id))}"
    )
    output = run_mysql(container, user, password, database, sql)
    rows: list[dict[str, object]] = []
    for line in output.splitlines():
        if not line.strip():
            continue
        rows.append(json.loads(line))
    return rows


def _quoted_identifier(name: str) -> str:
    return "`" + name.replace("`", "``") + "`"


def _count_by(rows: list[dict[str, object]], field: str) -> dict[str, int]:
    counts: dict[str, int] = {}
    for row in rows:
        key = str(row.get(field) or "UNKNOWN")
        counts[key] = counts.get(key, 0) + 1
    return dict(sorted(counts.items()))


def export_run(
    run_id: str,
    *,
    container: str,
    user: str,
    password: str,
    database: str,
    output_root: Path,
    log_file: Path | None,
) -> dict[str, object]:
    available = list_tables(container, user, password, database)
    target = output_root / run_id
    tables_dir = target / "tables"
    tables_dir.mkdir(parents=True, exist_ok=True)

    index: dict[str, object] = {
        "export_schema": "research-baseline-run-export.v1",
        "research_run_id": run_id,
        "database": database,
        "tables": {},
        "skipped_tables": [],
    }
    for table in RESEARCH_TABLES:
        if table not in available:
            index["skipped_tables"].append({"table": table, "reason": "TABLE_NOT_FOUND"})
            continue
        columns = list_columns(container, user, password, database, table)
        selected = choose_filter(columns, table)
        if selected is None:
            index["skipped_tables"].append({"table": table, "reason": "NO_RUN_FILTER_COLUMN"})
            continue
        filter_label, where_clause = selected
        rows = export_table(
            container=container,
            user=user,
            password=password,
            database=database,
            table=table,
            run_id=run_id,
            columns=columns,
            where_clause=where_clause,
        )
        (tables_dir / f"{table}.json").write_text(
            json.dumps(rows, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
            newline="\n",
        )
        index["tables"][table] = {"row_count": len(rows), "filter": filter_label}

    run_rows = json.loads((tables_dir / "research_run.json").read_text(encoding="utf-8")) if (
        tables_dir / "research_run.json"
    ).is_file() else []
    if not run_rows:
        raise ExportError(f"run {run_id} was not found in {database}.research_run")
    task_rows = json.loads((tables_dir / "research_agent_task.json").read_text(encoding="utf-8")) if (
        tables_dir / "research_agent_task.json"
    ).is_file() else []
    cell_rows = json.loads((tables_dir / "research_cell.json").read_text(encoding="utf-8")) if (
        tables_dir / "research_cell.json"
    ).is_file() else []
    index["terminal_state"] = run_rows[0].get("status")
    # Run 表没有失败原因列；终态原因记在任务上，这里按任务聚合以便离线复盘。
    index["task_terminal_reasons"] = sorted(
        {str(row.get("terminal_reason")) for row in task_rows if row.get("terminal_reason")}
    )
    index["cell_status_counts"] = _count_by(cell_rows, "cell_status")
    index["question"] = run_rows[0].get("question")

    if log_file is not None:
        if not log_file.is_file():
            raise ExportError(f"log file not found: {log_file}")
        log_dir = target / "log"
        log_dir.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(log_file, log_dir / log_file.name)
        index["log_file"] = log_file.name

    (target / "index.json").write_text(
        json.dumps(index, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
        newline="\n",
    )
    return index


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run", action="append", default=[], required=True, help="Research Run id")
    parser.add_argument("--container", default=DEFAULT_CONTAINER)
    parser.add_argument("--database", default=None)
    parser.add_argument("--user", default=DEFAULT_USER)
    parser.add_argument("--password", default=None)
    parser.add_argument("--output-root", type=Path, default=DEFAULT_OUTPUT_ROOT)
    parser.add_argument("--log-file", type=Path, default=None)
    args = parser.parse_args(argv)

    dotenv = load_dotenv(REPOSITORY_ROOT / ".env")
    database = args.database or dotenv.get("MYSQL_DATABASE") or DEFAULT_DATABASE
    password_variable = "MYSQL_ROOT_PASSWORD" if args.user == "root" else "MYSQL_PASSWORD"
    password = (
        args.password
        or dotenv.get(password_variable)
        or os.environ.get(password_variable)
    )
    if not password:
        raise ExportError(f"{password_variable} is required (env or .env)")

    summaries = []
    for run_id in args.run:
        summaries.append(
            export_run(
                run_id,
                container=args.container,
                user=args.user,
                password=password,
                database=database,
                output_root=args.output_root,
                log_file=args.log_file,
            )
        )
    sys.stdout.write(json.dumps(summaries, ensure_ascii=False, indent=2) + "\n")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except ExportError as error:
        sys.stderr.write(f"export_baseline_run failed: {error}\n")
        raise SystemExit(2)
