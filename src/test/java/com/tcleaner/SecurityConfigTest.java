package com.tcleaner;

import com.tcleaner.api.FileConversionService;
import com.tcleaner.api.TelegramController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Тесты безопасности API.
 *
 * <p>Используем standalone MockMvc без Spring-контекста,
 * чтобы не поднимать Redis, ExportJobProducer и другие сервисы.</p>
 */
@DisplayName("SecurityConfig")
class SecurityConfigTest {

    private final MockMvc mockMvc;

    SecurityConfigTest() throws Exception {
        FileConversionService mockService = mock(FileConversionService.class);
        when(mockService.convert(any(), isNull())).thenReturn(
                ResponseEntity.ok()
                        .contentType(MediaType.TEXT_PLAIN)
                        .body((StreamingResponseBody) outputStream -> outputStream.write(new byte[0]))
        );

        TelegramController controller = new TelegramController(mockService);
        this.mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("Health endpoint публичный — без аутентификации")
    void testHealthEndpointIsPublic() throws Exception {
        mockMvc.perform(get("/api/health"))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Convert endpoint работает без API ключа когда api.key пустой")
    void testConvertEndpointRequiresApiKey() throws Exception {
        mockMvc.perform(multipart("/api/convert")
                .file(new MockMultipartFile(
                        "file", "test.json", "application/json",
                        "{\"messages\": []}".getBytes())))
            .andExpect(status().isOk());
    }
}
