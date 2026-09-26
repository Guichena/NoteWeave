from pathlib import Path

import os

from pydantic import AliasChoices, Field, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    worker_type: str = "artifact"
    java_base_url: str = "http://localhost:8081"
    environment: str = "local"
    internal_auth_token: str = Field(
        default="",
        validation_alias=AliasChoices("internal_auth_token", "NOTEWEAVE_ARTIFACT_INTERNAL_AUTH_TOKEN", "NOTEWEAVE_INTERNAL_AUTH_TOKEN"),
    )
    callback_secret: str = Field(
        default="",
        validation_alias=AliasChoices("NOTEWEAVE_ARTIFACT_CALLBACK_SECRET"),
    )
    kafka_bootstrap_servers: str = "localhost:9092"
    kafka_security_protocol: str = "PLAINTEXT"
    kafka_sasl_mechanism: str = "PLAIN"
    kafka_sasl_username: str = ""
    kafka_sasl_password: str = ""
    kafka_artifact_topic: str = "noteweave.artifact.job"
    kafka_artifact_dlq_topic: str = "noteweave.artifact.job.dlq"
    kafka_artifact_group_id: str = "noteweave-artifact-worker"
    kafka_artifact_max_poll_interval_seconds: int = Field(default=4200, ge=60, le=21_600)
    kafka_consume_max_attempts: int = Field(default=3, ge=1, le=20)
    artifact_consumer_enabled: bool = True
    artifact_repository_backend: str = "memory"
    artifact_repository_file_path: str = "runtime/artifact-worker/repository.json"
    artifact_customization_file_path: str = "runtime/artifact-worker/customizations.json"
    artifact_runtime_state_file_path: str = "runtime/artifact-worker/runtime-state.json"
    artifact_wait_queue_file_path: str = "runtime/artifact-worker/wait-queue.json"
    mcp_sandbox_root: str = "runtime"
    debug_routes_enabled: bool = False
    llm_api_key: str = Field(
        default="",
        validation_alias=AliasChoices("NOTEWEAVE_ARTIFACT_LLM_API_KEY"),
    )
    llm_base_url: str = Field(
        default="",
        validation_alias=AliasChoices("NOTEWEAVE_ARTIFACT_LLM_BASE_URL"),
    )
    llm_model: str = Field(
        default="",
        validation_alias=AliasChoices("NOTEWEAVE_ARTIFACT_LLM_MODEL"),
    )
    llm_timeout_seconds: int = Field(
        default=60,
        validation_alias=AliasChoices("NOTEWEAVE_ARTIFACT_LLM_TIMEOUT_SECONDS"),
    )
    mcp_process_timeout_seconds: int = Field(default=3600, ge=1, le=21_600)

    model_config = SettingsConfigDict(env_prefix="NOTEWEAVE_")

    @model_validator(mode="after")
    def validate_production_transport(self) -> "Settings":
        if self.kafka_artifact_topic.strip() != "noteweave.artifact.job":
            raise ValueError("Artifact command topic must be noteweave.artifact.job")
        if self.environment.strip().lower() in {"prod", "production"}:
            if self.artifact_repository_backend.strip().lower() != "file":
                raise ValueError("production requires the file artifact repository backend")
            if self.debug_routes_enabled:
                raise ValueError("production forbids artifact worker debug routes")
            if not os.environ.get("NOTEWEAVE_ARTIFACT_INTERNAL_AUTH_TOKEN", "").strip():
                raise ValueError("production requires NOTEWEAVE_ARTIFACT_INTERNAL_AUTH_TOKEN")
            if not self.internal_auth_token.strip() or len(self.internal_auth_token.strip()) < 24:
                raise ValueError("production requires a dedicated artifact internal auth token with sufficient entropy")
            if not self.java_base_url.lower().startswith("https://"):
                raise ValueError("production requires NOTEWEAVE_JAVA_BASE_URL to use HTTPS")
            if not self.callback_secret.strip() or len(self.callback_secret.strip()) < 24:
                raise ValueError("production requires a dedicated artifact callback secret with sufficient entropy")
            if not self.artifact_consumer_enabled:
                raise ValueError("production requires the durable Artifact Kafka consumer")
            if self.kafka_artifact_max_poll_interval_seconds <= self.mcp_process_timeout_seconds:
                raise ValueError(
                    "production requires Artifact Kafka max poll interval to exceed the MCP process timeout"
                )
        return self


def load_settings() -> Settings:
    return Settings()


def resolve_mcp_sandbox_root(settings: Settings | None = None) -> Path:
    configured = Path((settings or load_settings()).mcp_sandbox_root).expanduser()
    if configured.is_absolute():
        return configured.resolve()
    worker_root = Path(__file__).resolve().parents[1]
    return (worker_root / configured).resolve()
