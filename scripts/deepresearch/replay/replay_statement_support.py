import copy
import json
from pathlib import Path
import os
from app.narrative_report import NarrativeReportPolisher
from app.statement_support import verify_report, _render, frozen_facts

root = Path(os.environ['NOTEWEAVE_REPLAY_OUT'])
old = json.loads((root / 'requirement-report.json').read_text())
data = {**old['input'], 'statement_support_version': 1, 'narrative_llm_budget': 0}
report = NarrativeReportPolisher(None).polish(data)
assert report.llm_calls == 0
assert all(fact['claim_text'] in report.markdown for cell in data['cells'] for fact in cell['facts'])
support = report.narrative['statement_support']
assert support['checks'] and support['model_calls'] == 0
root.joinpath('statement-report.json').write_text(json.dumps({'input': data, 'narrative': report.narrative, 'markdown': report.markdown}, ensure_ascii=False))

cell = {'cell_key': 'row:features', 'entity_label': 'Atlas', 'candidate_value': 'Atlas maintains indexes during writes.',
        'evidence_keys': ['evidence:a'], 'facts': [{'fact_id': 'fact:a', 'claim_text': 'Atlas maintains indexes during writes.', 'evidence_keys': ['evidence:a']}],
        'evidence_quotes': [{'evidence_key': 'evidence:a', 'quote': 'Atlas maintains indexes during writes.'}]}
good = 'Atlas updates its indexes while writes occur [evidence:a].'
bad = 'Atlas does not maintain indexes during writes [evidence:a].'
narrative = {'title': 'Atlas findings', 'executive_summary': _render(frozen_facts([cell])['fact:a']),
             'sections': [{'heading': 'Findings', 'paragraphs': [{'text': value, 'cell_keys': ['row:features'], 'evidence_keys': ['evidence:a']} for value in (good, bad)]}],
             'comparison_table': {}, 'limitations': []}
class Judge:
    def complete_json(self, purpose, payload):
        assert purpose == 'research.synthesis.statement_support'
        return json.dumps({'checks': [{'statement_id': item['statement_id'],
                   'verdict': 'UNSUPPORTED' if 'does not maintain' in item['text'] else 'SUPPORTED', 'fact_ids': ['fact:a']} for item in payload['statements']]})
checks, calls, repaired = verify_report(narrative, [cell], Judge(), 2)
assert calls == 1 and repaired
assert narrative['sections'][0]['paragraphs'][0]['text'] == good
assert 'does not maintain' not in narrative['sections'][0]['paragraphs'][1]['text']
assert any(check['basis'] == 'SEMANTIC_ENTAILMENT' for check in checks)
root.joinpath('statement-semantic.json').write_text(json.dumps({'input': {'cells': [cell], 'narrative_llm_budget': 2},
    'narrative': {**narrative, 'statement_support': {'schema_version': 'research-statement-support.v1', 'checks': checks, 'model_calls': calls}}}))
print(json.dumps({'retained_facts': 40, 'zero_budget_fallback': True, 'valid_paragraph_preserved': True,
                  'unsupported_negation_replaced': True, 'offline_judge_calls': calls, 'provider_calls': 0}))
