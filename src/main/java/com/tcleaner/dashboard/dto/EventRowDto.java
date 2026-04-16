package com.tcleaner.dashboard.dto;

/**
 * Raw-строка для таблицы «последние экспорты» (страница events).
 * Все даты — ISO-строки из SQLite.
 */
public record EventRowDto(
        String taskId,
        Long botUserId,
        String username,
        String chatTitle,
        String canonicalChatId,
        String startedAt,
        String finishedAt,
        String status,
        Long messagesCount,
        Long bytesCount,
        String source,
        String errorMessage) {}
