# Plan: ux-fast — dashboard.db query optimization

## Context

Dashboard: Java Spring Boot читает SQLite dashboard.db через JdbcTemplate.
50k users → concurrent status polls → latency bottleneck в SQL queries.

Primary bottleneck: StatsQueryService queries без composite indexes + N+1 в CacheMetricsService.

## Метрики keep/discard

Primary: `p99_overview_ms` (overview endpoint) ИЛИ `p99_n1_resolve_ms` (N+1 pattern)
Secondary: `peak_rss_mb`, `p99_topchats_ms`, `p99_status_ms`
Keep: primary ≥5% лучше И peak_rss не вырос >10%
Discard: любая регрессия primary ≥2%

## Гипотезы (по ROI)

P1 — N+1 → batch resolve (КРИТИЧНО):
- CacheMetricsService.resolveChatMeta() = N отдельных DB queries per top-chat
- Fix: один SELECT ... WHERE id IN (?,?,?) вместо цикла
- Файл: src/main/java/.../cache/CacheMetricsService.java:113-129
- Bench: p99_n1_resolve_ms → p99_batch_resolve_ms
- Ожидание: -80-90% latency на этом path

P2 — composite index status+started_at (ВЫСОКИЙ):
- statusBreakdown() фильтрует по status + date range
- Текущий: idx_events_status отдельно, нет composite
- Fix: Liquibase миграция добавить idx_events_status_started ON export_events(status, started_at DESC)
- Bench: p99_status_ms
- Ожидание: -40-60% при больших таблицах

P3 — composite index chat+date для topChats (ВЫСОКИЙ):
- topChats() GROUP BY chat_ref_id + WHERE started_at range
- idx_events_chat_started уже есть, но планировщик может не использовать при GROUP BY
- Fix: covering index (chat_ref_id, started_at, messages_count, bytes_count)
- Bench: p99_topchats_ms

P4 — polling 15s → 30s + visibility (СРЕДНИЙ):
- subscriptions.js:207 setTimeout 15s при active in-progress
- Fix: 30s + document.addEventListener('visibilitychange') — стоп при hidden tab
- Файл: src/main/resources/static/dashboard/js/pages/subscriptions.js

P5 — overview covering index (СРЕДНИЙ):
- periodTotals() = COUNT + SUM WHERE started_at range
- Fix: covering index (started_at, bot_user_id, messages_count, bytes_count)
- Bench: p99_overview_ms

P6 — НЕ трогать:
- Redis Streams consumer threading (риск + OOM)
- Cache TTL sync (coordinated Java+Python change)
- Pagination API (breaking change)

## Дополнительные оптимизации (сделано поверх P1-P5)

P7 — split topUsersByPeriod JOIN → batch (DONE):
- GROUP BY + LEFT JOIN на 50k events → 58ms. Fix: aggregate без JOIN + batch PK lookup
- Результат: -47% (58→31ms)

P8 — split topChats JOIN → batch (DONE):
- Та же проблема для topChats. Fix: aggregate без JOIN + batch chats lookup
- Результат: -29% (35→25ms)

## SQL-потолок (дальше без schema change нельзя)

- timeseries (38ms): strftime() не индексируется при WHERE started_at range. HISTORICAL cached.
- top_users (31ms): O(n_events) hash aggregate по date range — фундаментально.
- active_users (13ms): COUNT(DISTINCT) — нельзя мержить с SUM (проверено → регрессия).

## Урок: SQLite query planner

- COUNT(DISTINCT col) + SUM в одном запросе → отдельный execution plan → covering index не используется
- Covering index (started_at DESC, col1, col2) эффективен для COUNT(*)+SUM, неэффективен для DISTINCT
- JOIN на агрегирующем запросе → убирать, заменять batch PK lookup
