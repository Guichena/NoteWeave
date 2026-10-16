from pathlib import Path

from pydantic import Field, SecretStr, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


MAX_UPLOAD_BYTES = 256 * 1024 * 1024
MAX_OUTPUT_CHARS = 20_000_000


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="NOTEWEAVE_PARSE_", extra="ignore", populate_by_name=True)
    internal_auth_token: SecretStr = SecretStr("")
    max_upload_bytes: int = Field(default=MAX_UPLOAD_BYTES, ge=1, le=MAX_UPLOAD_BYTES,
        validation_alias="NOTEWEAVE_PARSE_MAX_FILE_BYTES")
    max_output_chars: int = Field(default=MAX_OUTPUT_CHARS, ge=1, le=MAX_OUTPUT_CHARS,
        validation_alias="NOTEWEAVE_PARSE_MAX_CHARS")
    max_elements: int = Field(default=100_000, ge=1, le=100_000)
    max_concurrency: int = Field(default=1, ge=1, le=4,
        validation_alias="NOTEWEAVE_PARSE_MAX_CONCURRENT_DOCUMENTS")
    document_timeout_seconds: float = Field(default=120.0, gt=0, le=600)
    upload_timeout_seconds: float = Field(default=120.0, gt=0, le=600)
    artifacts_path: Path | None = None
    temp_root: Path | None = None
    ocr_enabled: bool = True
    ocr_min_native_chars: int = Field(default=20, ge=1, le=1000)

    @field_validator("artifacts_path", "temp_root", mode="before")
    @classmethod
    def empty_path_is_unset(cls, value):
        return None if isinstance(value, str) and not value.strip() else value
