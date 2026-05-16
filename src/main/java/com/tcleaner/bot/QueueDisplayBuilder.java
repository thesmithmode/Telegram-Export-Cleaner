package com.tcleaner.bot;

import com.tcleaner.core.BotLanguage;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.User;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Component
public class QueueDisplayBuilder {

    private final BotI18n i18n;

    public QueueDisplayBuilder(BotI18n i18n) {
        this.i18n = i18n;
    }

    public String build(BotLanguage lang, boolean fromCache, long pendingInQueue, boolean hasActiveJob) {
        if (fromCache) {
            return i18n.msg(lang, "bot.queue.cached");
        }
        // pendingInQueue включает текущую job; aheadCount — без неё.
        long aheadCount = (pendingInQueue - 1) + (hasActiveJob ? 1 : 0);
        long myPosition = pendingInQueue + (hasActiveJob ? 1 : 0);
        if (aheadCount <= 0) {
            return i18n.msg(lang, "bot.queue.starting");
        }
        return i18n.msg(lang, "bot.queue.position", myPosition, aheadCount);
    }

    /**
     * Информация о выбранном диапазоне дат в сессии. Пустая строка если оба null.
     * Pure-функция: дата + i18n, никаких побочек.
     */
    public String dateInfo(BotLanguage lang, UserSession session) {
        if (session.getFromDate() == null && session.getToDate() == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(i18n.msg(lang, "bot.date.prefix")).append(' ');
        if (session.getFromDate() != null) {
            LocalDate from = LocalDateTime.parse(session.getFromDate()).toLocalDate();
            sb.append(i18n.msg(lang, "bot.date.from", from.format(BotInputParser.dateFormat())));
        } else {
            sb.append(i18n.msg(lang, "bot.date.from_chat_start"));
        }
        if (session.getToDate() != null) {
            LocalDate to = LocalDateTime.parse(session.getToDate()).toLocalDate();
            sb.append(i18n.msg(lang, "bot.date.to", to.format(BotInputParser.dateFormat())));
        } else {
            sb.append(i18n.msg(lang, "bot.date.to_today"));
        }
        return sb.toString();
    }

    /**
     * Имя для отображения. first+last с пробелом, либо null если оба пустые
     * (Telegram User: first_name обязателен по API, но в test/mock может быть null).
     */
    public static String displayName(User from) {
        String first = from.getFirstName();
        String last = from.getLastName();
        StringBuilder sb = new StringBuilder();
        if (first != null && !first.isBlank()) {
            sb.append(first);
        }
        if (last != null && !last.isBlank()) {
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(last);
        }
        return sb.isEmpty() ? null : sb.toString();
    }
}
