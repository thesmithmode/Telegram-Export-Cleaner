package com.tcleaner.bot;

import com.tcleaner.core.BotLanguage;
import com.tcleaner.dashboard.events.StatsEventPayload;
import com.tcleaner.dashboard.events.StatsEventType;
import com.tcleaner.dashboard.events.StatsStreamPublisher;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer;
import org.telegram.telegrambots.longpolling.starter.SpringLongPollingBot;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.api.objects.message.Message;

import java.time.Instant;
import java.util.List;

@Component
@ConditionalOnExpression("'${telegram.bot.token:}' != ''")
public class ExportBot implements SpringLongPollingBot, LongPollingSingleThreadUpdateConsumer {

    private static final Logger log = LoggerFactory.getLogger(ExportBot.class);

    static final String CB_EXPORT_ALL = "export_all";
    static final String CB_DATE_RANGE = "date_range";
    static final String CB_FROM_START = "from_start";
    static final String CB_TO_TODAY = "to_today";
    static final String CB_LAST_24H = "last_24h";
    static final String CB_LAST_3D = "last_3d";
    static final String CB_LAST_7D = "last_7d";
    static final String CB_LAST_30D = "last_30d";
    static final String CB_BACK_TO_MAIN = "back_main";
    static final String CB_BACK_TO_DATE_CHOICE = "back_date_choice";
    static final String CB_BACK_TO_FROM_DATE = "back_from_date";
    static final String CB_CANCEL_EXPORT = "cancel_export";
    static final String CB_LANG_PREFIX = "lang:";
    static final String CB_SETTINGS_LANGUAGE = "settings:language";
    static final String CB_SETTINGS_OPEN = "settings:open";
    static final String CB_SUB_CONFIRM_PREFIX = "sub_confirm:";

    private static final java.util.Set<String> BANNED_MINIAPP_HOSTS = java.util.Set.of(
            "localhost", "127.0.0.1", "0.0.0.0", "::1", "[::1]"
    );

    private final String botToken;
    private final String miniAppUrl;
    private final BotMessenger messenger;
    private final BotI18n i18n;
    private final ObjectProvider<StatsStreamPublisher> statsPublisherProvider;
    private final Counter consumeErrorsCounter;
    private final BotSecurityGate securityGate;
    private final ExportBotCommandHandler commandHandler;
    private final ExportBotCallbackHandler callbackHandler;

    public ExportBot(
            @Value("${telegram.bot.token}") String botToken,
            @Value("${dashboard.mini-app.url}") String miniAppUrl,
            BotMessenger messenger,
            BotI18n i18n,
            ObjectProvider<StatsStreamPublisher> statsPublisherProvider,
            MeterRegistry meterRegistry,
            BotSecurityGate securityGate,
            ExportBotCommandHandler commandHandler,
            ExportBotCallbackHandler callbackHandler
    ) {
        java.net.URI parsed;
        try {
            parsed = java.net.URI.create(miniAppUrl);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(
                    "dashboard.mini-app.url некорректен: " + miniAppUrl
                    + " (не парсится как URI). Установите TRAEFIK_DASHBOARD_DOMAIN.", ex);
        }
        String scheme = parsed.getScheme() == null ? "" : parsed.getScheme().toLowerCase();
        String host = parsed.getHost() == null ? "" : parsed.getHost().toLowerCase();
        if (!"https".equals(scheme) || host.isEmpty() || BANNED_MINIAPP_HOSTS.contains(host)) {
            throw new IllegalStateException(
                    "dashboard.mini-app.url некорректен: " + miniAppUrl
                    + ". Требуется публичный HTTPS-URL (Telegram Mini App не принимает http/localhost)."
                    + " Установите TRAEFIK_DASHBOARD_DOMAIN в окружении контейнера.");
        }
        this.botToken = botToken;
        this.miniAppUrl = miniAppUrl;
        this.messenger = messenger;
        this.i18n = i18n;
        this.statsPublisherProvider = statsPublisherProvider;
        this.consumeErrorsCounter = Counter.builder("bot.consume.errors").register(meterRegistry);
        this.securityGate = securityGate;
        this.commandHandler = commandHandler;
        this.callbackHandler = callbackHandler;
        log.info("Telegram-бот инициализирован");
    }

    @EventListener(ApplicationReadyEvent.class)
    void registerBotCommands() {
        List<BotCommand> defaultCommands = buildCommands(BotLanguage.EN);
        messenger.setMyCommands(defaultCommands, null);
        for (BotLanguage lang : BotLanguage.allActive()) {
            messenger.setMyCommands(buildCommands(lang), lang.getTelegramApiCode());
        }
        messenger.setChatMenuButton(miniAppUrl, "Dashboard");
    }

    private List<BotCommand> buildCommands(BotLanguage lang) {
        return List.of(
                new BotCommand("/start", i18n.msg(lang, "bot.command.start")),
                new BotCommand("/cancel", i18n.msg(lang, "bot.command.cancel")),
                new BotCommand("/settings", i18n.msg(lang, "bot.command.settings"))
        );
    }

    @Override
    public String getBotToken() {
        return botToken;
    }

    @Override
    public LongPollingUpdateConsumer getUpdatesConsumer() {
        return this;
    }

    @Override
    public void consume(Update update) {
        long userId = extractUserId(update);
        if (userId > 0 && (securityGate.isBlocked(userId) || securityGate.isFlooded(userId))) {
            return;
        }
        try {
            processUpdate(update);
        } catch (Exception ex) {
            consumeErrorsCounter.increment();
            log.error("Update processing fail: {}", ex.getMessage(), ex);
        }
    }

    private static long extractUserId(Update update) {
        if (update.hasCallbackQuery() && update.getCallbackQuery().getFrom() != null) {
            return update.getCallbackQuery().getFrom().getId();
        }
        if (update.hasMessage() && update.getMessage().getFrom() != null) {
            return update.getMessage().getFrom().getId();
        }
        return 0L;
    }

    private void processUpdate(Update update) {
        if (update.hasCallbackQuery()) {
            CallbackQuery callback = update.getCallbackQuery();
            publishBotUserSeen(callback.getFrom());
            callbackHandler.handleCallbackSafe(callback);
            return;
        }

        if (!update.hasMessage()) {
            return;
        }

        Message message = update.getMessage();
        if (!"private".equals(message.getChat().getType())) {
            return;
        }

        publishBotUserSeen(message.getFrom());

        if (message.hasText()) {
            commandHandler.handleMessageText(
                    message.getChatId(), message.getFrom().getId(), message.getText().trim());
        }
    }

    private void publishBotUserSeen(User from) {
        StatsStreamPublisher publisher = statsPublisherProvider.getIfAvailable();
        if (publisher == null || from == null) {
            return;
        }
        try {
            publisher.publish(StatsEventPayload.builder()
                    .type(StatsEventType.BOT_USER_SEEN)
                    .botUserId(from.getId())
                    .username(from.getUserName())
                    .displayName(QueueDisplayBuilder.displayName(from))
                    .ts(Instant.now())
                    .build());
        } catch (Exception ex) {
            log.warn("bot_user.seen не опубликовано (stats analytics loss): {}", ex.getMessage());
        }
    }
}
