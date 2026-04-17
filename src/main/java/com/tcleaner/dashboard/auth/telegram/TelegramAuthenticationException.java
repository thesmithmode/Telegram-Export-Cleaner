package com.tcleaner.dashboard.auth.telegram;

import org.springframework.security.core.AuthenticationException;

/**
 * Бросается при неудачной Telegram-аутентификации: плохой hash, протухший
 * auth_date, отсутствие BotUser для не-админа.
 */
public class TelegramAuthenticationException extends AuthenticationException {
    public TelegramAuthenticationException(String message) {
        super(message);
    }
}
