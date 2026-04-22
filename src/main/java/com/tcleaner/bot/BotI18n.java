package com.tcleaner.bot;

import com.tcleaner.core.BotLanguage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.MessageSource;
import org.springframework.context.NoSuchMessageException;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Обёртка над {@link MessageSource} для бота. Резолвит ключи в правильную локаль,
 * подставляет аргументы {@code MessageFormat}-ом, в крайнем случае возвращает сам ключ
 * (лучше показать {@code bot.start.help} чем упасть).
 */
@Component
public class BotI18n {

    private static final Logger log = LoggerFactory.getLogger(BotI18n.class);

    private final MessageSource botMessageSource;

    public BotI18n(@Qualifier("botMessageSource") MessageSource botMessageSource) {
        this.botMessageSource = botMessageSource;
    }

    public String msg(BotLanguage lang, String key, Object... args) {
        return msg(lang != null ? lang.getLocale() : Locale.ENGLISH, key, args);
    }

    public String msg(Locale locale, String key, Object... args) {
        Locale resolved = locale != null ? locale : Locale.ENGLISH;
        try {
            return botMessageSource.getMessage(key, args, resolved);
        } catch (NoSuchMessageException ex) {
            // Ключа нет даже в EN-fallback: опечатка разработчика либо не добавили ключ.
            // Показываем ключ (лучше "bot.start.help" чем пустое сообщение) + diag-лог.
            log.warn("Отсутствует i18n-ключ: key={} locale={}", key, resolved);
            return key;
        }
    }

    /**
     * Язык, выбранный в {@code BotUser.language}, или {@link BotLanguage#EN} если код
     * null/невалидный. Используется для резолва локали в точках, где известен только
     * строковый код из БД.
     */
    public BotLanguage resolveLanguage(String code) {
        return BotLanguage.fromCodeOrDefault(code, BotLanguage.EN);
    }
}
