#!/usr/bin/env bash
# Renders docker-compose.prod.yml with a dummy REDIS_PASSWORD and checks
# that the secret is absent from the redis command and healthcheck.
# Container env may still hold the value: that is how Compose injects .env.
# The real .env is neither read nor printed.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="$ROOT/docker-compose.prod.yml"
DUMMY_PASSWORD="DUMMY_REDIS_PW"

if docker compose version >/dev/null 2>&1; then
  compose() { docker compose "$@"; }
elif command -v docker-compose >/dev/null 2>&1; then
  compose() { docker-compose "$@"; }
else
  echo "docker compose is required to render ${COMPOSE_FILE}" >&2
  exit 1
fi

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

# Only vars marked :? are required. --env-file replaces the project .env so a
# local secret file cannot leak into this render.
cat >"$tmp/env" <<EOF
IMAGE_TAG=ci
REDIS_PASSWORD=${DUMMY_PASSWORD}
EOF

compose -f "$COMPOSE_FILE" --env-file "$tmp/env" --project-directory "$ROOT" config --format json >"$tmp/rendered.json"

python3 - "$tmp/rendered.json" "$DUMMY_PASSWORD" <<'PYCHECK'
import json
import sys

path, dummy = sys.argv[1], sys.argv[2]
with open(path, encoding="utf-8") as fh:
    doc = json.load(fh)

redis = doc["services"]["redis"]
command = redis.get("command")
health = redis.get("healthcheck") or {}
test = health.get("test")
blob = json.dumps({"command": command, "healthcheck_test": test}, ensure_ascii=False)

if dummy in blob:
    sys.stderr.write("redis command/healthcheck still contains the dummy password\n")
    sys.stderr.write(blob + "\n")
    sys.exit(1)

command_text = command if isinstance(command, str) else " ".join(command or [])
test_text = test if isinstance(test, str) else " ".join(test or [])

if "requirepass" not in command_text or "$REDIS_PASSWORD" not in command_text:
    sys.stderr.write("redis command no longer wires requirepass to $REDIS_PASSWORD\n")
    sys.stderr.write(command_text + "\n")
    sys.exit(1)

if "docker-entrypoint.sh" not in command_text:
    sys.stderr.write("redis command does not re-exec the image entrypoint\n")
    sys.stderr.write(command_text + "\n")
    sys.exit(1)

if "-a" in test_text.split() or dummy in test_text or "REDIS_PASSWORD" in test_text:
    sys.stderr.write("redis healthcheck test still references a password\n")
    sys.stderr.write(test_text + "\n")
    sys.exit(1)

if "redis-cli" not in test_text or "ping" not in test_text:
    sys.stderr.write("redis healthcheck is not redis-cli ping\n")
    sys.stderr.write(test_text + "\n")
    sys.exit(1)

env = redis.get("environment") or {}
if isinstance(env, list):
    env_map = {}
    for item in env:
        key, _, value = item.partition("=")
        env_map[key] = value
else:
    env_map = env

if env_map.get("REDIS_PASSWORD") != dummy or env_map.get("REDISCLI_AUTH") != dummy:
    sys.stderr.write("REDIS_PASSWORD/REDISCLI_AUTH were not passed through container env\n")
    sys.exit(1)

print("ok: rendered redis command and healthcheck do not contain DUMMY_REDIS_PW")
PYCHECK
