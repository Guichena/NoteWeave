#!/bin/sh
set -eu

export MYSQL_PWD="${MYSQL_PWD:-noteweave123}"
output="${MA4G_CONTRACT_EXPORT:-/control/contracts.tsv}"
mysql -hmysql -unoteweave --batch --raw --skip-column-names noteweave -e "
select c.id, c.envelope_size_bytes, c.envelope_digest, c.receipt_digest,
       replace(replace(to_base64(cast(c.envelope_json as binary)), char(10), ''), char(13), ''),
       replace(replace(to_base64(cast(c.receipt_json as binary)), char(10), ''), char(13), '')
from research_agent_completion c
join research_agent_task t on t.id=c.research_agent_task_id
where t.research_run_id='00000000-0000-0000-0000-00000000a002'
order by cast(c.completion_key as binary), c.id;
" > "$output"
test -s "$output"
echo "MA4G_CONTRACTS_EXPORTED path=$output rows=$(wc -l < "$output" | tr -d ' ')"
