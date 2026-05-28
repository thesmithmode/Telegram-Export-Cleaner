---
name: project-architecture-notes
description: Ключевые паттерны и архитектурные решения проекта Telegram-export-clean
metadata:
  type: project
---

Spring Boot 3.4 / Java 21 + Python Pyrogram worker + SQLite (Liquibase) + Redis + Caffeine.

**Ключевые паттерны:**
- Redis List как очередь задач: 3 приоритета (express / main / subscription). Java пишет, Python читает через BLMOVE (atomic).
- StatsStreamPublisher → Redis Stream `stats:events` → StatsStreamConsumer → ExportEventIngestionService. At-least-once, idempotent по task_id.
- ChatUpserter.findByCanonicalChatIdAndTopicId — кастомный @Query с `(:topicId IS NULL AND c.topicId IS NULL) OR c.topicId = :topicId` — null-safe (не derived method).
- Subscription UNIQUE: partial index `uk_subscriptions_one_active_per_user WHERE status='ACTIVE'` + сервисная проверка.
- StatsQueryService — self-reference через `@Lazy @Autowired StatsQueryService self` для @Cacheable nested calls.
- BotSecurityGate: Redis blacklist + Caffeine local flood counter (expireAfterWrite=5s).
- TelegramAuthController: replay protection через Redis nonce по hash (TTL=MAX_AGE). Fail-closed.
- ExportJobProducer.getActiveExport — Redis pipeline (5 команд → 1 RTT): проверяет job:processing, job:completed, job:failed, очереди.
- Temp file в /data/import (Docker volume), удаляется в finally StreamingResponseBody.

**Known fragile patterns:**
- maybeBumpUserTotals изменяет managed entity без явного save() — работает через dirty-checking JPA, хрупко при рефакторинге.
- isInDesiredWindow open-ended для normal case (нет cap на 6h).
- getActiveExport false-positive при queueSize>0 и задача чужая в очереди — нет проверки job_json:{taskId}.

**Why:** Записано по итогам code review сессии 2026-05-15.
**How to apply:** При работе с subscription scheduler, ingestion service, очередями — проверять эти паттерны.
