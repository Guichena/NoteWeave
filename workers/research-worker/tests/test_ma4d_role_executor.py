from __future__ import annotations

import pytest


def test_factory_should_isolate_execution_state_and_enforce_role_allowlist() -> None:
    from app.role_executor import RoleExecutorFactory

    factory = RoleExecutorFactory()
    first = factory.create("DEEP_CELL", "execution-a")
    second = factory.create("DEEP_CELL", "execution-b")

    assert first.execution_id == "execution-a"
    assert second.execution_id == "execution-b"
    assert first.toolbox is not second.toolbox
    assert first.usage is not second.usage
    assert first.cancel_token is not second.cancel_token
    assert first.toolbox.allowed_tools == frozenset({"search", "fetch", "read", "extract"})
    with pytest.raises(PermissionError):
        first.toolbox.require("write_canonical_cell")


def test_factory_should_reject_unknown_role_before_creating_execution() -> None:
    from app.role_executor import RoleExecutorFactory

    with pytest.raises(ValueError, match="unsupported research agent role"):
        RoleExecutorFactory().create("ARBITRARY", "execution-a")
