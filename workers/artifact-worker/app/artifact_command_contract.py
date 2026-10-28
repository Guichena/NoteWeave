from typing import Literal

from pydantic import BaseModel, ConfigDict, Field


class ArtifactCommand(BaseModel):
    model_config = ConfigDict(extra="forbid")

    schema_version: Literal["artifact-command.v1"]
    task_id: str = Field(min_length=1)
    delivery_token: str = Field(min_length=1)
