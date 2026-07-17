"""Versioned transport-only Kafka command contract for atomic Agent execution."""

from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field


class _StrictContract(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)


class ResearchAgentCommand(_StrictContract):
    """Transport identity only; all task scope is fetched after server-side claim."""

    schema_version: Literal["research-agent-command.v1"]
    command_id: str = Field(min_length=1, max_length=128)
    research_run_id: str = Field(min_length=1, max_length=64)
    agent_task_id: str = Field(min_length=1, max_length=64)
    idempotency_key: str = Field(min_length=1, max_length=200)
    delivery_attempt: int = Field(default=1, ge=1, le=1000)
