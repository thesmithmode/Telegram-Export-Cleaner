#!/usr/bin/env bash
# Daily SQLite backup for message cache and dashboard DBs.
#
# Usage on host (cron):
#   BASE=/var/lib/telegram-cleaner (or override via env)
#   0 4 * * * root TELEGRAM_CLEANER_BASE=/path /opt/telegram-cleaner/backup-cache.sh \
#       >> /var/log/telegram-cleaner-backup.log 2>&1
#
# Expects the following layout under $BASE:
#   cache/messages.db        — written by python-worker
#   dashboard/dashboard.db   — written by java-bot
#   backups/                 — created here
#
# Rotation: keeps $KEEP most recent .db.gz files per DB, deletes older ones.
# sqlite3 .backup performs a WAL-safe atomic snapshot on a live open DB;
# plain cp/rsync can miss WAL pages and yield a corrupted copy.

set -uo pipefail

# Бэкапы содержат сообщения и dashboard.db с ID пользователей — режим 700/600,
# чтобы посторонние локальные пользователи на хосте их не читали.
umask 077

BASE="${TELEGRAM_CLEANER_BASE:-/var/lib/telegram-cleaner}"
BACKUPS="$BASE/backups"
STAMP="$(date -u +%Y%m%d)"
LOCKFILE="${BACKUP_LOCKFILE:-/var/run/telegram-cleaner-backup.lock}"
KEEP="${BACKUP_KEEP:-3}"

mkdir -p "$BACKUPS" || { echo "[$(date -u +%FT%TZ)] FATAL: mkdir $BACKUPS failed" >&2; exit 1; }
chmod 700 "$BACKUPS" || { echo "[$(date -u +%FT%TZ)] FATAL: chmod 700 $BACKUPS failed" >&2; exit 1; }

exec 9> "$LOCKFILE"
if ! flock -n 9; then
    echo "[$(date -u +%FT%TZ)] WARN: another backup is running, cannot acquire lock" >&2
    exit 1
fi

had_error=0

backup_db() {
    local src="$1" name="$2"
    if [[ ! -f "$src" ]]; then
        echo "[$(date -u +%FT%TZ)] WARN: $src not found, skipping $name"
        return 0
    fi
    local tmp="$BACKUPS/${name}-${STAMP}.db.tmp"
    local out="$BACKUPS/${name}-${STAMP}.db.gz"
    local start=$SECONDS

    sqlite3 "$src" ".backup '$tmp'"

    # Integrity check before compressing — catch corruption early.
    # PRAGMA integrity_check returns "ok" or one error per line.
    local integrity
    integrity=$(sqlite3 "$tmp" "PRAGMA integrity_check;" 2>&1)
    if [[ "$integrity" != "ok" ]]; then
        echo "[$(date -u +%FT%TZ)] ERROR: integrity check failed for $name: $integrity" >&2
        rm -f "$tmp"
        return 1
    fi

    gzip -f "$tmp" || { echo "[$(date -u +%FT%TZ)] ERROR: gzip failed for $name" >&2; rm -f "$tmp"; return 1; }
    mv "${tmp}.gz" "$out" || { echo "[$(date -u +%FT%TZ)] ERROR: mv failed for $name" >&2; return 1; }

    local elapsed=$((SECONDS - start))
    local size
    size=$(du -h "$out" | cut -f1)
    echo "[$(date -u +%FT%TZ)] OK: $name -> $out ($size, ${elapsed}s)"

    # NULL-safe rotation: ls/parsing рушится на пробелах/newline в имени.
    # find -printf '%T@ %p\0' разделяет nul-байтом, sort/cut/head операют над
    # этим разделителем; имена остаются нетронутыми.
    local total
    total=$(find "$BACKUPS" -maxdepth 1 -type f -name "${name}-*.db.gz" -printf '.' | wc -c)
    if [[ "$total" -gt "$KEEP" ]]; then
        local to_delete=$((total - KEEP))
        find "$BACKUPS" -maxdepth 1 -type f -name "${name}-*.db.gz" -printf '%T@\t%p\0' \
            | sort -z -n \
            | head -z -n "$to_delete" \
            | cut -z -f2 \
            | while IFS= read -r -d '' old; do
                rm -f -- "$old"
                echo "[$(date -u +%FT%TZ)] rotated out: $old"
            done
    fi
}

backup_db "$BASE/cache/messages.db"      "messages" || had_error=1
backup_db "$BASE/dashboard/dashboard.db" "dashboard" || had_error=1

if [[ "$had_error" -eq 0 ]]; then
    echo "[$(date -u +%FT%TZ)] backup cycle complete"
else
    echo "[$(date -u +%FT%TZ)] backup cycle finished with errors" >&2
    exit 1
fi
