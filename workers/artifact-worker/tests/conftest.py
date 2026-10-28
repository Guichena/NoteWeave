"""共用流水线测试使用确定性模型，媒体测试保留各自替身。"""

import pytest
import app.runner as runner_module


@pytest.fixture(autouse=True)
def artifact_test_model(monkeypatch):
    from tests.pipeline.test_pipeline import Model
    monkeypatch.setattr(runner_module, "build_default_llm_client", lambda: Model("DOC"))
