from concurrent.futures import ThreadPoolExecutor
from threading import Barrier, Lock
from types import SimpleNamespace
import json
import time

import pytest

from app import llm_client
from app.node_executor import NodeExecutionError, NodeExecutionResult, NodeExecutor


def _client(monkeypatch, create, budget=64):
    monkeypatch.setattr(llm_client, "OpenAI", lambda **_kwargs:
        SimpleNamespace(chat=SimpleNamespace(completions=SimpleNamespace(create=create))))
    return llm_client.OpenAICompatibleLlmClient("test-key", "test-model", max_total_calls=budget)


def _response(content):
    return SimpleNamespace(choices=[SimpleNamespace(message=SimpleNamespace(content=content))],
        usage=SimpleNamespace(prompt_tokens=7, completion_tokens=3))


def _node():
    schema = {"type": "object", "additionalProperties": False,
        "properties": {"value": {"type": "integer"}}, "required": ["value"]}
    return SimpleNamespace(node_id="writer", node_kind="LLM_STEP",
        prompt_recipe_id="node.style_polisher.v1", input_schema=schema, output_schema=schema)


def test_concurrent_reservations_share_remaining_budget_and_snapshot_is_detached(monkeypatch):
    entered = []
    lock = Lock()
    start = Barrier(8)

    def create(**_kwargs):
        with lock:
            entered.append(1)
        time.sleep(0.02)
        return _response('{"value":1}')

    client = _client(monkeypatch, create, budget=5)
    client.reserve_prior_calls(2)

    def invoke(index):
        start.wait(timeout=5)
        result = client.complete_json("artifact.node.section_writer", {"index": index})
        return bool(result), client.current_thread_call_count()

    with ThreadPoolExecutor(max_workers=8) as pool:
        results = list(pool.map(invoke, range(8)))
    summary = client.usage_summary()
    assert len(entered) == sum(success for success, _count in results) == 3
    assert sum(count for _success, count in results) == 3
    assert summary["call_count"] == 5
    assert summary["input_tokens"] == 21
    assert summary["output_tokens"] == 9
    summary["calls"][0]["status"] = "EXTERNAL_CHANGE"
    assert client.usage_summary()["calls"][0]["status"] == "COMPLETED"


@pytest.mark.parametrize("invalid_first", [False, True])
def test_node_traces_count_only_their_thread_requests_including_retry(monkeypatch, invalid_first):
    gate = Barrier(4)
    attempts = {}
    lock = Lock()

    def create(**kwargs):
        data = json.loads(kwargs["messages"][1]["content"])["payload"]
        value = data["inputs"]["value"]
        with lock:
            attempts[value] = attempts.get(value, 0) + 1
            attempt = attempts[value]
        gate.wait(timeout=5)
        if invalid_first and attempt == 1:
            return _response('{"value":"invalid"}')
        if invalid_first:
            assert data["validation_errors"]
        return _response(json.dumps({"value": value}))

    client = _client(monkeypatch, create)
    with ThreadPoolExecutor(max_workers=4) as pool:
        results = list(pool.map(lambda value: NodeExecutor(client).execute(_node(), {"value": value}), range(4)))
    expected = 2 if invalid_first else 1
    assert [result.trace["llm_calls"] for result in results] == [expected] * 4
    assert [result.output["value"] for result in results] == list(range(4))
    assert client.usage_summary()["current_call_count"] == 4 * expected


def test_composite_nodes_still_project_inputs_and_validate_outputs():
    observed = []

    def composite(inputs, _node):
        observed.append(inputs)
        return NodeExecutionResult({"value": inputs["value"]},
            {"llm_calls": 2, "purpose": "artifact.node.section_writer", "steps": [{"llm_calls": 2}]})

    executor = NodeExecutor(llm_handlers={"node.style_polisher.v1": composite})
    result = executor.execute(_node(), {"value": 4, "permission_secret": "hidden"})
    assert observed == [{"value": 4}]
    assert result.trace["llm_calls"] == 2
    assert result.trace["input_fields"] == ["value"]
    assert result.output == {"value": 4}

    def invalid(_inputs, _node):
        return NodeExecutionResult({"value": "invalid"}, {"llm_calls": 0})

    with pytest.raises(NodeExecutionError):
        NodeExecutor(llm_handlers={"node.style_polisher.v1": invalid}).execute(_node(), {"value": 4})
