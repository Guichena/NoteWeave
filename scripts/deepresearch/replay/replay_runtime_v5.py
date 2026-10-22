"""新执行链的最小离线回放，不读取评测答案，不调用外部模型。"""
import json
import os
from pathlib import Path
from types import SimpleNamespace
from app.document_navigation import document_links, navigation_hits
from app.requirement_definitions import compile_requirements
from app.report_coverage import _in_scope, audit_report_coverage
from app.source_scope_checks import check_source_scopes
from app.runtime_metrics import runtime_metrics
from app.planning_completeness import audit_planning
from app.fact_selection import select_facts, validate_selection
from app.synthesis_executor import _MeteredSynthesisClient
import time


links = document_links('<a href="index.html">index</a><a href="indexes.html">JSON indexes</a><a href="https://other.example/docs/8.4/x">external</a>', 'https://atlas.example/docs/8.4/index.html')
doc = SimpleNamespace(url='https://atlas.example/docs/8.4/index.html', fetch_status='FETCHED', navigation_links=links,
    query='JSON indexes', source_title='Atlas', provider='offline', source_domain='atlas.example', source_quality='OFFICIAL_DOCS', source_quality_score=.9)
snapshot = SimpleNamespace(query_policy={'navigation_policy': {'max_follow_pages': 2, 'max_depth': 1},
    'column_specs': [{'label':'JSON indexes'}], 'requirement_contract': {'requirements':[]}})
hits = navigation_hits(snapshot,[doc])
assert len(hits) == 1 and hits[0].url.endswith('/indexes.html')
doc.navigation_links = [{'url':'https://atlas.example/docs/9.0/indexes.html','label':'JSON indexes'},
                        {'url':'https://user:password@atlas.example/docs/8.4/indexes.html','label':'JSON indexes'}]
assert navigation_hits(snapshot,[doc]) == []
comparison = {'text':'比较 Atlas 和 Boreal 的索引', 'question_quote':'比较 Atlas 和 Boreal 的索引', 'dimension':'索引',
    'entities':['Atlas','Boreal'], 'completion_mode':'COMPARISON','minimum_count':2,'count_expression':''}
assert compile_requirements([comparison],comparison['text'],[{'label':'索引'}],[{'label':'Atlas'},{'label':'Boreal'}],required=True)[0]['minimum_count'] == 2
scope = {'scope_type':'RUN','scope':{'entity_keys':['a','b'],'column_key':'index'}}
assert _in_scope(scope,'a:index') and not _in_scope(scope,'c:index') and not _in_scope(scope,'a:history')
snapshot.query_policy['requirement_contract']['source_scopes'] = [{'cell_key':'a:index','entity_label':'Atlas','as_of_date':'2026-10-20'}]
checks = check_source_scopes(snapshot,[{'cluster_key':'cluster:a','cell_key':'a:index','claim_text':'Atlas supports indexes','evidence_keys':['evidence:a']}],[],[],None)
assert checks['model_calls'] == 0 and checks['checks'][0]['product'] == 'UNKNOWN'
metric = runtime_metrics(None,[],time.monotonic())
assert metric['input_tokens'] is None and metric['output_tokens'] is None
data = {'report_feedback_version':1,'cells':[], 'requirement_coverage': {'requirements':[{'requirement_id':'req:a','cell_key':'a:index',
    'text':'索引是否可用','status':'PENDING','scope_type':'CELL','completion_mode':'SINGLE','minimum_count':1}]}}
result, calls = audit_report_coverage({'statement_support':{'checks':[]},'limitations':[]},data,None,0)
assert calls == 0 and result['items'][0]['gap_type'] == 'NOT_CHECKED' and not result['complete']
planning = {'question':'比较 Atlas 和 Boreal，限定版本', 'requested_clauses':[{'clause_id':'clause:0','text':'比较 Atlas 和 Boreal'},{'clause_id':'clause:1','text':'限定版本'}]}
review = audit_planning(planning,{'user_requirements':[comparison]},None)
assert not review['complete'] and review['model_calls'] == 0 and len(review['clauses']) == 2
class MissingVersion:
    def complete_json(self,purpose,payload):
        return json.dumps({'clauses':[{'clause_id':'clause:0','status':'MAPPED','requirement_indexes':[0]},
                                     {'clause_id':'clause:1','status':'MISSING','requirement_indexes':[]}]})
review = audit_planning(planning,{'user_requirements':[comparison]},MissingVersion())
assert not review['complete'] and review['clauses'][1]['status'] == 'MISSING'
large = {'evidence_key':'evidence:large','cell_key':'a:index','claim_text':'large fact '*1000,'quote_text':'quote',
         'source_id':'source:a','window_id':'window:a','source_url':'https://atlas.example/docs'}
chosen, selected = select_facts(SimpleNamespace(query_policy={}),[large],[],limit=6,byte_limit=4000)
validate_selection(selected)
assert chosen == [] and selected['deferred'][0]['reason_code'] == 'COMPLETION_PAYLOAD_LIMIT'
events = []
class PermitClient:
    def require_synthesis_call(self,snapshot,index,purpose):
        events.append(('permit',index,purpose))
class Delegate:
    def complete_json(self,purpose,payload):
        events.append(('provider',purpose))
        return '{}'
metered = _MeteredSynthesisClient(Delegate(),PermitClient(),None)
metered.complete_json('research.synthesis',{})
metered.complete_json('research.synthesis.statement_support',{})
assert metered.calls == 2 and events[0][0] == events[2][0] == 'permit' and events[1][0] == events[3][0] == 'provider'
from app.extraction_result import ExtractionResult, ExtractionTerminationReason
from app.research_agent_completion_contract import build_completion_envelope, serialize_completion_envelope
diagnostics = ExtractionResult(termination_reason=ExtractionTerminationReason.NO_READ_WINDOWS).diagnostics()
diagnostics.update(runtime_metrics=metric,source_scope_checks={'schema_version':'research-source-scope-checks.v1','model_calls':0,'checks':[]})
envelope = build_completion_envelope({'schema_version':'research-agent-completion.v2','task_id':'00000000-0000-0000-0000-000000000005',
    'worker_instance_id':'offline-worker','lease_epoch':1,'fencing_token':10,'execution_key':'offline-v5',
    'task_snapshot_digest':'sha256:'+'c'*64,'termination_reason':'NO_SUPPORTED_CANDIDATE',
    'budget_usage':{key:0 for key in ('llm_calls','search_calls','fetch_calls','read_calls','extract_calls','evidence_cards','candidates_submitted')},
    'telemetry':{'search_hits':0,'documents':0,'windows':0},'trace_digest':'sha256:'+'a'*64,
    'evidence':[],'candidates':[],'extraction_diagnostics':diagnostics})
(Path(os.environ['NOTEWEAVE_REPLAY_OUT'])/'completion-v5.json').write_bytes(serialize_completion_envelope(envelope))
print(json.dumps({'runtime_v5_python':'PASSED','provider_calls':0}))
