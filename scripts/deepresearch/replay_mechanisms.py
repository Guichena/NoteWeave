"""离线机制回放入口：固定输入、模拟模型、受控数据库；不调用外部 Provider。"""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
from datetime import datetime, timezone

ROOT = Path(__file__).resolve().parents[2]
FIXTURES = Path(__file__).resolve().parent / "replay"
SCENARIOS = {
    "显式数量和原文绑定": "replay_requirement_v2.py",
    "同义事实不增加发现数": "replay_requirement_v2.py",
    "大量事实分节保留": "replay_requirement_v2.py",
    "多对象纵向表格": "replay_requirement_v2.py",
    "逐句来源核验与局部替换": "replay_statement_support.py",
    "明确需求优先选择事实": "replay_completion_design_v4.py",
    "截断覆盖降为部分": "replay_completion_design_v4.py",
    "定向阅读遵守域名范围": "replay_completion_design_v4.py",
    "报告答案限次修复": "replay_completion_design_v4.py",
    "大报告分段和瞬时错误重试": "replay_synthesis_upload.py",
    "目录章节限制与版本目录隔离": "replay_runtime_v5.py",
    "跨对象比较范围绑定": "replay_runtime_v5.py",
    "未知来源语义不通过": "replay_runtime_v5.py",
    "未知token和未核验报告状态": "replay_runtime_v5.py",
    "规划复核未知时不能声明完整": "replay_runtime_v5.py",
    "规划版本分句遗漏保留缺项": "replay_runtime_v5.py",
    "长事实超出提交大小后保留线索": "replay_runtime_v5.py",
    "成文模型请求先取得服务端授权": "replay_runtime_v5.py",
    "跨轮累计和旧协议兼容": "RequirementCoverageReplay",
    "双路核验的来源独立性": "RequirementCoverageReplay",
    "版本与开发分支范围": "SourceScopeReplay",
    "服务端片段不可变与重组": "SynthesisUploadReplay",
    "服务端语句映射防篡改": "StatementSupportReplay",
    "服务端报告覆盖与完成门禁": "CompletionDesignV4Replay",
    "跨租约恢复与消耗结算": "CompletionRuntimeV5Replay",
    "报告生成中断仍保留授权消耗": "CompletionRuntimeV5Replay",
    "来源日期原文与截止时间": "CompletionRuntimeV5Replay",
    "跨语言冻结摘要一致": "check_contract_v4.py",
}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--java-classpath", help="后端依赖 classpath，提供后同时执行 Java 权威校验")
    parser.add_argument("--backend-classes", type=Path, default=ROOT / "backend/target/classes")
    args = parser.parse_args(argv)
    if sys.flags.optimize:
        raise ValueError("机制回放需要启用 Python 断言，不能使用 -O")
    output = args.output_dir.resolve()
    output.mkdir(parents=True, exist_ok=False)
    env = {**os.environ, "NOTEWEAVE_REPLAY_OUT": str(output), "NOTEWEAVE_REPLAY_ROOT": str(ROOT),
           "PYTHONDONTWRITEBYTECODE": "1", "PYTHONPATH": str(ROOT / "workers/research-worker")}
    outcomes, logs = {}, []

    def run(driver, command):
        result = subprocess.run(command, cwd=ROOT, env=env, capture_output=True, text=True, encoding="utf-8", errors="replace")
        logs.append({"driver": driver, "exit_code": result.returncode, "stdout": result.stdout, "stderr": result.stderr})
        outcomes[driver] = "PASSED" if result.returncode == 0 else "FAILED"
        if result.returncode:
            raise RuntimeError("机制回放失败：" + driver)

    state = "COMPLETED"
    try:
        run("replay_requirement_v2.py",[sys.executable,"-B",str(FIXTURES / "replay_requirement_v2.py"),str(output / "requirement-report.json")])
        for driver in ("replay_statement_support.py","replay_completion_design_v4.py","replay_runtime_v5.py"):
            run(driver,[sys.executable,"-B",str(FIXTURES / driver)])
        run("replay_synthesis_upload.py",[sys.executable,"-B",str(FIXTURES / "replay_synthesis_upload.py"),str(output / "statement-report.json"),str(output / "synthesis-upload.json")])
        if args.java_classpath:
            classes = output / "replay-classes"
            classes.mkdir()
            classpath = str(args.backend_classes.resolve()) + os.pathsep + args.java_classpath
            sources = sorted(FIXTURES.glob("*.java"))
            run("javac",["javac","-encoding","UTF-8","-cp",classpath,"-d",str(classes),*[str(path) for path in sources]])
            classpath = str(classes) + os.pathsep + classpath
            for driver in dict.fromkeys(value for value in SCENARIOS.values() if "." not in value):
                run(driver,["java","-cp",classpath,"com.noteweave.research." + driver])
            run("check_contract_v4.py",[sys.executable,"-B",str(FIXTURES / "check_contract_v4.py")])
    except Exception:
        state = "FAILED"
        raise
    finally:
        report = {"schema_version":"research-mechanism-replay.v1","state":state,
            "scenario_count":len(SCENARIOS),"passed_count":sum(outcomes.get(driver) == "PASSED" for driver in SCENARIOS.values()),
            "all_scenarios_passed":all(outcomes.get(driver) == "PASSED" for driver in SCENARIOS.values()),
            "created_at":datetime.now(timezone.utc).isoformat(),"provider_calls":0,
            "scenarios":[{"name":name,"driver":driver,"status":outcomes.get(driver,"NOT_RUN")} for name,driver in SCENARIOS.items()],"logs":logs}
        (output / "replay-result.json").write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding="utf-8")
    print(json.dumps({"state":state,"passed":sum(outcomes.get(driver) == "PASSED" for driver in SCENARIOS.values()),"scenario_count":len(SCENARIOS)},ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
