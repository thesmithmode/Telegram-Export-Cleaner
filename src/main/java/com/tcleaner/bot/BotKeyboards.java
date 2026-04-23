package com.tcleaner.bot;

import com.tcleaner.core.BotLanguage;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

import java.util.ArrayList;
import java.util.List;

/**
 * Билдеры InlineKeyboardMarkup для Telegram-бота. Выделено из ExportBot
 * (God class). Зависит только от {@link BotI18n} (тексты кнопок) и от
 * package-private CB_* констант в {@link ExportBot}.
 */
@Component
public class BotKeyboards {

    private final BotI18n i18n;

    public BotKeyboards(BotI18n i18n) {
        this.i18n = i18n;
    }

    public InlineKeyboardMarkup dateChoiceKeyboard(BotLanguage lang) {
        return InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(button(lang, "bot.button.export_all", ExportBot.CB_EXPORT_ALL)))
                .keyboardRow(new InlineKeyboardRow(
                        button(lang, "bot.button.last_24h", ExportBot.CB_LAST_24H),
                        button(lang, "bot.button.last_3d", ExportBot.CB_LAST_3D)))
                .keyboardRow(new InlineKeyboardRow(
                        button(lang, "bot.button.last_7d", ExportBot.CB_LAST_7D),
                        button(lang, "bot.button.last_30d", ExportBot.CB_LAST_30D)))
                .keyboardRow(new InlineKeyboardRow(button(lang, "bot.button.date_range", ExportBot.CB_DATE_RANGE)))
                .keyboardRow(new InlineKeyboardRow(button(lang, "bot.button.back", ExportBot.CB_BACK_TO_MAIN)))
                .build();
    }

    public InlineKeyboardMarkup fromDateKeyboard(BotLanguage lang) {
        return InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(button(lang, "bot.button.from_start", ExportBot.CB_FROM_START)))
                .keyboardRow(new InlineKeyboardRow(button(lang, "bot.button.back", ExportBot.CB_BACK_TO_DATE_CHOICE)))
                .build();
    }

    public InlineKeyboardMarkup toDateKeyboard(BotLanguage lang) {
        return InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(button(lang, "bot.button.to_today", ExportBot.CB_TO_TODAY)))
                .keyboardRow(new InlineKeyboardRow(button(lang, "bot.button.back", ExportBot.CB_BACK_TO_FROM_DATE)))
                .build();
    }

    public InlineKeyboardMarkup settingsKeyboard(BotLanguage lang) {
        return InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(
                        button(lang, "bot.settings.change_language", ExportBot.CB_SETTINGS_LANGUAGE)))
                .build();
    }

    public InlineKeyboardMarkup mainMenuKeyboard(BotLanguage lang) {
        return InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(
                        button(lang, "bot.settings.title", ExportBot.CB_SETTINGS_OPEN)))
                .build();
    }

<<<<<<< HEAD
=======
    /**
     * Клавиатура с одной кнопкой "Подтвердить подписку" для confirmation-flow.
     * Callback data: "sub_confirm:{subscriptionId}".
     */
    public InlineKeyboardMarkup subConfirmKeyboard(BotLanguage lang, long subscriptionId) {
        return InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(
                        InlineKeyboardButton.builder()
                                .text(i18n.msg(lang, "bot.button.sub_confirm"))
                                .callbackData(ExportBot.CB_SUB_CONFIRM_PREFIX + subscriptionId)
                                .build()))
                .build();
    }

>>>>>>> feat/chat-subscriptions
    /** Клавиатура выбора языка — 2 кнопки в ряд. Callback {@code lang:<code>}. */
    public InlineKeyboardMarkup languageChoiceKeyboard() {
        InlineKeyboardMarkup.InlineKeyboardMarkupBuilder markup = InlineKeyboardMarkup.builder();
        List<InlineKeyboardButton> row = new ArrayList<>(2);
        for (BotLanguage lang : BotLanguage.allActive()) {
            row.add(InlineKeyboardButton.builder()
                    .text(lang.getDisplayName())
                    .callbackData(ExportBot.CB_LANG_PREFIX + lang.getCode())
                    .build());
            if (row.size() == 2) {
                markup.keyboardRow(new InlineKeyboardRow(row));
                row = new ArrayList<>(2);
            }
        }
        if (!row.isEmpty()) {
            markup.keyboardRow(new InlineKeyboardRow(row));
        }
        return markup.build();
    }

    private InlineKeyboardButton button(BotLanguage lang, String msgKey, String callbackData) {
        return InlineKeyboardButton.builder()
                .text(i18n.msg(lang, msgKey))
                .callbackData(callbackData)
                .build();
    }
}
