package com.tcleaner.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("BotLanguage")
class BotLanguageTest {

    @Test
    @DisplayName("getTelegramApiCode для bare-тегов возвращает сам код")
    void telegramApiCodeForBareTags() {
        assertThat(BotLanguage.RU.getTelegramApiCode()).isEqualTo("ru");
        assertThat(BotLanguage.EN.getTelegramApiCode()).isEqualTo("en");
        assertThat(BotLanguage.DE.getTelegramApiCode()).isEqualTo("de");
        assertThat(BotLanguage.ZH.getTelegramApiCode()).isEqualTo("zh");
    }

    @Test
    @DisplayName("getTelegramApiCode для pt-BR возвращает \"pt\" (Telegram требует ISO 639-1)")
    void telegramApiCodeStripsRegionFromPtBr() {
        assertThat(BotLanguage.PT_BR.getTelegramApiCode()).isEqualTo("pt");
    }

    @Test
    @DisplayName("getCode для PT_BR остаётся \"pt-BR\" (ResourceBundle / БД не меняются)")
    void getCodePreservesRegionForPtBr() {
        assertThat(BotLanguage.PT_BR.getCode()).isEqualTo("pt-BR");
    }
}
