# autoresearch — dashboard UX optimization (ветка ux-fast)

Цель: ускорить dashboard.db queries и снизить latency для 50k concurrent users.
Ветка `ux-fast`. Никогда не мерджить в main/dev напрямую.

## Контекст

Dashboard: Java Spring Boot + SQLite (dashboard.db via JdbcTemplate).
Bottleneck: SQL queries без covering indexes + N+1 в CacheMetricsService + aggressive polling.

## Файлы для модификации

- `src/main/resources/db/changelog/db.changelog-master.sql` — Liquibase indexes
- `src/main/java/.../service/cache/CacheMetricsService.java` — N+1 fix
- `src/main/java/.../service/stats/StatsQueryService.java` — query optimization
- `src/main/resources/static/dashboard/js/pages/subscriptions.js` — polling interval

НЕ трогать:
- API контракты (response shape)
- Redis Streams consumer threading
- export-worker/ (отдельная ветка worker-fast)
- tests/ без явного запроса

## Bench

### bench_dashboard.py — in-memory SQLite с реальной schema

Засевает 50k events, 5k users, 500 chats.
Замеряет latency ключевых queries:
- Primary: `p99_overview_ms`, `p99_n1_resolve_ms`
- Secondary: `p99_topchats_ms`, `p99_status_ms`, `peak_rss_mb`

Keep: primary ≥5% лучше
Discard: регрессия ≥2%

## Experiment loop

LOOP FOREVER:

1. Посмотреть git state (ветка ux-fast / текущий коммит)
2. Выбрать гипотезу из `plan.md` по ROI
3. Изменить минимально (один файл по возможности)
4. `git commit` (PERF/FIX/CHORE: описание на русском)
5. `git push origin ux-fast` — CI запускает bench_dashboard.py
6. Polling loop до CI_DONE
7. Grep metrics из CI log
8. Записать в `results.tsv`
9. Keep/discard по критерию

## Правила

- НЕ ОСТАНАВЛИВАТЬСЯ без команды
- Каждый коммит = одна гипотеза
- 3 краша → пропустить тему
- Bench падает → фиксить bench, не код
- force-push запрещён — git revert для отката
