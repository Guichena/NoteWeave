#!/bin/sh
set -eu

export MYSQL_PWD="${MYSQL_PWD:-noteweave123}"
output="${MA4G_ROW_DIGEST_EXPORT:-/control/row-digests.tsv}"
mysql -hmysql -unoteweave --batch --raw --skip-column-names noteweave -e "
select row_type, row_id, content_digest,
       replace(replace(to_base64(cast(payload as binary)), char(10), ''), char(13), '')
from (
  select 'evidence' row_type, se.id row_id, se.content_digest,
         json_object(
           'id',se.id,
           'research_run_id',se.research_run_id,
           'agent_completion_id',se.agent_completion_id,
           'evidence_key',se.evidence_key,
           'window_id',se.window_id,
           'source_id',se.source_id,
           'source_title',se.source_title,
           'source_url',se.source_url,
           'provider',se.provider,
           'adapter',se.adapter,
           'search_query',se.search_query,
           'read_focus',se.read_focus,
           'quote_text',se.quote_text,
           'claim_text',se.claim_text,
           'relation_type',se.relation_type,
           'support_score',cast(se.support_score as char),
           'conflict_score',cast(se.conflict_score as char),
           'support_score_ppm',se.support_score_ppm,
           'conflict_score_ppm',se.conflict_score_ppm,
           'snapshot_status',se.snapshot_status,
           'snapshot_key',se.snapshot_key
         ) payload
  from source_evidence se
  where se.research_run_id='00000000-0000-0000-0000-00000000a002'

  union all

  select 'candidate', c.id, c.content_digest,
         json_object(
           'id',c.id,
           'research_run_id',c.research_run_id,
           'agent_completion_id',c.agent_completion_id,
           'research_agent_execution_id',c.research_agent_execution_id,
           'task_id',c.task_id,
           'execution_id',c.execution_id,
           'idempotency_key',c.idempotency_key,
           'cell_key',c.cell_key,
           'base_cell_version',c.base_cell_version,
           'plan_revision',c.plan_revision,
           'entity_set_version',c.entity_set_version,
           'lease_epoch',c.lease_epoch,
           'fencing_token',c.fencing_token,
           'candidate_value',c.candidate_value,
           'evidence_ids',json_extract(c.evidence_ids_json,'$'),
           'confidence_score',cast(c.confidence_score as char),
           'confidence_score_ppm',c.confidence_score_ppm
         )
  from research_agent_candidate c
  where c.research_run_id='00000000-0000-0000-0000-00000000a002'

  union all

  select 'merge', m.id, m.content_digest,
         json_object(
           'id',m.id,
           'research_run_id',m.research_run_id,
           'agent_completion_id',m.agent_completion_id,
           'candidate_id',m.candidate_id,
           'merge_key',m.merge_key,
           'cell_key',m.cell_key,
           'expected_cell_version',m.expected_cell_version,
           'result_cell_version',m.result_cell_version,
           'verdict',m.verdict,
           'decision',m.decision,
           'reason_code',m.reason_code,
           'accepted_evidence_ids',json_extract(m.accepted_evidence_ids_json,'$')
         )
  from research_cell_merge m
  where m.research_run_id='00000000-0000-0000-0000-00000000a002'

  union all

  select 'cell_evidence', rce.id, rce.content_digest,
         json_object(
           'id',rce.id,
           'research_run_id',rce.research_run_id,
           'agent_completion_id',rce.agent_completion_id,
           'candidate_id',c.id,
           'merge_id',m.id,
           'merge_key',m.merge_key,
           'research_cell_id',rce.research_cell_id,
           'source_evidence_id',rce.source_evidence_id,
           'evidence_key',rce.evidence_key
         )
  from research_cell_evidence rce
  join research_cell rc on rc.id=rce.research_cell_id and rc.research_run_id=rce.research_run_id
  join research_agent_candidate c
    on c.research_run_id=rce.research_run_id
   and c.agent_completion_id=rce.agent_completion_id
   and c.cell_key=rc.cell_key
   and json_contains(c.evidence_ids_json,json_quote(rce.evidence_key))
  join research_cell_merge m
    on m.research_run_id=rce.research_run_id
   and m.agent_completion_id=rce.agent_completion_id
   and m.candidate_id=c.id
   and m.decision='ACCEPTED'
  where rce.research_run_id='00000000-0000-0000-0000-00000000a002'
) digest_rows
order by cast(row_type as binary), row_id;
" > "$output"
test -s "$output"
echo "MA4G_ROW_DIGESTS_EXPORTED path=$output rows=$(wc -l < "$output" | tr -d ' ')"

