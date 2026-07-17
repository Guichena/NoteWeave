"""MA4D execution isolation primitives; real network tool wiring follows TaskSnapshot support."""

from __future__ import annotations

from dataclasses import dataclass, field


@dataclass
class CancellationToken:
    cancelled: bool = False

    def cancel(self) -> None:
        self.cancelled = True

    def require_active(self) -> None:
        if self.cancelled:
            raise RuntimeError("research agent execution is cancelled")


@dataclass
class UsageAccumulator:
    values: dict[str, int | float] = field(default_factory=dict)

    def add(self, key: str, value: int | float = 1) -> None:
        self.values[key] = self.values.get(key, 0) + value


@dataclass(frozen=True)
class Toolbox:
    allowed_tools: frozenset[str]

    def require(self, tool_name: str) -> None:
        if tool_name not in self.allowed_tools:
            raise PermissionError(f"tool is not allowed for this research agent role: {tool_name}")


@dataclass
class RoleExecutionContext:
    execution_id: str
    role: str
    profile: RoleProfile
    toolbox: Toolbox
    usage: UsageAccumulator
    cancel_token: CancellationToken


@dataclass(frozen=True)
class RoleProfile:
    profile_key: str
    model_purpose: str
    temperature: float
    allowed_tools: frozenset[str]
    max_search_calls: int
    max_fetch_calls: int
    max_read_windows: int
    max_llm_calls: int
    require_independent_sources: bool = False


class RoleProfileCatalog:
    _PROFILES = {
        "WIDE_DISCOVERY": RoleProfile(
            "wide-discovery-v1", "wide_discovery", 0.2,
            frozenset({"search", "read"}), 8, 0, 12, 2,
        ),
        "DEEP_CELL": RoleProfile(
            "deep-cell-v1", "deep_cell_extract", 0.1,
            frozenset({"search", "fetch", "read", "extract"}), 4, 4, 8, 4,
        ),
        "COUNTERFACTUAL": RoleProfile(
            "counterfactual-v1", "counterfactual_verify", 0.0,
            frozenset({"search", "fetch", "read", "extract"}), 6, 6, 10, 4, True,
        ),
        "CELL_VERIFIER": RoleProfile(
            "cell-verifier-v1", "cell_verify", 0.0,
            frozenset({"read"}), 0, 0, 8, 1,
        ),
        "GLOBAL_VERIFIER": RoleProfile(
            "global-verifier-v1", "global_verify", 0.0,
            frozenset({"read"}), 0, 0, 16, 1,
        ),
        # Runtime aliases retained for the MA4 task snapshot vocabulary.
        "EVIDENCE_AUDIT": RoleProfile(
            "evidence-audit-v1", "cell_verify", 0.0,
            frozenset({"read"}), 0, 0, 8, 1,
        ),
        "SYNTHESIS": RoleProfile(
            "synthesis-v1", "report_synthesis", 0.0,
            frozenset(), 0, 0, 0, 1,
        ),
    }

    def resolve(self, role: str) -> RoleProfile:
        normalized = role.strip().upper()
        profile = self._PROFILES.get(normalized)
        if profile is None:
            raise ValueError(f"unsupported research agent role: {role}")
        return profile


class RoleExecutorFactory:
    def __init__(self, profiles: RoleProfileCatalog | None = None) -> None:
        self.profiles = profiles or RoleProfileCatalog()

    def create(self, role: str, execution_id: str) -> RoleExecutionContext:
        normalized = role.strip().upper()
        profile = self.profiles.resolve(normalized)
        if not execution_id.strip():
            raise ValueError("execution_id must not be blank")
        return RoleExecutionContext(
            execution_id=execution_id.strip(),
            role=normalized,
            profile=profile,
            toolbox=Toolbox(profile.allowed_tools),
            usage=UsageAccumulator(),
            cancel_token=CancellationToken(),
        )
