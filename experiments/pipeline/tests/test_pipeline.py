"""全部使用临时目录和替身；不包含项目质量样例，不调用实际模型。"""
from copy import deepcopy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch
import zipfile

from experiments.pipeline.adapters import AdapterError, DoclingHttpAdapter, MineruCliAdapter, normalize_docling, normalize_mineru, read_mineru_zip
from experiments.pipeline.contracts import ContractError, document, element, loads, local_path, validate_document, validate_manifest
from experiments.pipeline.metrics import cer, headings, reading_order, tables
from experiments.pipeline.pipeline_eval import evaluate, main
from experiments.pipeline.tables import table_cells, visible_table_text


SAMPLE = {"sample_id": "unit-fixture", "input_path": "fixture.pdf", "sha256": "a" * 64, "mime_type": "application/pdf", "gold_text_path": None, "gold_layout_path": None}


def docling_payload():
    return {"schema_version": 1, "workspace_id": "pipeline-eval", "source_id": "unit-fixture", "snapshot_id": "unit-fixture",
        "sha256": "a" * 64, "mime_type": "application/pdf", "markdown": "真实原文", "page_count": 1,
        "elements": [{"element_type": "TEXT", "text": "真实原文", "page": 1, "bbox": None, "heading_level": None}],
        "parser": "DOCLING", "truncated": False, "warnings": []}


def mineru_payload():
    return {"pages": [{"page_idx": 0, "blocks": [{"type": "doc_title", "content": "标题", "level": 1},
        {"type": "table", "content": "<table><tr><td>A</td><td>B</td></tr></table>", "captions": [{"content": "表格说明"}]}]}],
        "metadata": {"producer": {"name": "mineru", "version": "4.0.unit-fixture"}}, "extensions": {}, "is_full_document": True}


def mineru_middle():
    return {"schema": "docvortex.middle", "schema_version": "2.0", "pages": [{"page_idx": 0, "blocks": [
        {"type": "doc_title", "content": [{"type": "text", "content": "标题", "styles": []}], "level": 1},
        {"type": "table", "content": [{"type": "table_body", "content": "<table><tr><td>A</td><td>B</td></tr></table>"},
            {"type": "table_caption", "content": [{"type": "text", "content": "表格说明", "styles": []}]}]}]}]}


class ContractTests(unittest.TestCase):
    def test_duplicate_nonfinite_trailing_and_unknown_manifest_fields_fail(self):
        for data in ('{"a":1,"a":2}', '{"a":NaN}', '{}{}'):
            with self.assertRaises(ValueError):
                loads(data)
        value = {"schema_version": 1, "suite_id": "fixture", "expected_sample_count": 30, "sample_root": ".", "samples": [], "unexpected": 1}
        with self.assertRaises(ContractError):
            validate_manifest(value)

    def test_unknown_bbox_origin_and_invalid_confidence_fail(self):
        value = document(SAMPLE, "GOLD", [element("TEXT", "正文", page=1)], pages=1)
        for confidence in (True, -0.1, 1.1, float("nan")):
            changed = deepcopy(value)
            changed["elements"][0]["ocr_confidence"] = confidence
            with self.assertRaises(ContractError):
                validate_document(changed)

    def test_paths_cannot_escape_explicit_root(self):
        with tempfile.TemporaryDirectory() as directory:
            for name in ("../outside.pdf", str(Path(directory).parent / "outside.pdf"), "https://example.com/x.pdf", "C:drive.pdf"):
                with self.assertRaises(ContractError):
                    local_path(Path(directory), name)

    def test_duplicate_sample_ids_and_bool_integers_fail(self):
        manifest = {"schema_version": 1, "suite_id": "fixture", "expected_sample_count": 30, "sample_root": ".", "samples": [SAMPLE, SAMPLE]}
        with self.assertRaises(ContractError):
            validate_manifest(manifest)
        value = document(SAMPLE, "GOLD", [element("TEXT", "正文", page=1)], pages=1)
        value["elements"][0]["page"] = True
        with self.assertRaises(ContractError):
            validate_document(value)

    def test_sample_id_matches_parse_worker_64_character_limit(self):
        manifest = {"schema_version": 1, "suite_id": "x" * 128, "expected_sample_count": 30, "sample_root": ".", "samples": [dict(SAMPLE, sample_id="x" * 65)]}
        with self.assertRaises(ContractError):
            validate_manifest(manifest)


class MetricTests(unittest.TestCase):
    def test_cer_reports_real_edit_distance_without_clamping(self):
        self.assertEqual(cer("abc", "adc")["edit_distance"], 1)
        self.assertAlmostEqual(cer("abc", "adc")["value"], 1 / 3)
        self.assertEqual(cer("a", "aaaa")["value"], 3)
        self.assertEqual(cer("e\u0301 \n缓存", "é缓存")["value"], 0)

    def test_cer_empty_reference_and_cpu_budget_are_explicit(self):
        self.assertIsNone(cer("", "text")["value"])
        self.assertEqual(cer("a" * 20, "b" * 20, 20)["reason"], "EDIT_DISTANCE_BUDGET_EXCEEDED")
        self.assertEqual(cer("x" * 5000 + "a", "x" * 5000 + "b", 1)["edit_distance"], 1)

    def test_heading_multiset_counts_duplicates_and_real_levels(self):
        gold = [element("SECTION_HEADER", "章节", page=1, heading=2)] * 2
        predicted = [element("SECTION_HEADER", "章节", page=1, heading=3)]
        result = headings(gold, predicted)
        self.assertEqual(result["matched"], 1)
        self.assertEqual(result["recall"], 0.5)
        self.assertEqual(result["hierarchy_accuracy"], 0)

    def test_missing_heading_levels_are_unknown(self):
        result = headings([element("TITLE", "章节", page=1, heading=1)], [element("TITLE", "章节", page=1)])
        self.assertIsNone(result["hierarchy_accuracy"])
        self.assertEqual(result["hierarchy_reason"], "NO_MATCHED_KNOWN_LEVELS")

    def test_order_reversal_has_measured_inversions_and_coverage(self):
        gold = [element("TEXT", text, page=1) for text in ("甲", "乙", "丙")]
        result = reading_order(gold, list(reversed(gold)))
        self.assertEqual(result["inversions"], 3)
        self.assertEqual(result["inversion_rate"], 1)
        self.assertEqual(result["coverage"], 1)

    def test_ambiguous_or_unknown_order_never_produces_perfect_score(self):
        a = element("TEXT", "重复", page=1)
        result = reading_order([a, a], [a, a])
        self.assertIsNone(result["inversion_rate"])
        self.assertEqual(result["coverage"], 0)
        unknown = element("CAPTION", "说明", page=1, order_known=False)
        self.assertIsNone(reading_order([a, unknown], [a, unknown])["inversion_rate"])

    def test_low_coverage_keeps_real_inversions_but_has_no_order_score(self):
        gold = [element("TEXT", str(index), page=1) for index in range(10)]
        result = reading_order(gold, gold[:2])
        self.assertEqual(result["coverage"], 0.2)
        self.assertEqual(result["inversions"], 0)
        self.assertIsNone(result["inversion_rate"])
        self.assertEqual(result["reason"], "LOW_MATCH_COVERAGE")

    def test_table_cells_structure_and_text_are_measured_separately(self):
        gold = [element("TABLE", "A B", page=1, cells=[["A", "B"]])]
        parsed = [element("TABLE", "A C", page=1, cells=[["A", "C"]])]
        result = tables(gold, parsed)
        self.assertEqual(result["structure_accuracy"], 1)
        self.assertEqual(result["cell_exact_accuracy"], 0.5)
        self.assertIsNone(tables(gold, [element("TABLE", "A B", page=1)])["structure_accuracy"])


class TableTests(unittest.TestCase):
    def test_markdown_and_html_simple_tables_have_equal_cells(self):
        markdown = "|A|B|\n|---|---|\n|C|D|"
        html = "<table><tr><th>A</th><th>B</th></tr><tr><td>C</td><td>D</td></tr></table>"
        self.assertEqual(table_cells(markdown), table_cells(html))
        self.assertEqual(visible_table_text(markdown, table_cells(markdown)), "A B\nC D")

    def test_escaped_pipe_is_one_cell(self):
        self.assertEqual(table_cells("| A\\|B | C |\n|---|---|"), [["A|B", "C"]])

    def test_merged_or_nested_table_structure_stays_unknown(self):
        for text in ('<table><tr><td rowspan="2">A</td><td>B</td></tr></table>',
                '<table><tr><td colspan="2">A</td></tr></table>', '<table><tr><td><table><tr><td>A</td></tr></table></td></tr></table>',
                '<table><tr><td>A</td></tr></table><table><tr><td>B</td></tr></table>'):
            self.assertIsNone(table_cells(text))
            self.assertIn("A", visible_table_text(text, None))


class AdapterTests(unittest.TestCase):
    def test_docling_unknown_version_origin_and_confidence_remain_null(self):
        value = normalize_docling(SAMPLE, docling_payload())
        self.assertIsNone(value["parser_version"])
        self.assertIsNone(value["elements"][0]["text_origin"])
        self.assertIsNone(value["elements"][0]["ocr_confidence"])

    def test_docling_ocr_source_is_preserved_and_identity_is_strict(self):
        payload = docling_payload()
        payload["elements"][0].update(text_origin="OCR", ocr_confidence=0.7)
        self.assertEqual(normalize_docling(SAMPLE, payload)["elements"][0]["ocr_confidence"], 0.7)
        payload["snapshot_id"] = "foreign"
        with self.assertRaises(ContractError):
            normalize_docling(SAMPLE, payload)

    def test_optional_page_sources_is_validated_without_fabricating_values(self):
        payload = docling_payload()
        payload["page_sources"] = [{"page": 1, "text_origin": None, "ocr_confidence": None,
            "native_char_count": None, "has_images": None, "ocr_attempted": False}]
        self.assertIsNone(normalize_docling(SAMPLE, payload)["elements"][0]["text_origin"])
        payload["page_sources"][0]["ocr_confidence"] = 0.7
        with self.assertRaises(ContractError):
            normalize_docling(SAMPLE, payload)
        payload["page_sources"][0].update(text_origin="OCR", ocr_confidence=0.7)
        normalize_docling(SAMPLE, payload)
        payload["page_sources"].append(deepcopy(payload["page_sources"][0]))
        with self.assertRaises(ContractError):
            normalize_docling(SAMPLE, payload)

    def test_mineru_four_protocol_keeps_real_version_and_caption_order_unknown(self):
        value = normalize_mineru(SAMPLE, mineru_payload())
        self.assertEqual(value["parser_version"], "4.0.unit-fixture")
        self.assertEqual(value["elements"][1]["table_cells"], [["A", "B"]])
        self.assertFalse(value["elements"][2]["reading_order_known"])

    def test_middle_source_spans_preserve_literal_symbols_without_markdown_penalty(self):
        rendered = {"pages": [{"page_idx": 0, "blocks": [{"type": "text", "content": "**正文** \\* literal"}]}],
            "metadata": {"producer": {"name": "mineru", "version": "4.0.unit-fixture"}}, "is_full_document": True}
        middle = {"schema": "docvortex.middle", "schema_version": "2.0", "pages": [{"page_idx": 0, "blocks": [
            {"type": "text", "content": [{"type": "text", "content": "正文", "styles": ["bold"]},
                {"type": "text", "content": " * literal", "styles": []}]}]}]}
        self.assertEqual(normalize_mineru(SAMPLE, rendered, middle)["elements"][0]["text"], "正文 * literal")
        middle["pages"][0]["blocks"][0]["type"] = "list"
        with self.assertRaises(ContractError):
            normalize_mineru(SAMPLE, rendered, middle)

    def test_partial_legacy_or_unknown_mineru_protocol_is_rejected(self):
        for change in ({"is_full_document": False}, {"metadata": {"producer": {"name": "mineru", "version": "3.0"}}}):
            payload = mineru_payload()
            payload.update(change)
            with self.assertRaises(ContractError):
                normalize_mineru(SAMPLE, payload)

    def test_zip_reads_only_one_bounded_structured_member(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "raw.zip"
            with zipfile.ZipFile(path, "w") as archive:
                archive.writestr("result/structured_content.json", json.dumps(mineru_payload()))
                archive.writestr("result/images/unused.bin", b"not-executed")
            self.assertEqual(read_mineru_zip(path), mineru_payload())
            with zipfile.ZipFile(path, "a") as archive:
                archive.writestr("another/structured_content.json", "{}")
            with self.assertRaises(ContractError):
                read_mineru_zip(path)

    def test_zip_traversal_member_is_rejected_without_extracting(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "raw.zip"
            with zipfile.ZipFile(path, "w") as archive:
                archive.writestr("../structured_content.json", "{}")
            with self.assertRaises(ContractError):
                read_mineru_zip(path)
            self.assertFalse((Path(directory).parent / "structured_content.json").exists())

    def test_docling_streams_body_and_does_not_follow_redirect(self):
        with tempfile.TemporaryDirectory() as directory:
            source, raw = Path(directory) / "fixture.pdf", Path(directory) / "raw.json"
            source.write_bytes(b"fixture")
            response = Mock(status=302)
            response.read.return_value = b"redirect"
            connection = Mock()
            connection.getresponse.return_value = response
            with patch("experiments.pipeline.adapters.http.client.HTTPConnection", return_value=connection):
                with self.assertRaises(AdapterError) as error:
                    DoclingHttpAdapter(token="unit-only-token").parse(SAMPLE, source, raw)
            self.assertEqual(error.exception.code, "DOCLING_HTTP_302")
            self.assertEqual(connection.request.call_count, 1)
            self.assertTrue(hasattr(connection.request.call_args.kwargs["body"], "read"))

    def test_mineru_command_is_stateless_full_document_and_uses_no_shell(self):
        with tempfile.TemporaryDirectory() as directory:
            source, raw = Path(directory) / "fixture.pdf", Path(directory) / "raw.zip"
            source.write_bytes(b"fixture")
            def write_output(arguments, **kwargs):
                self.assertNotIn("shell", kwargs)
                self.assertEqual(arguments[:2], ["mineru-kit", "parse"])
                self.assertEqual(arguments[-2:], ["--pages", "all"])
                with zipfile.ZipFile(arguments[arguments.index("-o") + 1], "w") as archive:
                    archive.writestr("structured_content.json", json.dumps(mineru_payload()))
                    archive.writestr("middle_json.json", json.dumps(mineru_middle()))
                return Mock(returncode=0)
            with patch("experiments.pipeline.adapters.subprocess.run", side_effect=write_output):
                self.assertEqual(MineruCliAdapter().parse(SAMPLE, source, raw)["parser"], "MINERU")


class RunnerTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.root = Path(self.directory.name)
        self.addCleanup(self.directory.cleanup)

    def manifest(self, count):
        samples = []
        for index in range(count):
            sample = dict(SAMPLE, sample_id="unit-" + str(index), input_path=str(index) + ".pdf", gold_text_path=str(index) + ".txt", gold_layout_path=str(index) + ".json")
            content = ("unit-fixture-" + str(index)).encode()
            sample["sha256"] = hashlib.sha256(content).hexdigest()
            (self.root / sample["input_path"]).write_bytes(content)
            (self.root / sample["gold_text_path"]).write_text("测试标题\n资料正文", encoding="utf-8")
            layout = document(sample, "GOLD", [element("TITLE", "测试标题", page=1, heading=1), element("TEXT", "资料正文", page=1)], pages=1)
            (self.root / sample["gold_layout_path"]).write_text(json.dumps(layout, ensure_ascii=False), encoding="utf-8")
            samples.append(sample)
        manifest = {"schema_version": 1, "suite_id": "unit-suite", "expected_sample_count": 30, "sample_root": ".", "samples": samples}
        path = self.root / "manifest.json"
        path.write_text(json.dumps(manifest), encoding="utf-8")
        return path

    def adapters(self):
        result = {}
        for name in ("DOCLING", "MINERU"):
            adapter = Mock()
            adapter.unavailable_reason.return_value = None
            def parse(sample, source, raw, parser_name=name):
                raw.write_bytes(b"unit-fixture-only")
                return document(sample, parser_name, [element("TITLE", "测试标题", page=1, heading=1), element("TEXT", "资料正文", page=1)], pages=1)
            adapter.parse.side_effect = parse
            result[name] = adapter
        return result

    def test_empty_real_manifest_and_default_readiness_never_call_adapters(self):
        adapters = self.adapters()
        result = evaluate(self.manifest(0), self.root / "output", adapters=adapters)
        self.assertEqual(result["status"], "NOT_RUN")
        self.assertEqual(result["registered_sample_count"], 0)
        self.assertEqual(result["samples"], [])
        self.assertIn("EXECUTION_DEFERRED", result["reason_codes"])
        for adapter in adapters.values():
            adapter.parse.assert_not_called()
            adapter.unavailable_reason.assert_not_called()

    def test_complete_unit_corpus_still_needs_explicit_execution(self):
        adapters = self.adapters()
        result = evaluate(self.manifest(30), self.root / "output", adapters=adapters)
        self.assertEqual(result["ready_sample_count"], 30)
        self.assertEqual(result["status"], "NOT_RUN")
        self.assertTrue(all(row["metrics"] is None and row["elapsed_seconds"] is None for sample in result["samples"] for row in sample["parsers"]))
        for adapter in adapters.values():
            adapter.parse.assert_not_called()

    def test_missing_annotation_blocks_all_execution_and_keeps_scores_null(self):
        path = self.manifest(30)
        (self.root / "0.json").unlink()
        adapters = self.adapters()
        result = evaluate(path, self.root / "output", execute=True, adapters=adapters)
        self.assertIn("SAMPLES_OR_ANNOTATIONS_NOT_READY", result["reason_codes"])
        self.assertEqual(result["status"], "NOT_RUN")
        for adapter in adapters.values():
            adapter.parse.assert_not_called()

    def test_unit_execution_records_actual_artifacts_and_measured_duration_without_invented_rss(self):
        adapters = self.adapters()
        result = evaluate(self.manifest(30), self.root / "output", execute=True, adapters=adapters)
        self.assertEqual(result["status"], "COMPLETED")
        for sample in result["samples"]:
            for row in sample["parsers"]:
                self.assertIsNone(row["peak_rss_bytes"])
                self.assertIsNone(row["parser_version"])
                self.assertGreaterEqual(row["elapsed_seconds"], 0)
                self.assertEqual(len(row["raw_output_sha256"]), 64)
                self.assertEqual(row["metrics"]["cer"]["value"], 0)

    def test_parser_failure_has_no_score_and_no_followup_model_call(self):
        adapters = self.adapters()
        adapters["DOCLING"].parse.side_effect = AdapterError("DOCLING_HTTP_503", "fixture-only")
        result = evaluate(self.manifest(30), self.root / "output", execute=True, adapters=adapters)
        self.assertEqual(result["status"], "PARTIAL")
        row = result["samples"][0]["parsers"][0]
        self.assertEqual(row["reason_codes"], ["DOCLING_HTTP_503"])
        self.assertIsNone(row["metrics"])
        self.assertEqual(adapters["DOCLING"].parse.call_count, 30)

    def test_input_hash_mismatch_cannot_run(self):
        path = self.manifest(30)
        (self.root / "0.pdf").write_bytes(b"changed")
        adapters = self.adapters()
        result = evaluate(path, self.root / "output", execute=True, adapters=adapters)
        self.assertIn("INPUT_HASH_MISMATCH", result["samples"][0]["readiness_reasons"])
        for adapter in adapters.values():
            adapter.parse.assert_not_called()

    def test_cli_default_writes_not_run_report_and_never_starts_models(self):
        with patch("experiments.pipeline.adapters.subprocess.run") as process, patch("experiments.pipeline.adapters.http.client.HTTPConnection") as http:
            self.assertEqual(main(["--manifest", str(self.manifest(0)), "--output", str(self.root / "output")]), 0)
            process.assert_not_called()
            http.assert_not_called()
        report = loads((self.root / "output" / "results.json").read_bytes())
        self.assertEqual(report["status"], "NOT_RUN")

    def test_explicit_execution_of_missing_corpus_returns_nonzero_without_models(self):
        with patch("experiments.pipeline.adapters.subprocess.run") as process, patch("experiments.pipeline.adapters.http.client.HTTPConnection") as http:
            self.assertEqual(main(["--manifest", str(self.manifest(0)), "--output", str(self.root / "output"), "--execute"]), 2)
            process.assert_not_called()
            http.assert_not_called()

    def test_checked_in_manifest_has_no_fake_project_samples_and_schemas_are_strict(self):
        root = Path(__file__).resolve().parents[1]
        manifest = validate_manifest(loads((root / "manifest.json").read_bytes()))
        self.assertEqual(manifest["samples"], [])
        for name in ("manifest", "normalized-document", "results"):
            schema = loads((root / (name + ".schema.json")).read_bytes())
            self.assertFalse(schema["additionalProperties"])
            self.assertEqual(schema["$schema"], "https://json-schema.org/draft/2020-12/schema")


if __name__ == "__main__":
    unittest.main()
