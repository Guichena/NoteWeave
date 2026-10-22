import json
from pathlib import Path
import os
from types import SimpleNamespace
from app.fact_selection import select_facts, validate_selection
from app.requirement_coverage import coverage_proposal
from app.narrative_report import NarrativeReportPolisher
from app.report_coverage import audit_report_coverage
from app.deep_cell_executor import _prepend_deferred_read_urls

root = Path(os.environ['NOTEWEAVE_REPLAY_OUT'])
req = 'req:' + 'a' * 48
other = 'req:' + 'b' * 48
contract = {'schema_version': 'research-requirement-contract.v2', 'contract_digest': 'sha256:' + 'c' * 64,
    'missing_requirement_ids': [req, other], 'requirements': [
        {'requirement_id': req, 'origin': 'EXPLICIT', 'minimum_count': 1, 'completion_mode': 'SINGLE'},
        {'requirement_id': other, 'origin': 'DERIVED', 'minimum_count': 1, 'completion_mode': 'SINGLE'}], 'facts': []}
cards = [{'evidence_key': f'evidence:{i}', 'cell_key': 'row:features', 'source_id': f'source:{i}', 'window_id': f'window:{i}',
          'claim_text': f'Capability {i}', 'quote_text': f'Capability {i}', 'source_url': 'https://atlas.example/docs', 'support_score_ppm': 900000-i} for i in range(7)]
observations = [{'requirement_id': other, 'evidence_keys': [item['evidence_key'] for item in cards[:6]], 'complete': True},
                {'requirement_id': req, 'evidence_keys': ['evidence:6'], 'complete': True}]
chosen, diagnostic = select_facts(SimpleNamespace(query_policy={'requirement_contract': contract}), cards, observations, limit=6)
validate_selection(diagnostic)
assert 'evidence:6' in diagnostic['submitted_evidence_keys'] and len(diagnostic['deferred']) == 1
proposal = coverage_proposal(contract, observations, diagnostic['submitted_evidence_keys'])
assert next(item for item in proposal['observations'] if item['requirement_id'] == other)['complete'] is False
root.joinpath('fact-selection-v4.json').write_text(json.dumps(diagnostic))
read_snapshot = SimpleNamespace(source_policy={'allowed_external_domains': ['atlas.example']}, query_policy={'repair_target': {
    'dispatch_version': 1, 'actions': [{'action': 'READ_TARGETED', 'read_urls': ['https://atlas.example/docs', 'https://other.example/docs']} ]}})
hits = _prepend_deferred_read_urls(read_snapshot, SimpleNamespace(normalized_question='Atlas'), [])
assert len(hits)==1 and hits[0].url=='https://atlas.example/docs'

old = json.loads(root.joinpath('requirement-report.json').read_text())
count_req = {'requirement_id': req, 'cell_key': 'row:features', 'entity_label': 'Atlas', 'text': '列出至少二十项功能',
    'scope_type': 'CELL', 'status': 'SATISFIED', 'completion_mode': 'DISTINCT_FACTS', 'minimum_count': 20,
    'supporting_fact_ids': [fact['fact_id'] for fact in old['input']['cells'][0]['facts']]}
data = {**old['input'], 'statement_support_version': 1, 'report_coverage_version': 1, 'narrative_llm_budget': 0,
        'requirement_coverage': {'requirements': [count_req]}}
report = NarrativeReportPolisher(None).polish(data)
assert not report.narrative['report_coverage']['complete'] and report.llm_calls == 0
assert all(fact['claim_text'] in report.markdown for fact in data['cells'][0]['facts'])
root.joinpath('report-coverage-zero-v4.json').write_text(json.dumps({'input': data, 'narrative': report.narrative, 'markdown': report.markdown}, ensure_ascii=False))

fact = {'fact_id': 'fact:atlas', 'claim_text': 'Atlas maintains indexes during writes.', 'evidence_keys': ['evidence:a']}
cell = {'cell_key': 'row:features', 'entity_label': 'Atlas', 'column_label': '索引维护', 'candidate_value': fact['claim_text'],
        'evidence_keys': fact['evidence_keys'], 'facts': [fact], 'evidence_quotes': [{'evidence_key': 'evidence:a', 'quote': fact['claim_text']}]}
requirement = {'requirement_id': req, 'cell_key': 'row:features', 'entity_label': 'Atlas', 'text': 'Atlas 是否在写入时维护索引？',
    'scope_type': 'CELL', 'status': 'SATISFIED', 'completion_mode': 'SINGLE', 'minimum_count': 1, 'supporting_fact_ids': ['fact:atlas']}
repair_input = {'question': requirement['text'], 'cells': [cell], 'limitations': [], 'requirement_coverage': {'requirements': [requirement]},
                'narrative_llm_budget': 6, 'statement_support_version': 1, 'report_coverage_version': 1}
class Model:
    def complete_json(self, purpose, payload):
        if purpose == 'research.synthesis':
            text = 'Atlas maintains indexes during writes [evidence:a].'
            return json.dumps({'title': requirement['text'], 'executive_summary': text,
                'sections': [{'heading': '直接回答', 'paragraphs': [{'text': text, 'cell_keys': ['row:features'], 'evidence_keys': ['evidence:a']}]}],
                'comparison_table': {}, 'limitations': [], 'unanswered_points': []})
        if purpose == 'research.synthesis.requirement_coverage':
            items=[]
            for item in payload['requirements']:
                found = [s['statement_id'] for s in item['statements'] if '会在写入时' in s['text']]
                items.append({'requirement_id': item['requirement']['requirement_id'], 'status': 'ANSWERED' if found else 'PARTIAL',
                              'statement_ids': found or [item['statements'][0]['statement_id']]})
            return json.dumps({'items': items})
        if purpose == 'research.synthesis.repair_answers':
            return json.dumps({'paragraphs': [{'requirement_id': req, 'text': 'Atlas 会在写入时维护索引 [evidence:a]。', 'fact_ids': ['fact:atlas']}]})
        if purpose == 'research.synthesis.statement_support':
            return json.dumps({'checks': [{'statement_id': item['statement_id'], 'verdict': 'SUPPORTED', 'fact_ids': ['fact:atlas']} for item in payload['statements']]})
        raise AssertionError(purpose)
fixed = NarrativeReportPolisher(Model()).polish(repair_input)
assert fixed.llm_calls == 6 and fixed.narrative['report_coverage']['complete']
assert '会在写入时维护索引' in fixed.markdown and not any(item.startswith('报告尚未完整回答') for item in fixed.narrative['limitations'])
root.joinpath('report-coverage-repair-v4.json').write_text(json.dumps({'input': repair_input, 'narrative': fixed.narrative, 'markdown': fixed.markdown}, ensure_ascii=False))
print(json.dumps({'explicit_requirement_prioritized': True, 'deferred_material_recorded': True, 'truncated_observation_partial': True,
                  'zero_budget_retained_facts': 40, 'bounded_answer_repair_calls': fixed.llm_calls, 'report_answered_after_repair': True, 'provider_calls': 0}))
