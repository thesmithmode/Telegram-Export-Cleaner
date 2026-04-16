package com.tcleaner.dashboard.security;

import com.tcleaner.dashboard.domain.DashboardRole;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * Единая точка RBAC-проверок: ADMIN видит любого пользователя,
 * USER — только свой {@code botUserId}. Перетекание данных между ролями
 * проверяется здесь, а не в URL-матчинге, чтобы не разъехаться при добавлении новых эндпоинтов.
 */
@Component
public class BotUserAccessPolicy {

    /**
     * Возвращает эффективный {@code botUserId} для запроса.
     * ADMIN может запрашивать любого ({@code requestedUserId=null} → без фильтра, вернёт 0).
     * USER может запрашивать только себя; чужой id или null → {@link AccessDeniedException}.
     *
     * @param role           роль залогиненного пользователя
     * @param ownBotUserId   botUserId авторизованного пользователя (null для ADMIN без привязки)
     * @param requestedUserId id из query-параметра (null = весь overview)
     * @return эффективный userId (0 = «все»), никогда не возвращает чужой id для USER
     */
    public long effectiveUserId(DashboardRole role, Long ownBotUserId, Long requestedUserId) {
        if (role == DashboardRole.ADMIN) {
            return requestedUserId != null ? requestedUserId : 0L;
        }
        // USER: может видеть только себя
        if (ownBotUserId == null) {
            throw new AccessDeniedException("Аккаунт USER не привязан к Telegram-пользователю");
        }
        if (requestedUserId != null && !requestedUserId.equals(ownBotUserId)) {
            throw new AccessDeniedException("Доступ запрещён: нельзя просматривать данные другого пользователя");
        }
        return ownBotUserId;
    }

    /**
     * Проверяет, может ли пользователь видеть конкретного botUserId.
     * ADMIN — всегда. USER — только себя.
     */
    public boolean canSeeUser(DashboardRole role, Long ownBotUserId, long targetBotUserId) {
        if (role == DashboardRole.ADMIN) {
            return true;
        }
        return ownBotUserId != null && ownBotUserId.longValue() == targetBotUserId;
    }
}
