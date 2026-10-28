"""验证五种形态的共用流程、失败修复与声明式扩展。"""

from copy import deepcopy
import json
from pathlib import Path
import shutil

import pytest
import yaml

from app.models import ArtifactTaskInput
from app.pipeline.evidence import build_evidence, evidence_errors
from app.pipeline.rules import contract_errors
from app.pipeline.runner import run
from app.pipeline.schemas import ROOT, load_schema, schema_errors
from app.pipeline.skills import SkillCatalog


def task(name, catalog):
    return ArtifactTaskInput.model_validate({
        "task_id": "pipeline-task", "workspace_id": "workspace", "target_id": "job", "input_snapshot_id": "input",
        "catalog_digest": catalog.digest,
        "source_scope": [{"source_id": "source", "source_snapshot_id": "snapshot", "title": "缓存资料",
            "material_windows": [{"chunk_id": "chunk", "content": "缓存有效期为30秒。过期后重新读取数据。"}]}],
        "control_pack": {"pack_type": "artifact", "target_key": name, "task_neighborhood": "ARTIFACT"},
        "input_payload": {"skill_key": name, "user_requirement": "根据资料制作学习材料", "inputs": {}}})


class Model:
    """只根据输入素材返回内容，测试真实流水线对输出的处理。"""
    def __init__(self, kind, *, bad=None, repair=True):
        self.kind = kind
        self.bad = bad
        self.repair = repair
        self.calls = []
        self.items = {}

    def complete_json(self, purpose, payload):
        self.calls.append((purpose, payload))
        stage = purpose.rsplit(".", 1)[-1]
        data = payload["inputs"]
        if stage == "gather":
            return json.dumps({"queries": ["缓存有效期"]})
        if stage == "plan":
            headings = data["contract"].get("required_headings", [])
            count = int(data["options"].get("count", max(3, data["contract"].get("sections", {}).get("min", 1))))
            return json.dumps({"title": "缓存学习材料", "units": [
                {"id": f"u{i}", "heading": headings[i] if i < len(headings) else f"主题{i}", "anchors": ["s0001"]}
                for i in range(count)], **({"columns": [{"id": "concept", "label": "概念"}, {"id": "value", "label": "说明"}]}
                                         if self.kind == "TABLE" else {})})
        if stage == "draft":
            unit = data["unit"]
            fact = {"text": data["evidence"]["sentences"][0]["text"], "anchors": ["s0001"]}
            value = {"id": unit["id"]}
            if self.kind == "DOC":
                value.update(heading=unit["heading"], blocks=[{"id": "b1", "type": "paragraph",
                    "statements": [{"id": "t1", **fact}]}])
            elif self.kind == "QUIZ":
                value.update(prompt=fact, options=[{"id": "a", "text": "30秒"}, {"id": "b", "text": "60秒"}],
                             answer="a", explanation=fact)
            elif self.kind == "CARDS":
                value.update(front="缓存的有效期是多少？", back=fact)
            elif self.kind == "TREE":
                value.update(label=unit["heading"], description=fact, children=[])
            else:
                value.update(cells=[fact, fact])
            self.items[unit["id"]] = deepcopy(value)
            if unit["id"] == "u0" and self.bad:
                if self.bad == "schema": value["extra"] = True
                if self.bad == "anchor": next(iter_facts(value))["anchors"] = ["missing"]
                if self.bad == "number": next(iter_facts(value))["text"] = "缓存有效期为99秒。"
                if self.bad == "rule" and self.kind == "QUIZ": value["answer"] = "missing"
            return json.dumps({"item": value})
        if stage == "check":
            return json.dumps({"items": [self.items[key] for key in data["failures"]] if self.repair else []})
        raise AssertionError(stage)


def iter_facts(value):
    from app.pipeline.evidence import facts
    return facts(value)


@pytest.mark.parametrize("name", ["briefing", "study-guide", "blog-post", "faq", "timeline", "custom-report", "course-notes", "quiz", "flashcards", "mind-map", "data-table"])
def test_all_declared_skills_use_five_stages_and_legacy_projection(name, monkeypatch, tmp_path):
    monkeypatch.setenv("NOTEWEAVE_MCP_SANDBOX_ROOT", str(tmp_path))
    catalog = SkillCatalog()
    skill = catalog.resolve(name)
    model = Model(skill.kind)
    events, result = run(task(name, catalog), client=model, catalog=catalog)
    payload = result.result_payload
    assert schema_errors(payload["ir"], load_schema(skill.kind)) == []
    assert evidence_errors(payload["ir"], payload["evidence"]) == []
    assert contract_errors(payload["ir"], skill.contract) == []
    assert list(payload["pipeline_stats"]["stages"]) == ["gather", "plan", "draft", "check", "render"]
    assert len(events) == 5
    assert payload["content_ir"]["schema_version"] == "artifact-content-v1"
    assert payload["candidate"]["catalog_digest"] == catalog.digest
    assert result.citations[0]["source_id"] == "source"
    if skill.kind == "TABLE":
        file = payload["candidate"]["required_files"][-1]
        assert file["role"] == "PRIMARY_CSV"
        csv = (tmp_path / "bilibili-render-pdf/exports/pipeline-task/content.csv").read_text(encoding="utf-8-sig")
        assert csv.startswith("概念,说明\n")


@pytest.mark.parametrize("bad", ["schema", "anchor", "number"])
def test_failed_units_are_repaired_in_one_combined_call(bad):
    catalog = SkillCatalog()
    model = Model("DOC", bad=bad)
    _, result = run(task("briefing", catalog), client=model, catalog=catalog)
    assert result.result_payload["pipeline_stats"]["repair_calls"] == 1
    repairs = [p for purpose, p in model.calls if purpose.endswith(".check")]
    assert len(repairs) == 1
    assert list(repairs[0]["inputs"]["failures"]) == ["u0"]
    assert len(result.result_payload["ir"]["sections"]) == 3


def test_unrepairable_units_are_removed_without_changing_valid_units(tmp_path):
    catalog = copy_catalog(tmp_path)
    path = catalog.directory / "blog-post/SKILL.md"
    path.write_text(path.read_text(encoding="utf-8").replace("min: 3", "min: 1"), encoding="utf-8")
    catalog = SkillCatalog(catalog.directory, catalog.schemas)
    _, result = run(task("blog-post", catalog), client=Model("DOC", bad="anchor", repair=False), catalog=catalog)
    assert result.result_payload["pipeline_stats"]["deleted_units"] == ["u0"]
    assert [s["id"] for s in result.result_payload["ir"]["sections"]] == ["u1", "u2"]


def copy_catalog(path):
    shutil.copytree(ROOT / "skills", path / "skills")
    shutil.copytree(ROOT / "schemas", path / "schemas")
    return SkillCatalog(path / "skills", path / "schemas")


def test_adding_only_one_skill_file_lists_and_generates_new_product(tmp_path):
    catalog = copy_catalog(tmp_path)
    original = (catalog.directory / "study-guide/SKILL.md").read_text(encoding="utf-8")
    # 只新增这个文件，加载器与执行器没有任何针对新名称的分支。
    new_path = catalog.directory / "new-learning-product/SKILL.md"
    new_path.parent.mkdir()
    new_path.write_text(original.replace("name: study-guide", "name: new-learning-product").replace("- study_guide", "- new_learning_alias"), encoding="utf-8")
    reloaded = SkillCatalog(catalog.directory, catalog.schemas)
    assert reloaded.resolve("new_learning_alias").name == "new-learning-product"
    _, result = run(task("new-learning-product", reloaded), client=Model("DOC"), catalog=reloaded)
    assert result.result_payload["ir"]["kind"] == "DOC"
    assert "缓存" in result.result_payload["markdown"]


@pytest.mark.parametrize("old,new", [("report_draft", "briefing"), ("knowledge_blog", "blog-post"),
                                    ("quiz_pack", "quiz"), ("mindmap_from_workspace", "mind-map")])
def test_old_names_resolve_to_new_skills(old, new):
    assert SkillCatalog().resolve(old).name == new


@pytest.mark.parametrize("change", ["name", "kind", "contract", "option"])
def test_invalid_skills_fail_when_loading(tmp_path, change):
    catalog = copy_catalog(tmp_path)
    path = catalog.directory / "briefing/SKILL.md"
    parts = path.read_text(encoding="utf-8").split("---", 2)
    header = yaml.safe_load(parts[1])
    if change == "name": header["name"] = "wrong"
    if change == "kind": header["kind"] = "UNREGISTERED"
    if change == "contract": header["contract"]["unknown"] = True
    if change == "option": header["options"]["length"]["default"] = "INVALID"
    path.write_text("---\n" + yaml.safe_dump(header, allow_unicode=True) + "---\n" + parts[2], encoding="utf-8")
    with pytest.raises(ValueError): SkillCatalog(catalog.directory, catalog.schemas)


def test_scope_snapshot_and_number_checks_reject_invalid_material():
    catalog = SkillCatalog()
    value = task("briefing", catalog)
    with pytest.raises(ValueError, match="冻结资料范围"):
        build_evidence([{"source_id": "outside", "content": "外部资料"}], value)
    evidence = build_evidence([{"source_id": "source", "source_snapshot_id": "snapshot", "chunk_id": "c",
                               "content": "有效期30秒。"}], value)
    assert evidence_errors({"text": "有效期60秒。", "anchors": ["s0001"]}, evidence)
    assert evidence_errors({"text": "有效期30秒。", "anchors": ["missing"]}, evidence)


def test_entry_defaults_to_v2_and_passes_task_retrieval(monkeypatch):
    import app.runner
    catalog = SkillCatalog()
    monkeypatch.delenv("NOTEWEAVE_ARTIFACT_PIPELINE", raising=False)
    monkeypatch.setattr(app.runner, "build_default_llm_client", lambda: Model("DOC"))
    called = []
    def retrieve(task_id, query, limit):
        called.append((task_id, query, limit))
        return {"task_id": task_id, "input_snapshot_id": "input", "passages": [{"chunk_id": "c",
            "source_id": "source", "source_snapshot_id": "snapshot", "content": "缓存有效期为30秒。", "title": "资料"}]}
    _, result = app.runner.run_artifact_task(task("briefing", catalog), passage_search=retrieve)
    assert called
    assert result.result_payload["ir"]["kind"] == "DOC"


def test_contract_failure_deletes_only_the_failed_question():
    catalog = SkillCatalog()
    _, result = run(task("quiz", catalog), client=Model("QUIZ", bad="rule", repair=False), catalog=catalog)
    assert result.result_payload["pipeline_stats"]["deleted_units"] == ["u0"]
    assert len(result.result_payload["ir"]["questions"]) == 9


def test_csv_download_keeps_the_existing_path_checks(monkeypatch, tmp_path):
    from fastapi import HTTPException
    import app.main
    monkeypatch.setattr(app.main, "resolve_mcp_sandbox_root", lambda settings: tmp_path)
    path = tmp_path / "bilibili-render-pdf/exports/csv-task/content.csv"
    path.parent.mkdir(parents=True)
    path.write_text("概念,说明\n缓存,有效期30秒\n", encoding="utf-8")
    response = app.main.download_task_export("csv-task", "content.csv")
    assert response.media_type == "text/csv; charset=UTF-8"
    assert Path(response.path) == path
    with pytest.raises(HTTPException):
        app.main.download_task_export("csv-task", "../content.csv")


def test_retrieval_cannot_substitute_another_task_input():
    catalog = SkillCatalog()
    def wrong_snapshot(task_id, query, limit):
        return {"task_id": task_id, "input_snapshot_id": "other-input", "passages": []}
    with pytest.raises(ValueError, match="输入快照"):
        run(task("briefing", catalog), client=Model("DOC"), catalog=catalog, passage_search=wrong_snapshot)


def test_decimal_numbers_are_not_split_between_sentences():
    catalog = SkillCatalog()
    evidence = build_evidence([{"source_id": "source", "source_snapshot_id": "snapshot", "chunk_id": "c",
        "content": "版本3.12支持该特性。比例为1.5%。"}], task("briefing", catalog))
    assert [v["text"] for v in evidence["sentences"]] == ["版本3.12支持该特性。", "比例为1.5%。"]
    assert evidence_errors({"text": "版本3.13支持该特性。", "anchors": ["s0001"]}, evidence)


def test_yaml_duplicate_keys_are_rejected(tmp_path):
    catalog = copy_catalog(tmp_path)
    path = catalog.directory / "briefing/SKILL.md"
    text = path.read_text(encoding="utf-8").replace("kind: DOC", "kind: DOC\nkind: DOC")
    path.write_text(text, encoding="utf-8")
    with pytest.raises(ValueError, match="字段重复"):
        SkillCatalog(catalog.directory, catalog.schemas)


def test_csv_render_cannot_write_outside_the_task_directory(monkeypatch, tmp_path):
    monkeypatch.setenv("NOTEWEAVE_MCP_SANDBOX_ROOT", str(tmp_path))
    catalog = SkillCatalog()
    value = task("data-table", catalog)
    value.task_id = "../../outside"
    with pytest.raises(ValueError, match="导出路径"):
        run(value, client=Model("TABLE"), catalog=catalog)


def test_catalog_digest_is_stable_across_windows_and_posix_line_endings(tmp_path):
    catalog = copy_catalog(tmp_path)
    for path in [*catalog.directory.glob("*/SKILL.md"), *catalog.schemas.glob("*.json")]:
        path.write_bytes(path.read_bytes().replace(b"\r\n", b"\n").replace(b"\n", b"\r\n"))
    assert SkillCatalog(catalog.directory, catalog.schemas).digest == catalog.digest


def test_schema_loader_imports_under_the_shallow_container_path(monkeypatch):
    monkeypatch.setenv("NOTEWEAVE_SCHEMAS_DIR", str(ROOT / "schemas"))
    namespace = {"__file__": "/app/app/pipeline/schemas.py"}
    source = ROOT / "workers/artifact-worker/app/pipeline/schemas.py"
    exec(compile(source.read_text(encoding="utf-8"), namespace["__file__"], "exec"), namespace)
    assert namespace["load_schema"]("DOC")["properties"]["kind"] == {"const": "DOC"}
