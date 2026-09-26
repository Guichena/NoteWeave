from app.authoritative_role_router import ROLE_CAPABILITIES


def test_only_registered_worker_roles_are_schedulable() -> None:
    assert ROLE_CAPABILITIES["DEEP_CELL"].schedulable is True
    assert ROLE_CAPABILITIES["COUNTERFACTUAL"].schedulable is True
    assert ROLE_CAPABILITIES["EVIDENCE_AUDIT"].schedulable is True
    assert ROLE_CAPABILITIES["SYNTHESIS"].schedulable is True
    assert ROLE_CAPABILITIES["WIDE_DISCOVERY"].schedulable is True
    assert ROLE_CAPABILITIES["CELL_VERIFIER"].schedulable is False
    assert ROLE_CAPABILITIES["GLOBAL_VERIFIER"].schedulable is False


def test_mutation_authority_is_narrower_than_executor_identity() -> None:
    assert ROLE_CAPABILITIES["EVIDENCE_AUDIT"].allowed_tools == frozenset()
    assert ROLE_CAPABILITIES["SYNTHESIS"].allowed_tools == frozenset()
    assert ROLE_CAPABILITIES["SYNTHESIS"].mutation_authority == "ARTIFACT_PROMOTION_ONLY"
    assert ROLE_CAPABILITIES["WIDE_DISCOVERY"].mutation_authority == "PROPOSAL_ONLY"
    assert ROLE_CAPABILITIES["WIDE_DISCOVERY"].allowed_tools == frozenset({"search", "read"})
