from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    worker_type: str = "research"
    java_base_url: str = "http://localhost:8081"
    kafka_bootstrap_servers: str = "localhost:9092"
    kafka_research_topic: str = "noteweave.research.run"
    kafka_group_id: str = "noteweave-research-worker"

    model_config = SettingsConfigDict(env_prefix="NOTEWEAVE_")


def load_settings() -> Settings:
    return Settings()
