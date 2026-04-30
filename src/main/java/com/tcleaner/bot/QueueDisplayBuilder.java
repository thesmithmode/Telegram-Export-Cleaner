package com.tcleaner.bot;

import com.tcleaner.core.BotLanguage;
import org.springframework.stereotype.Component;

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
}
