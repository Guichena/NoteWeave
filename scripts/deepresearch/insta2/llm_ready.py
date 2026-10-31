"""探测研究与评分共用的模型接口是否按系统消息输出 JSON：评分脚本把评分标准放在系统消息里，接口忽略系统消息时整轮评分作废。

用法：python3 run_with_env.py exp/llm_ready.py [次数]；全部返回 JSON 时退出码为 0，否则为 1。只输出计数，不输出密钥。
"""
import json
import os
import sys
import urllib.request


def ask(index):
    payload = {
        "model": os.environ["NOTEWEAVE_RESEARCH_LLM_MODEL"], "temperature": 0,
        "response_format": {"type": "json_object"},
        "messages": [
            {"role": "system", "content": "你是评分器。只输出 JSON：{\"scores\": {\"a\": 0.5}}"},
            {"role": "user", "content": f"请评分：报告内容为空。第 {index} 次"},
        ],
    }
    request = urllib.request.Request(
        os.environ["NOTEWEAVE_RESEARCH_LLM_BASE_URL"].rstrip("/") + "/chat/completions",
        data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json",
                 "Authorization": "Bearer " + os.environ["NOTEWEAVE_RESEARCH_LLM_API_KEY"]})
    try:
        with urllib.request.urlopen(request, timeout=120) as response:
            content = json.loads(response.read())["choices"][0]["message"].get("content") or ""
        return isinstance(json.loads(content).get("scores"), dict)
    except Exception:
        return False


def main(count):
    ok = sum(ask(index) for index in range(count))
    print(f"json {ok}/{count}")
    return 0 if ok == count else 1


if __name__ == "__main__":
    sys.exit(main(int(sys.argv[1]) if len(sys.argv) > 1 else 5))
