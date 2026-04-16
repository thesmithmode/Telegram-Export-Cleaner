#!/usr/bin/env bash
# Daily SQLite backup for message cache and dashboard DBs.
#
# Usage on host (cron):
#   BASE=/var/lib/telegram-cleaner (or override via env)
#   0 1 * * * root TELEGRAM_CLEANER_BASE=/path /opt/telegram-cleaner/backup-cache.sh \
#       >> /var/log/telegram-cleaner-backup.log 2>&1
#
# Expects the following layout under $BASE:
#   cache/messages.db        — written by python-worker
#   dashboard/dashboard.db   — written by java-bot
#   backups/                 — created here
#
# Rotation: keeps $KEEP most recent files per DB, deletes older ones.
# sqlite3 .backup performs a WAL-safe atomic snapshot on a live open DB;
# plain cp/rsync can miss WAL pages and yield a corrupted copy.

set -euo pipefail

BASE="${TELEGRAM_CLEANER_BASE:-/var/lib/telegram-cleaner}"
BACKUPS="$BASE/backups"
STAMP="$(date -u +%Y%m%d)"
LOCKFILE="${BACKUP_LOCKFILE:-/var/run/telegram-cleaner-backup.lock}"
KEEP="${BACKUP_KEEP:-3}"

mkdir -p "$BACKUPS"

exec 9> "$LOCKFILE"
if ! flock -n 9; then
    echo "[$(date -u +%FT%TZ)] another backup is running, skipping"
    exit 0
fi

backup_db() {
    local src="$1" name="$2"
    if [[ ! -f "$src" ]]; then
        echo "[$(date -u +%FT%TZ)] WARN: $src not found, skipping $name"
        return 0
    fi
    local out="$BACKUPS/${name}-${STAMP}.db"
    local start=$SECONDS
    sqlite3 "$src" ".backup '$out'"
    local elapsed=$((SECONDS - start))
    local size
    size=$(du -h "$out" | cut -f1)
    echo "[$(date -u +%FT%TZ)] OK: $name -> $out ($size, ${elapsed}s)"

    # shellcheck disable=SC2012
    ls -1t "$BACKUPS/${name}-"*.db 2>/dev/null | tail -n +$((KEEP + 1)) | while read -r old; do
        rm -f -- "$old"
        echo "[$(date -u +%FT%TZ)] rotated out: $old"
    done
}

backup_db "$BASE/cache/messages.db"      "messages"
backup_db "$BASE/dashboard/dashboard.db" "dashboard"

echo "[$(date -u +%FT%TZ)] backup cycle complete"
