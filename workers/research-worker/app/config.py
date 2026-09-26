import os
import re
import secrets
import socket

from pydantic import AliasChoices, Field, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    worker_type: str = "research"
    java_base_url: str = "http://localhost:8081"
    internal_auth_token: str = Field(
        default="",
        validation_alias=AliasChoices("internal_auth_token", "NOTEWEAVE_RESEARCH_INTERNAL_AUTH_TOKEN", "NOTEWEAVE_INTERNAL_AUTH_TOKEN"),
    )
    environment: str = "local"
    kafka_bootstrap_servers: str = "localhost:9092"
    kafka_security_protocol: str = "PLAINTEXT"
    kafka_sasl_mechanism: str = "PLAIN"
    kafka_sasl_username: str = ""
    kafka_sasl_password: str = ""
    kafka_research_agent_topic: str = "noteweave.research.agent.command"
    kafka_research_agent_dlq_topic: str = "noteweave.research.agent.command.dlq"
    kafka_research_agent_group_id: str = "noteweave-research-agent-worker"
    kafka_consume_max_attempts: int = 3
    # INCREMENTAL_V1 is the only automatic runtime mode. Historical local
    # modes remain parseable only for deterministic benchmark replay.
    research_agent_execution_mode: str = "INCREMENTAL_V1"
    research_agent_max_concurrency: int = 1
    research_agent_bundle_max_cells: int = 3
    research_agent_consumer_enabled: bool = True
    research_agent_deep_cell_executor_enabled: bool = True
    research_agent_evidence_audit_enabled: bool = False
    research_agent_synthesis_enabled: bool = False
    research_agent_wide_discovery_enabled: bool = False
    research_agent_fake_provider_enabled: bool = False
    research_agent_fault_invalid_json: bool = False
    research_agent_fault_tamper_quote: bool = False
    research_agent_fault_crash_after_archive: bool = False
    research_agent_worker_instance_id: str = Field(default_factory=lambda: _default_worker_instance_id())
    research_agent_lease_seconds: int = Field(default=60, ge=4, le=3600)
    research_agent_heartbeat_interval_seconds: float | None = Field(default=None, gt=0)
    research_agent_heartbeat_failure_budget_seconds: float | None = Field(default=None, gt=0)
    research_agent_heartbeat_request_timeout_seconds: float | None = Field(default=None, gt=0, le=30)
    research_agent_drain_grace_seconds: int = Field(default=30, ge=1, le=300)
    research_agent_drain_poll_timeout_ms: int = Field(default=500, ge=50, le=5000)
    research_agent_completion_max_attempts: int = Field(default=3, ge=1, le=10)
    llm_api_key: str = Field(
        default="",
        validation_alias=AliasChoices("NOTEWEAVE_RESEARCH_LLM_API_KEY"),
    )
    llm_base_url: str = Field(
        default="",
        validation_alias=AliasChoices("NOTEWEAVE_RESEARCH_LLM_BASE_URL"),
    )
    llm_model: str = Field(
        default="",
        validation_alias=AliasChoices("NOTEWEAVE_RESEARCH_LLM_MODEL"),
    )
    llm_timeout_seconds: int = Field(
        default=60,
        validation_alias=AliasChoices("NOTEWEAVE_RESEARCH_LLM_TIMEOUT_SECONDS"),
    )
    llm_max_attempts: int = Field(
        default=3,
        validation_alias=AliasChoices("NOTEWEAVE_RESEARCH_LLM_MAX_ATTEMPTS"),
    )
    llm_max_total_calls: int = Field(
        default=64,
        validation_alias=AliasChoices("NOTEWEAVE_RESEARCH_LLM_MAX_TOTAL_CALLS"),
    )
    llm_input_cost_per_million: float = Field(
        default=0.0,
        validation_alias=AliasChoices("NOTEWEAVE_RESEARCH_LLM_INPUT_COST_PER_MILLION"),
    )
    llm_output_cost_per_million: float = Field(
        default=0.0,
        validation_alias=AliasChoices("NOTEWEAVE_RESEARCH_LLM_OUTPUT_COST_PER_MILLION"),
    )
    llm_purpose_options: dict[str, dict[str, object]] = Field(
        default_factory=dict,
        validation_alias=AliasChoices("NOTEWEAVE_RESEARCH_LLM_PURPOSE_OPTIONS"),
    )
    llm_max_input_chars: int = Field(
        default=120000,
        validation_alias=AliasChoices("NOTEWEAVE_RESEARCH_LLM_MAX_INPUT_CHARS"),
    )
    llm_context_safety_buffer_chars: int = Field(
        default=8000,
        validation_alias=AliasChoices("NOTEWEAVE_RESEARCH_LLM_CONTEXT_SAFETY_BUFFER_CHARS"),
    )
    fetch_max_concurrency: int = 4
    fetch_timeout_seconds: int = 20
    fetch_max_retries: int = 3
    fetch_requests_per_second: float = 4.0
    research_enable_url_reader: bool = False
    research_jina_api_key: str = ""
    research_jina_base_url: str = "https://r.jina.ai/"

    model_config = SettingsConfigDict(env_prefix="NOTEWEAVE_")

    @model_validator(mode="after")
    def validate_research_agent_heartbeat_window(self) -> "Settings":
        interval = (
            self.research_agent_heartbeat_interval_seconds
            if self.research_agent_heartbeat_interval_seconds is not None
            else self.research_agent_lease_seconds / 4
        )
        if interval >= self.research_agent_lease_seconds / 3:
            raise ValueError("heartbeat interval must be less than lease_seconds / 3")
        failure_budget = (
            self.research_agent_heartbeat_failure_budget_seconds
            if self.research_agent_heartbeat_failure_budget_seconds is not None
            else interval * 2
        )
        if failure_budget + interval >= self.research_agent_lease_seconds:
            raise ValueError("heartbeat failure budget must leave one interval of lease safety margin")
        request_timeout = (
            self.research_agent_heartbeat_request_timeout_seconds
            if self.research_agent_heartbeat_request_timeout_seconds is not None
            else min(5.0, interval / 2)
        )
        if request_timeout >= interval:
            raise ValueError("heartbeat request timeout must be less than heartbeat interval")
        if self._is_production():
            if self.research_agent_fake_provider_enabled:
                raise ValueError("production forbids the deterministic fake research provider")
            if self.research_agent_fault_invalid_json:
                raise ValueError("production forbids research fault injection")
            if self.research_agent_fault_tamper_quote:
                raise ValueError("production forbids research quote-tamper injection")
            if self.research_agent_fault_crash_after_archive:
                raise ValueError("production forbids research crash injection")
            if not os.environ.get("NOTEWEAVE_RESEARCH_INTERNAL_AUTH_TOKEN", "").strip():
                raise ValueError("production requires NOTEWEAVE_RESEARCH_INTERNAL_AUTH_TOKEN")
            if not self.internal_auth_token.strip() or len(self.internal_auth_token.strip()) < 24:
                raise ValueError("production requires a dedicated research internal auth token with sufficient entropy")
            if not self.java_base_url.lower().startswith("https://"):
                raise ValueError("production requires NOTEWEAVE_JAVA_BASE_URL to use HTTPS")
            if self.kafka_security_protocol.upper() not in {"SSL", "SASL_SSL"}:
                raise ValueError("production requires Kafka SSL transport")
        return self

    def _is_production(self) -> bool:
        return self.environment.strip().lower() in {"prod", "production"}


def load_settings() -> Settings:
    return Settings()


def _default_worker_instance_id() -> str:
    configured = os.environ.get("NOTEWEAVE_RESEARCH_AGENT_WORKER_INSTANCE_ID", "").strip()
    if configured:
        return configured
    host = re.sub(r"[^a-z0-9]+", "-", socket.gethostname().lower()).strip("-") or "worker"
    return f"research-agent-{host}-{os.getpid()}-{secrets.token_hex(4)}"
