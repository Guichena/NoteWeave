"""产物任务统一进入声明式 Skill 的五段流水线。"""

from app.llm_client import build_default_llm_client
from app.models import ArtifactTaskInput, ArtifactProgressEvent, ArtifactTaskResult
from app.pipeline.runner import run
from app.pipeline.skills import SkillCatalog


# 启动时检查声明；任务执行时重新读取并核对冻结摘要。
SkillCatalog()


def run_artifact_task(task_input: ArtifactTaskInput, *, passage_search=None, frame_reader=None) -> tuple[list[ArtifactProgressEvent], ArtifactTaskResult]:
    return run(task_input, passage_search=passage_search, frame_reader=frame_reader, client=build_default_llm_client())
