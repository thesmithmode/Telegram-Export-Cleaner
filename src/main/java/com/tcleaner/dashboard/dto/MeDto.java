package com.tcleaner.dashboard.dto;

import com.tcleaner.dashboard.domain.DashboardRole;

/**
 * Отдаётся для {@code /dashboard/api/me}: минимум данных о текущем пользователе,
 * которого фронт использует для скрытия ADMIN-only UI-элементов у USER-роли.
 */
public record MeDto(String username, DashboardRole role, Long botUserId) {}
