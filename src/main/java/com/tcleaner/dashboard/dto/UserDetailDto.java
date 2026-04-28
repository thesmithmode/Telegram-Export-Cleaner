package com.tcleaner.dashboard.dto;

public record UserDetailDto(
        long botUserId,
        String username,
        String displayName,
        int totalExports,
        long totalMessages,
        long totalBytes,
        String firstSeen,
        String lastSeen
) {}
