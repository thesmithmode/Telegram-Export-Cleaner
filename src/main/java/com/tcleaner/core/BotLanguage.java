package com.tcleaner.core;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Поддерживаемые языки UI бота и дашборда.
 *
 * <p>Набор = топ-10 языков аудитории Telegram. Код ({@link #getCode()}) —
 * каноничный идентификатор, используется в БД ({@code bot_users.language}),
 * Telegram API ({@code language_code}) и как имя ResourceBundle-суффикса.
 *
 * <p>Fallback-стратегия: {@link #EN} — универсальный fallback для ключей,
 * отсутствующих в остальных локалях (настраивается в {@code BotI18nConfig}).
 */
public enum BotLanguage {

    RU("ru", "🇷🇺 Русский",        Locale.forLanguageTag("ru"),    false),
    EN("en", "🇬🇧 English",        Locale.forLanguageTag("en"),    false),
    ES("es", "🇪🇸 Español",        Locale.forLanguageTag("es"),    false),
    PT_BR("pt-BR", "🇧🇷 Português", Locale.forLanguageTag("pt-BR"), false),
    DE("de", "🇩🇪 Deutsch",        Locale.forLanguageTag("de"),    false),
    TR("tr", "🇹🇷 Türkçe",         Locale.forLanguageTag("tr"),    false),
    ID("id", "🇮🇩 Indonesia",      Locale.forLanguageTag("id"),    false),
    FA("fa", "🇮🇷 فارسی",          Locale.forLanguageTag("fa"),    true),
    AR("ar", "🇸🇦 العربية",        Locale.forLanguageTag("ar"),    true),
    ZH("zh", "🇨🇳 中文",            Locale.forLanguageTag("zh"),    false);

    private final String code;
    private final String displayName;
    private final Locale locale;
    private final boolean rtl;

    BotLanguage(String code, String displayName, Locale locale, boolean rtl) {
        this.code = code;
        this.displayName = displayName;
        this.locale = locale;
        this.rtl = rtl;
    }

    public String getCode() {
        return code;
    }

    /**
     * Код для Telegram Bot API (поле {@code language_code} в {@code setMyCommands}).
     * Telegram требует ISO 639-1 (2 буквы) — регионального суффикса быть не должно,
     * поэтому для {@code pt-BR} возвращаем {@code "pt"}.
     */
    public String getTelegramApiCode() {
        int dash = code.indexOf('-');
        return dash < 0 ? code : code.substring(0, dash);
    }

    public String getDisplayName() {
        return displayName;
    }

    public Locale getLocale() {
        return locale;
    }

    public boolean isRtl() {
        return rtl;
    }

    private static final Map<String, BotLanguage> BY_CODE = Stream.of(values())
            .collect(Collectors.toUnmodifiableMap(
                    l -> l.code.toLowerCase(Locale.ROOT),
                    l -> l));

    /**
     * Резолв по коду. Сравнение case-insensitive ({@code "RU"}, {@code "ru"}, {@code "Ru"} — все валидны).
     * Для {@code PT_BR} принимаются обе формы: {@code "pt-BR"} и {@code "pt_BR"}.
     */
    public static Optional<BotLanguage> fromCode(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        String normalized = code.replace('_', '-').toLowerCase(Locale.ROOT);
        return Optional.ofNullable(BY_CODE.get(normalized));
    }

    /**
     * Резолв по коду с fallback-значением при отсутствии/невалидности.
     * Используется там, где null недопустим (отрисовка UI).
     */
    public static BotLanguage fromCodeOrDefault(String code, BotLanguage fallback) {
        return fromCode(code).orElse(fallback);
    }

    public static List<BotLanguage> allActive() {
        return List.of(values());
    }
}
