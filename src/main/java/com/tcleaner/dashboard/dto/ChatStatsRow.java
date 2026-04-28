package com.tcleaner.dashboard.dto;

public record ChatStatsRow(
        long chatRefId,
        String canonicalChatId,
        String chatTitle,
        long exportCount,
        long totalMessages,
        long totalBytes
) {}
