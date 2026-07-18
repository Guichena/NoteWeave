from app.main import app


def test_research_worker_http_surface_should_not_expose_legacy_execution_routes() -> None:
    paths = {route.path for route in app.routes}

    assert "/health" in paths
    assert "/debug/run-task" not in paths
    assert "/tasks/{task_id}/run" not in paths
