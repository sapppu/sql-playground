#!/usr/bin/env bash
#
# WAL crash-recovery demo (real kill -9, real restart).
#
#   1. auto-commit INSERT  -> must survive the crash (WAL-logged + flushed)
#   2. BEGIN + INSERT, no COMMIT -> must NOT survive (never reaches the WAL)
#
# One command:  ./bench/crash-demo.sh
# Isolated: everything (port, WAL, users, history) lives under $DATA,
# your dev backend and its data are untouched.
#
set -u

PORT="${PORT:-8082}"
DATA="${DATA:-/tmp/crashdemo-data}"
BASE="http://localhost:${PORT}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR="$(ls -t "${SCRIPT_DIR}"/../target/*.jar 2>/dev/null | head -1)"
SESSION="crash-session"
USER="crashdemo"
PASS="crashdemo123"
FAILURES=0

say()  { printf '%s\n' "$*"; }
pass() { printf 'PASS: %s\n' "$*"; }
fail() { printf 'FAIL: %s\n' "$*"; FAILURES=$((FAILURES + 1)); }

jget() { python3 -c "import sys,json; print(json.load(sys.stdin)$1)" 2>/dev/null; }

wait_for_up() {
    local tries=0
    while [ $tries -lt 60 ]; do
        # /api/schema without auth answers 401 when the app is genuinely up
        code=$(curl -s -o /dev/null -w "%{http_code}" "${BASE}/api/schema" || echo 000)
        [ "$code" = "401" ] && return 0
        sleep 2; tries=$((tries + 1))
    done
    return 1
}

query() { # $1 = sql, $2 = token
    curl -s -X POST "${BASE}/api/query" \
        -H 'Content-Type: application/json' \
        -H "X-Session-Id: ${SESSION}" \
        -H "Authorization: Bearer $2" \
        -d "$(python3 -c 'import sys,json; print(json.dumps({"sql": sys.argv[1]}))' "$1")"
}

names_of() { # $1 = /api/query response json on stdin -> space-separated names
    python3 -c "
import sys, json
d = json.load(sys.stdin)
print(' '.join(str(r.get('name', '')) for r in d.get('rows', [])))
"
}

[ -n "$JAR" ] || { say "No built jar found. Run:  mvn package -DskipTests  (in backend/)"; exit 2; }

say "=== WAL crash-recovery demo ==="
say "data dir : ${DATA}   port: ${PORT}"
rm -rf "${DATA}"
mkdir -p "${DATA}" "${DATA}/wal" "${DATA}/history"

say ""
say "--- boot #1 (fresh) ---"
java -jar "$JAR" \
    --server.port="${PORT}" \
    --wal.file.path="${DATA}/wal.log" \
    --wal.user.dir="${DATA}/wal" \
    --user.store.path="${DATA}/users.json" \
    --query.history.path="${DATA}/history" \
    > "${DATA}/boot1.log" 2>&1 &
APP_PID=$!
wait_for_up || { say "backend failed to boot; see ${DATA}/boot1.log"; kill $APP_PID 2>/dev/null; exit 2; }
say "backend up (pid ${APP_PID})"

say ""
say "--- signup ${USER} ---"
TOKEN=$(curl -s -X POST "${BASE}/api/auth/signup" \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"${USER}\",\"password\":\"${PASS}\"}" | jget "['token']")
[ -n "$TOKEN" ] || { say "signup failed"; kill $APP_PID 2>/dev/null; exit 2; }
say "signed up"

say ""
say "--- committed write (auto-commit INSERT) ---"
query "CREATE TABLE crashdemo (id INTEGER PRIMARY KEY, name VARCHAR NOT NULL)" "$TOKEN" > /dev/null
query "INSERT INTO crashdemo (name) VALUES ('Ada')" "$TOKEN" | jget "['message']"

say ""
say "--- in-flight write (BEGIN + INSERT, never committed) ---"
query "BEGIN" "$TOKEN" | jget "['message']"
query "INSERT INTO crashdemo (name) VALUES ('Uncommitted')" "$TOKEN" | jget "['message']"
say "rows visible inside the txn:"
query "SELECT * FROM crashdemo" "$TOKEN" | names_of

say ""
say "--- WAL file before crash (INSERT payloads only) ---"
grep -h '"operation" *: *"INSERT"' "${DATA}"/wal/*.wal.log 2>/dev/null \
    | grep -o '"name" *: *"[^"]*"' | sort -u

say ""
say "--- kill -9 (no graceful shutdown) ---"
kill -9 $APP_PID 2>/dev/null
# Reap immediately: wait consumes the exit status silently, so bash never
# prints its own "Killed" job notice into the transcript.
wait $APP_PID 2>/dev/null
sleep 2
say "process is dead; restarting on the same data dir..."

say ""
say "--- boot #2 (recovery) ---"
java -jar "$JAR" \
    --server.port="${PORT}" \
    --wal.file.path="${DATA}/wal.log" \
    --wal.user.dir="${DATA}/wal" \
    --user.store.path="${DATA}/users.json" \
    --query.history.path="${DATA}/history" \
    > "${DATA}/boot2.log" 2>&1 &
APP_PID=$!
wait_for_up || { say "backend failed to reboot; see ${DATA}/boot2.log"; kill $APP_PID 2>/dev/null; exit 2; }
say "backend up (pid ${APP_PID})"

TOKEN2=$(curl -s -X POST "${BASE}/api/auth/login" \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"${USER}\",\"password\":\"${PASS}\"}" | jget "['token']")
say ""
say "--- rows after restart ---"
NAMES=$(query "SELECT * FROM crashdemo" "$TOKEN2" | names_of)
say "rows: ${NAMES}"

case " ${NAMES} " in
    *" Ada "*)      pass "committed write 'Ada' survived the crash" ;;
    *)              fail "committed write 'Ada' missing after replay" ;;
esac
case " ${NAMES} " in
    *"Uncommitted"*) fail "in-flight write 'Uncommitted' survived (it must not)" ;;
    *)               pass "in-flight write 'Uncommitted' is gone after the crash" ;;
esac
if grep -q "Uncommitted" "${DATA}"/wal/*.wal.log 2>/dev/null; then
    fail "uncommitted row leaked into a WAL file"
else
    pass "uncommitted row never reached any WAL file"
fi

say ""
if [ "$FAILURES" -eq 0 ]; then say "=== DEMO GREEN: durability holds, in-flight work stays lost ===";
else say "=== DEMO RED: ${FAILURES} check(s) failed ==="; fi

kill $APP_PID 2>/dev/null
exit $FAILURES
