#!/bin/sh
set -eu

export MYSQL_PWD="${MYSQL_ROOT_PASSWORD:-root}"
timeout="${MA4G_G2_KILL_TIMEOUT_SECONDS:-45}"
attempt=0
connection_id=""
while [ "$attempt" -lt "$timeout" ]; do
  attempt=$((attempt + 1))
  connection_id="$(mysql -hmysql -uroot --batch --raw --skip-column-names information_schema -e "
    select coalesce(is_used_lock('ma4g_g2_second_cell_cas'), '');
  ")"
  [ -n "$connection_id" ] && break
  sleep 1
done
if ! printf '%s' "$connection_id" | grep -Eq '^[0-9]+$'; then
  mysql -hmysql -uroot -e "select id,user,db,command,time,state,left(info,240) info from information_schema.processlist order by time desc,id" >&2
  echo "MA4G_G2_CONNECTION_NOT_FOUND" >&2
  exit 1
fi
mysql -hmysql -uroot -e "select id,user,db,command,time,state,left(info,240) info from information_schema.processlist where id=$connection_id"
transaction_rows="$(mysql -hmysql -uroot --batch --raw --skip-column-names information_schema -e "
  select coalesce((select trx_rows_modified from innodb_trx where trx_mysql_thread_id=$connection_id), 0);
")"
if ! printf '%s' "$transaction_rows" | grep -Eq '^[0-9]+$'; then
  echo "MA4G_G2_TRANSACTION_NOT_MUTATED connection_id=$connection_id rows_modified=$transaction_rows" >&2
  exit 1
fi
mysql -hmysql -uroot -e "
select is_used_lock('ma4g_g2_second_cell_cas') as fault_barrier_owner;
select trx_mysql_thread_id,trx_id,trx_state,trx_started,trx_rows_locked,trx_rows_modified,trx_tables_locked
from information_schema.innodb_trx where trx_mysql_thread_id=$connection_id;
select t.processlist_id,e.event_id,e.nesting_event_id,e.event_name,e.sql_text,e.rows_affected
from performance_schema.threads t
join performance_schema.events_statements_history e on e.thread_id=t.thread_id
where t.processlist_id=$connection_id order by e.event_id;
"
mysql -hmysql -uroot -e "kill connection $connection_id"
echo "MA4G_G2_CONNECTION_KILLED connection_id=$connection_id uncommitted_rows_modified=$transaction_rows"
