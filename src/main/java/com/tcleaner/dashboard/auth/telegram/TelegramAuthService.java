package com.tcleaner.dashboard.auth.telegram;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Auth pipeline для Telegram Mini App: парсинг initData → HMAC → nonce (replay) →
 * user.id sanity → loginOrCreate. Каждая стадия возвращает либо причину отказа,
 * либо переходит дальше. Вынесено из TelegramAuthController, чтобы оставить
 * в контроллере только orchestration (session rotation, cookies, redirects)
 * и поддавать pipeline юнит-тестам без HttpServlet'а.
 *
 * <p>Replay-policy: <b>fail-closed</b> при Redis outage — иначе initData можно
 * было бы переиспользовать всё окно MAX_AGE. Возвращается код "infra", чтобы
 * UI отличал brief-инфраструктурный сбой от подделки.
 */
@Service
public class TelegramAuthService {

    private static final Logger log = LoggerFactory.getLogger(TelegramAuthService.class);
    private static final String NONCE_PREFIX = "tg:nonce:";

    private final TelegramMiniAppAuthVerifier verifier;
    private final TelegramLoginService loginService;
    private final StringRedisTemplate redis;

    public TelegramAuthService(TelegramMiniAppAuthVerifier verifier,
                                TelegramLoginService loginService,
                                StringRedisTemplate redis) {
        this.verifier = verifier;
        this.loginService = loginService;
        this.redis = redis;
    }

    public LoginOutcome login(String initData, String clientIp, long adminTelegramId) {
        TelegramMiniAppLoginData data = parseOrNull(initData, clientIp);
        if (data == null) {
            return LoginOutcome.fail("invalid");
        }
        if (!verify(data, clientIp)) {
            return LoginOutcome.fail("invalid");
        }
        String nonceResult = claimNonce(data, clientIp);
        if (nonceResult != null) {
            return LoginOutcome.fail(nonceResult);
        }
        if (data.id() <= 0) {
            log.warn("Telegram Mini App login rejected: отсутствует user.id в initData ip={}", clientIp);
            return LoginOutcome.fail("invalid");
        }
        // tgUserId передаём отдельно — DashboardUser.botUserId nullable для ADMIN,
        // controller'у нужен оригинальный Telegram ID для tg_uid cookie.
        return LoginOutcome.ok(loginService.loginOrCreate(data, adminTelegramId), data.id());
    }

    private TelegramMiniAppLoginData parseOrNull(String initData, String ip) {
        try {
            return TelegramMiniAppLoginData.parse(initData);
        } catch (IllegalArgumentException e) {
            log.warn("Telegram Mini App login: ошибка парсинга initData ip={}: {}", ip, e.getMessage());
            return null;
        }
    }

    private boolean verify(TelegramMiniAppLoginData data, String ip) {
        try {
            verifier.verify(data);
            return true;
        } catch (TelegramAuthenticationException e) {
            log.warn("Telegram Mini App login rejected: id={} ip={} reason={}",
                    data.id(), ip, e.getMessage());
            return false;
        }
    }

    /** Возвращает null при успехе, иначе код ошибки для редиректа. */
    private String claimNonce(TelegramMiniAppLoginData data, String ip) {
        try {
            Boolean isNew = redis.opsForValue().setIfAbsent(
                    NONCE_PREFIX + data.hash(), "1", TelegramMiniAppAuthVerifier.MAX_AGE);
            if (!Boolean.TRUE.equals(isNew)) {
                log.warn("Telegram Mini App login rejected: replay detected, id={} ip={}",
                        data.id(), ip);
                return "invalid";
            }
            return null;
        } catch (Exception e) {
            log.error("Redis nonce check failed ip={}: {}", ip, e.getMessage(), e);
            return "infra";
        }
    }

    /**
     * Результат прохождения auth pipeline. Либо успешный {@link TelegramLoginService.LoginResult}
     * + tgUserId из initData (DashboardUser.botUserId nullable для ADMIN), либо код ошибки.
     */
    public record LoginOutcome(TelegramLoginService.LoginResult loginResult,
                                long tgUserId,
                                String errorCode) {
        public static LoginOutcome ok(TelegramLoginService.LoginResult r, long tgUserId) {
            return new LoginOutcome(r, tgUserId, null);
        }

        public static LoginOutcome fail(String code) {
            return new LoginOutcome(null, 0L, code);
        }

        public boolean isFailure() {
            return errorCode != null;
        }
    }
}
