#!/bin/sh
set -eu

mode="${1:-}"
label="${2:-}"
case "$mode" in capture|assert) ;; *) echo "usage: verify-state.sh capture|assert LABEL" >&2; exit 2 ;; esac
if ! printf '%s' "$label" | grep -Eq '^[a-z0-9][a-z0-9_-]{0,63}$'; then
  echo "MA4G_STATE_INVALID_LABEL value=$label" >&2
  exit 2
fi

export MYSQL_PWD="${MYSQL_PWD:-noteweave123}"
mysql_query() {
  mysql -h"${MA4G_MYSQL_HOST:-mysql}" -u"${MA4G_MYSQL_USER:-noteweave}" \
    --batch --raw --skip-column-names "${MA4G_MYSQL_DATABASE:-noteweave}" "$@"
}
mysql_value() { mysql_query -e "$1"; }

snapshot_file="/tmp/ma4g-state-$$.tsv"
trap 'rm -f "$snapshot_file"' EXIT HUP INT TERM
mysql_query < /fixture/state-digest.sql > "$snapshot_file"
cat "$snapshot_file"

line_count="$(awk -F '\t' '$1 == "COMPONENT" || $1 == "OVERALL" { count++ } END { print count + 0 }' "$snapshot_file")"
overall_count="$(awk -F '\t' '$1 == "OVERALL" { print $3 }' "$snapshot_file")"
overall_digest="$(awk -F '\t' '$1 == "OVERALL" { print $4 }' "$snapshot_file")"
if [ "$line_count" != "17" ] || ! printf '%s' "$overall_count" | grep -Eq '^[0-9]+$' \
    || ! printf '%s' "$overall_digest" | grep -Eq '^[0-9a-f]{64}$'; then
  echo "MA4G_STATE_INVALID_SNAPSHOT lines=$line_count rows=$overall_count digest=$overall_digest" >&2
  exit 1
fi

mysql_value "
create table if not exists ma4g_state_baseline (
  label varchar(64) not null,
  component varchar(96) not null,
  row_count bigint not null,
  component_digest char(64) not null,
  primary key(label, component)
) engine=InnoDB;
" >/dev/null

if [ "$mode" = "capture" ]; then
  mysql_value "delete from ma4g_state_baseline where label='$label'" >/dev/null
  tab="$(printf '\t')"
  while IFS="$tab" read -r kind component row_count digest; do
    case "$kind" in COMPONENT|OVERALL) ;; *) continue ;; esac
    [ "$kind" = "OVERALL" ] && component="__OVERALL__"
    if ! printf '%s' "$component" | grep -Eq '^(__OVERALL__|[a-z_]+)$' \
        || ! printf '%s' "$row_count" | grep -Eq '^[0-9]+$' \
        || ! printf '%s' "$digest" | grep -Eq '^[0-9a-f]{64}$'; then
      echo "MA4G_STATE_INVALID_LINE component=$component" >&2
      exit 1
    fi
    mysql_value "insert into ma4g_state_baseline(label,component,row_count,component_digest)
      values('$label','$component',$row_count,'$digest')" >/dev/null
  done < "$snapshot_file"
  echo "MA4G_STATE_BASELINE_CAPTURED label=$label rows=$overall_count digest=$overall_digest"
  exit 0
fi

baseline_lines="$(mysql_value "select count(*) from ma4g_state_baseline where label='$label'")"
if [ "$baseline_lines" != "$line_count" ]; then
  echo "MA4G_STATE_BASELINE_MISSING label=$label expected_lines=$line_count actual=$baseline_lines" >&2
  exit 1
fi

tab="$(printf '\t')"
while IFS="$tab" read -r kind component row_count digest; do
  case "$kind" in COMPONENT|OVERALL) ;; *) continue ;; esac
  [ "$kind" = "OVERALL" ] && component="__OVERALL__"
  expected="$(mysql_value "select concat(row_count,':',component_digest) from ma4g_state_baseline where label='$label' and component='$component'")"
  actual="$row_count:$digest"
  if [ "$actual" != "$expected" ]; then
    echo "MA4G_STATE_CHANGED label=$label component=$component expected=$expected actual=$actual" >&2
    exit 1
  fi
done < "$snapshot_file"

echo "MA4G_STATE_UNCHANGED label=$label rows=$overall_count digest=$overall_digest"

