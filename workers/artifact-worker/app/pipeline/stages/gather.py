"""根据用户要求查询冻结资料范围，生成编号素材包。"""

from app.pipeline.evidence import build_evidence
from app.pipeline.schemas import schema_errors
from app.pipeline.stages import call_model


def gather(task, skill, options, client, passage_search):
    schema = {"type": "object", "properties": {"queries": {"type": "array", "minItems": 1, "maxItems": 5,
              "items": {"type": "string", "minLength": 1, "maxLength": 2000}}}, "required": ["queries"], "additionalProperties": False}
    query_plan = call_model(client, skill, "gather", {"requirement": task.input_payload.user_requirement,
                            "options": options, "source_titles": [s.title for s in task.source_scope]}, schema)
    if schema_errors(query_plan, schema):
        raise ValueError("取材查询格式不符合约定")
    passages = []
    if passage_search:
        for query in dict.fromkeys(query_plan["queries"]):
            response = passage_search(task.task_id, query, 20)
            if response.get("task_id") != task.task_id or response.get("input_snapshot_id") != task.input_snapshot_id:
                raise ValueError("检索响应不属于当前任务输入快照")
            passages.extend(response.get("passages", []))
    else:
        for source in task.source_scope:
            windows = source.material_windows or [{"content": source.sample_text}]
            for index, window in enumerate(windows):
                passages.append({**window, "source_id": source.source_id, "source_snapshot_id": source.source_snapshot_id,
                    "title": source.title, "content": window.get("content", window.get("text", "")),
                    "chunk_id": str(window.get("chunk_id") or window.get("window_id") or f"{source.source_id}:{index}")})
    return build_evidence(passages, task)
