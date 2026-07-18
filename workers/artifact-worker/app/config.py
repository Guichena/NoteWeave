from pydantic import AliasChoices, Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    worker_type: str = "artifact"
    java_base_url: str = "http://localhost:8081"
    internal_auth_token: str = ""
    kafka_bootstrap_servers: str = "localhost:9092"
    artifact_repository_backend: str = "memory"
    artifact_repository_file_path: str = ".artifact-worker-repository.json"
    artifact_customization_file_path: str = ".artifact-worker-customizations.json"
    artifact_runtime_state_file_path: str = ".artifact-worker-runtime-state.json"
    artifact_wait_queue_file_path: str = ".artifact-worker-wait-queue.json"
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


def load_settings() -> Settings:
    return Settings()
