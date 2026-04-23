package com.tcleaner.dashboard.domain;

/**
 * Жизненный цикл подписки на периодический экспорт ({@code chat_subscriptions.status}).
 * Значения сериализуются в TEXT-колонку через {@code EnumType.STRING};
 * схема БД ограничивает допустимые значения: CHECK('ACTIVE','PAUSED','ARCHIVED').
 *
 * <ul>
 *   <li>{@link #ACTIVE}   — нормальный рабочий режим: планировщик учитывает подписку.</li>
 *   <li>{@link #PAUSED}   — две подряд неудачи (consecutive_failures ≥ 2) либо ручная
 *                           пауза пользователем; планировщик пропускает подписку до
 *                           явной разблокировки.</li>
 *   <li>{@link #ARCHIVED} — soft-delete: пользователь не подтвердил подписку в течение
 *                           48 ч после отправки confirm-сообщения (или 7 дней без
 *                           активности). В дашборде остаётся видимой, но не запускается.</li>
 * </ul>
 */
public enum SubscriptionStatus {
    ACTIVE,
    PAUSED,
    ARCHIVED
}
