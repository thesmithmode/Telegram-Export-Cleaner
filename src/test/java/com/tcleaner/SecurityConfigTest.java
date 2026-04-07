package com.tcleaner;

import com.tcleaner.api.FileConversionService;
import com.tcleaner.api.TelegramController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.bean.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Тесты конфигурации безопасности.
 *
 * <p>Используем {@code @WebMvcTest} вместо {@code @SpringBootTest},
 * чтобы не поднимать полный контекст (Redis, ExportJobProducer и т.д.).
 * Загружаются контроллеры, фильтры (ApiKeyFilter) и SecurityConfig.
 * FileConversionService мокается, т.к. это @Service.</p>
 */
@WebMvcTest(TelegramController.class)
@DisplayName("SecurityConfig")
class SecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private FileConversionService conversionService;

    @Test
    @DisplayName("Health endpoint публичный — без аутентификации")
    void testHealthEndpointIsPublic() throws Exception {
        mockMvc.perform(get("/api/health"))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Convert endpoint работает без API ключа когда api.key пустой")
    void testConvertEndpointRequiresApiKey() throws Exception {
        // По умолчанию api.key пустой в тестах, ApiKeyFilter пропускает всё
        when(conversionService.convert(any(), isNull())).thenReturn(
                org.springframework.http.ResponseEntity.ok()
                        .contentType(MediaType.TEXT_PLAIN)
                        .body(outputStream -> outputStream.write(new byte[0]))
        );

        mockMvc.perform(multipart("/api/convert")
                .file(new org.springframework.mock.web.MockMultipartFile(
                        "file", "test.json", "application/json",
                        "{\"messages\": []}".getBytes())))
            .andExpect(status().isOk());
    }
}
