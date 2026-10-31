"""加载部署配置与评测账号后执行评测脚本；只设置环境变量，不输出任何值。"""
import os
import secrets
import sys

DEPLOY_ENV = "/data/workspace/noteweave-research-deploy/.env"
EVAL_ENV = "/data/workspace/noteweave-research-eval/eval.env"


def load(path):
    values = {}
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, value = line.split("=", 1)
            value = value.strip()
            if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
                value = value[1:-1]
            values[key.strip()] = value
    return values


def ensure_eval_account():
    if os.path.exists(EVAL_ENV):
        return
    old = os.umask(0o077)
    try:
        with open(EVAL_ENV, "w", encoding="utf-8") as handle:
            handle.write("NOTEWEAVE_EVAL_USERNAME=nw_research_eval\n"
                         "NOTEWEAVE_EVAL_EMAIL=research-eval@noteweave.test\n"
                         f"NOTEWEAVE_EVAL_PASSWORD={secrets.token_urlsafe(18)}\n")
    finally:
        os.umask(old)


ensure_eval_account()
env = dict(os.environ)
env.update(load(DEPLOY_ENV))
env.update(load(EVAL_ENV))
env.setdefault("PYTHONIOENCODING", "utf-8")
os.execvpe(sys.executable, [sys.executable, *sys.argv[1:]], env)
