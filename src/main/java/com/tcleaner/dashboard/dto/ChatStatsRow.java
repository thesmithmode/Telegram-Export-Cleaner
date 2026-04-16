package com.tcleaner.dashboard.dto;

/**
 * Строка в таблице "топ чатов" — агрегация по {@code chat_ref_id} из {@code export_events}.
 */
public record ChatStatsRow(
        long chatRefId,
        String canonicalChatId,
        String chatTitle,
        long exportCount,
        long totalMessages,
        long totalBytes
) {}
