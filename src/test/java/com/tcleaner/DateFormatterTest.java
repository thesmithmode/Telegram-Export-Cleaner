package com.tcleaner;
import com.tcleaner.format.DateFormatter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DateFormatter")
class DateFormatterTest {

    @Nested
    @DisplayName("parseDate() - РїР°СЂСЃРёРЅРі С‚РѕР»СЊРєРѕ РґР°С‚С‹ (YYYYMMDD)")
    class ParseDateOnly {
        
        @Test
        @DisplayName("РџР°СЂСЃРёС‚ СЃС‚Р°РЅРґР°СЂС‚РЅСѓСЋ ISO РґР°С‚Сѓ")
        void parsesStandardIsoDate() {
            String result = DateFormatter.parseDate("2025-06-24T15:29:46");
            assertThat(result).isEqualTo("20250624");
        }
        
        @Test
        @DisplayName("РџР°СЂСЃРёС‚ РґР°С‚Сѓ СЃ РІРµРґСѓС‰РёРјРё РЅСѓР»СЏРјРё")
        void parsesDateWithLeadingZeros() {
            assertThat(DateFormatter.parseDate("2025-01-05T09:03:07")).isEqualTo("20250105");
        }
        
        @Test
        @DisplayName("РџР°СЂСЃРёС‚ РґР°С‚Сѓ РІ РїРµСЂРІС‹Р№ РґРµРЅСЊ РіРѕРґР°")
        void parsesNewYearDate() {
            assertThat(DateFormatter.parseDate("2025-01-01T00:00:00")).isEqualTo("20250101");
        }
        
        @Test
        @DisplayName("РџР°СЂСЃРёС‚ РґР°С‚Сѓ РІ РїРѕСЃР»РµРґРЅРёР№ РґРµРЅСЊ РіРѕРґР°")
        void parsesEndOfYearDate() {
            assertThat(DateFormatter.parseDate("2025-12-31T23:59:59")).isEqualTo("20251231");
        }
        
        @ParameterizedTest
        @NullAndEmptySource
        @DisplayName("Р’РѕР·РІСЂР°С‰Р°РµС‚ РїСѓСЃС‚СѓСЋ СЃС‚СЂРѕРєСѓ РґР»СЏ null/empty")
        void returnsEmptyForNullOrEmpty(String input) {
            assertThat(DateFormatter.parseDate(input)).isEmpty();
        }
        
        @Test
        @DisplayName("Р’РѕР·РІСЂР°С‰Р°РµС‚ РїСѓСЃС‚СѓСЋ СЃС‚СЂРѕРєСѓ РґР»СЏ РЅРµРІР°Р»РёРґРЅРѕРіРѕ С„РѕСЂРјР°С‚Р°")
        void returnsEmptyForInvalidFormat() {
            assertThat(DateFormatter.parseDate("invalid-date")).isEmpty();
        }

        @Test
        @DisplayName("Р’РѕР·РІСЂР°С‰Р°РµС‚ РїСѓСЃС‚СѓСЋ СЃС‚СЂРѕРєСѓ РґР»СЏ РЅРµРїРѕР»РЅРѕР№ РґР°С‚С‹")
        void returnsEmptyForPartialDate() {
            assertThat(DateFormatter.parseDate("2025-06")).isEmpty();
        }
    }
    
}
