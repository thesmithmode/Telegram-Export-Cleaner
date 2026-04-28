package com.tcleaner.dashboard.security;

import com.tcleaner.dashboard.domain.DashboardRole;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

// RBAC здесь, не в URL-матчинге — иначе новые эндпоинты забывают добавить матчер.
// ADMIN: requestedUserId=null → 0 (все). USER: чужой id → AccessDeniedException.
@Component
public class BotUserAccessPolicy {

    public long effectiveUserId(DashboardRole role, Long ownBotUserId, Long requestedUserId) {
        if (role == DashboardRole.ADMIN) {
            return requestedUserId != null ? requestedUserId : 0L;
        }
        if (ownBotUserId == null) {
            throw new AccessDeniedException("Аккаунт USER не привязан к Telegram-пользователю");
        }
        if (requestedUserId != null && !requestedUserId.equals(ownBotUserId)) {
            throw new AccessDeniedException("Доступ запрещён: нельзя просматривать данные другого пользователя");
        }
        return ownBotUserId;
    }

    public boolean canSeeUser(DashboardRole role, Long ownBotUserId, long targetBotUserId) {
        if (role == DashboardRole.ADMIN) {
            return true;
        }
        return ownBotUserId != null && ownBotUserId.longValue() == targetBotUserId;
    }
}
