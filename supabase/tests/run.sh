#!/usr/bin/env bash
# Teste schema.sql + storage.sql sur un Postgres local jetable (aucun compte Supabase requis).
# Prérequis : binaires PostgreSQL (initdb, pg_ctl, psql) ; exécuter d'abord `./gradlew :core:test`
# pour produire core/build/serverdoc.json. Si lancé en root, bascule sur l'utilisateur postgres.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
BIN="${PG_BIN:-$(ls -d /usr/lib/postgresql/*/bin 2>/dev/null | tail -1)}"
WORK="$(mktemp -d)"; chmod 777 "$WORK"
cp "$ROOT"/supabase/*.sql "$ROOT"/supabase/tests/*.sql "$ROOT/core/build/serverdoc.json" "$WORK/"; chmod -R a+rX "$WORK"
SCRIPT="
set -e
'$BIN/initdb' -D '$WORK/db' -A trust >/dev/null
'$BIN/pg_ctl' -D '$WORK/db' -o '-p 54329 -k $WORK -c listen_addresses=' -l '$WORK/log' -w start >/dev/null
trap \"'$BIN/pg_ctl' -D '$WORK/db' -m immediate stop >/dev/null\" EXIT
export PGOPTIONS='-c client_min_messages=warning'
P=\"'$BIN/psql' -h '$WORK' -p 54329 -d postgres -v ON_ERROR_STOP=1 -q\"
eval \$P -f '$WORK/stubs.sql'
eval \$P -f '$WORK/schema.sql'
eval \$P -f '$WORK/schema.sql'   # rejouable
eval \$P -f '$WORK/storage.sql'
DOC='$WORK/serverdoc.json' eval \$P -t -f '$WORK/flow.sql' | grep -E 'TESTS|ECHEC' 
"
if [ "$(id -u)" = "0" ]; then su postgres -c "bash -c \"$(printf '%s' "$SCRIPT" | sed 's/"/\\"/g; s/\$/\\$/g')\""; else bash -c "$SCRIPT"; fi
rm -rf "$WORK"
