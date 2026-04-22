package com.tcleaner.dashboard.auth.telegram;

import com.tcleaner.dashboard.auth.DashboardUserService;
import com.tcleaner.dashboard.domain.DashboardRole;
import com.tcleaner.dashboard.domain.DashboardUser;
import com.tcleaner.dashboard.service.ingestion.BotUserUpserter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Outer-transactional граница для Telegram Mini App login. Объединяет upsert
 * BotUser и findOrCreate DashboardUser в одну транзакцию — без этого они
 * выполнялись бы в двух (каждый из методов сам {@code @Transactional}
 * REQUIRED), и при падении второго первая запись осталась бы в БД.
 *
 * <p>HTTP-уровень (валидация id, сессии, cookies, SecurityContext) остаётся
 * в контроллере — это non-DB операции, им транзакция не нужна.
 */
@Service
public class TelegramLoginService {

    private final BotUserUpserter botUserUpserter;
    private final DashboardUserService userService;

    public TelegramLoginService(BotUserUpserter botUserUpserter,
                                DashboardUserService userService) {
        this.botUserUpserter = botUserUpserter;
        this.userService = userService;
    }

    /**
     * Регистрирует/обновляет пользователя по верифицированным данным Telegram.
     * Вызов должен происходить только после {@code TelegramMiniAppAuthVerifier.verify}
     * и проверки {@code data.id() > 0}.
     *
     * @return созданный или обновлённый {@link DashboardUser}
     */
    @Transactional
    public LoginResult loginOrCreate(TelegramMiniAppLoginData data, long adminTelegramId) {
        long id = data.id();
        String firstName = data.firstName();
        String username = data.username();

        DashboardRole role;
        Long botUserId;
        if (id == adminTelegramId) {
            role = DashboardRole.ADMIN;
            botUserId = null;
        } else {
            botUserUpserter.upsert(id, username, firstName,
                    Instant.ofEpochSecond(data.authDate()));
            role = DashboardRole.USER;
            botUserId = id;
        }

        DashboardUser user = userService.findOrCreate(id, firstName, username, role, botUserId);
        return new LoginResult(user, role);
    }

    /** Результат логина: созданная/обновлённая запись + назначенная роль. */
    public record LoginResult(DashboardUser user, DashboardRole role) {
    }
}
