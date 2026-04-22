package com.tcleaner.dashboard.config;

import com.tcleaner.core.BotLanguage;
import com.tcleaner.dashboard.auth.DashboardUserDetails;
import com.tcleaner.dashboard.service.ingestion.BotUserUpserter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.LocaleResolver;

import java.util.Locale;

/**
 * Резолвит locale для дашборда из {@code BotUser.language} — единый источник правды
 * с ботом. Если пользователь не аутентифицирован или ещё не выбрал язык — fallback
 * на {@code Accept-Language} из запроса, затем на русский (историческая база юзеров).
 */
public class BotUserLocaleResolver implements LocaleResolver {

    private final BotUserUpserter userUpserter;
    private final Locale defaultLocale;

    public BotUserLocaleResolver(BotUserUpserter userUpserter, Locale defaultLocale) {
        this.userUpserter = userUpserter;
        this.defaultLocale = defaultLocale;
    }

    @Override
    public Locale resolveLocale(HttpServletRequest request) {
        Long botUserId = currentBotUserId();
        if (botUserId != null) {
            Locale stored = userUpserter.getLanguage(botUserId)
                    .flatMap(BotLanguage::fromCode)
                    .map(BotLanguage::getLocale)
                    .orElse(null);
            if (stored != null) {
                return stored;
            }
        }
        Locale fromHeader = request.getLocale();
        return fromHeader != null ? fromHeader : defaultLocale;
    }

    @Override
    public void setLocale(HttpServletRequest request, HttpServletResponse response, Locale locale) {
        // Явная смена locale делается через POST /dashboard/api/me/settings/language —
        // она пишет в BotUser.language, и следующий запрос подхватит новый язык.
        // Здесь ничего не делаем; Spring вызывает setLocale из LocaleChangeInterceptor,
        // который мы не подключаем.
    }

    private static Long currentBotUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof DashboardUserDetails details)) {
            return null;
        }
        return details.getBotUserId();
    }
}
