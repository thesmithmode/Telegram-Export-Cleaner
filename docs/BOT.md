# Telegram Bot

Реализован в `com/tcleaner/bot/ExportBot.java`. Только личные чаты. Команды: `/start`, `/settings` (язык), `/cancel`.

## Нетривиальные детали

**Блокировка параллельных экспортов:** перед enqueue проверяется `active_export:{userId}` в Redis. Дубль → подсказка дождаться или `/cancel`.

**Subscription confirmation:** `ConfirmationScheduler` раз в 7 дней шлёт inline-кнопку "Да, оставить активной". Callback `sub_confirm:{id}` → `SubscriptionService.confirmReceived(id)`. Нет ответа 48 ч → ARCHIVED.

**i18n:** язык хранится в `bot_users.language` (единый источник для бота и дашборда). RTL-языки (fa, ar) — бот добавляет RLM в форматирование.

**Числовые chat ID** в текущем UX напрямую не поддерживаются пользовательским вводом.
