from types import SimpleNamespace

from app import source_scope_checks
from app.source_scope_checks import check_source_scopes, validate_scope_checks


def _snapshot():
    return SimpleNamespace(query_policy={"requirement_contract": {"source_scopes": [], "requirements": []}})


def _clusters(count):
    return [{"cluster_key": f"c{i}", "cell_key": "row:col", "claim_text": f"claim {i}", "evidence_keys": []} for i in range(count)]


class _Client:
    def __init__(self, responses):
        self.responses = list(responses)
        self.calls = []

    def complete_json(self, purpose, payload):
        self.calls.append([fact["cluster_key"] for fact in payload["facts"]])
        response = self.responses.pop(0)
        if isinstance(response, Exception):
            raise response
        if response == "ECHO":
            return {"checks": [{"cluster_key": fact["cluster_key"], "product": "MATCH", "branch": "APPLICABLE",
                                "time": "APPLICABLE", "date": "", "date_kind": "UNKNOWN", "date_quote": ""}
                               for fact in payload["facts"]]}
        return response


def _run(clusters, client, monkeypatch):
    monkeypatch.setattr(source_scope_checks, "require_execution_active", lambda: None)
    monkeypatch.setattr(source_scope_checks, "parse_json_payload", lambda value: value)
    result = check_source_scopes(_snapshot(), clusters, [], [], client)
    validate_scope_checks(result)
    return result


def test_failed_scope_call_is_retried_instead_of_marking_every_fact_unknown(monkeypatch):
    client = _Client([ConnectionError("Connection error."), "ECHO"])
    result = _run(_clusters(3), client, monkeypatch)
    assert result["model_calls"] == 2
    assert {check["product"] for check in result["checks"]} == {"MATCH"}


def test_mismatched_cluster_keys_are_retried(monkeypatch):
    client = _Client([{"checks": []}, "ECHO"])
    result = _run(_clusters(2), client, monkeypatch)
    assert result["model_calls"] == 2
    assert all(check["time"] == "APPLICABLE" for check in result["checks"])


def test_retry_keeps_one_call_for_each_later_batch(monkeypatch):
    client = _Client([ConnectionError("x"), "ECHO", "ECHO"])
    result = _run(_clusters(12), client, monkeypatch)
    assert result["model_calls"] == 3
    assert [len(batch) for batch in client.calls] == [6, 6, 6]
    assert all(check["product"] == "MATCH" for check in result["checks"])


def test_first_batch_failing_twice_still_leaves_the_later_batch_its_call(monkeypatch):
    client = _Client([ConnectionError("x"), ConnectionError("y"), "ECHO"])
    result = _run(_clusters(12), client, monkeypatch)
    assert result["model_calls"] == 3
    first, second = result["checks"][:6], result["checks"][6:]
    assert {check["product"] for check in first} == {"UNKNOWN"}
    assert {check["product"] for check in second} == {"MATCH"}
