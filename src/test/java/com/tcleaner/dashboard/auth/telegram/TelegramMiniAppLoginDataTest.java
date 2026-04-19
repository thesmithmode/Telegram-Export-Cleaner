package com.tcleaner.dashboard.auth.telegram;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Регрессионные тесты на парсинг initData реального Telegram Mini App формата:
 * {@code user} приходит как URL-encoded JSON, а не top-level параметр.
 * До фикса все пользователи логинились как {@code tg_0} — id искался на top-level.
 */
@DisplayName("TelegramMiniAppLoginData parse")
class TelegramMiniAppLoginDataTest {

    @Test
    @DisplayName("реальный Mini App initData: id/first_name/username извлекаются из user JSON")
    void parsesUserFieldsFromJson() {
        String initData = "auth_date=1700000000"
                + "&user=%7B%22id%22%3A123456789%2C%22first_name%22%3A%22John%22"
                + "%2C%22last_name%22%3A%22Doe%22%2C%22username%22%3A%22johndoe%22%7D"
                + "&hash=abcd";

        TelegramMiniAppLoginData data = TelegramMiniAppLoginData.parse(initData);

        assertThat(data.id()).isEqualTo(123456789L);
        assertThat(data.firstName()).isEqualTo("John");
        assertThat(data.lastName()).isEqualTo("Doe");
        assertThat(data.username()).isEqualTo("johndoe");
        assertThat(data.authDate()).isEqualTo(1700000000L);
        assertThat(data.hash()).isEqualTo("abcd");
    }

    @Test
    @DisplayName("top-level id игнорируется — только user JSON является источником identity")
    void topLevelIdIgnored() {
        String initData = "auth_date=1700000000&id=999&hash=abcd";

        TelegramMiniAppLoginData data = TelegramMiniAppLoginData.parse(initData);

        assertThat(data.id()).isZero();
        assertThat(data.firstName()).isNull();
        assertThat(data.username()).isNull();
    }

    @Test
    @DisplayName("user=невалидный JSON → id=0, никаких исключений")
    void invalidUserJsonYieldsZeroId() {
        String initData = "auth_date=1700000000&user=not-json&hash=abcd";

        TelegramMiniAppLoginData data = TelegramMiniAppLoginData.parse(initData);

        assertThat(data.id()).isZero();
        assertThat(data.firstName()).isNull();
    }

    @Test
    @DisplayName("отсутствие user → id=0")
    void missingUserYieldsZeroId() {
        String initData = "auth_date=1700000000&hash=abcd";

        TelegramMiniAppLoginData data = TelegramMiniAppLoginData.parse(initData);

        assertThat(data.id()).isZero();
    }

    @Test
    @DisplayName("toDataCheckString не включает hash, сохраняет user=JSON как есть")
    void dataCheckStringPreservesUserJson() {
        String initData = "auth_date=1700000000"
                + "&user=%7B%22id%22%3A111%7D"
                + "&hash=abcd";

        String dcs = TelegramMiniAppLoginData.parse(initData).toDataCheckString();

        assertThat(dcs).isEqualTo("auth_date=1700000000\nuser={\"id\":111}");
    }
}
