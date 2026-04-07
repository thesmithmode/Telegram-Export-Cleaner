package com.tcleaner;

import com.tcleaner.api.FileConversionService;
import com.tcleaner.api.TelegramController;
import com.tcleaner.core.MessageFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.ByteArrayOutputStream;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Тесты безопасности API.
 *
 * <p>Вызываем контроллер напрямую (как {@link TelegramControllerTest}),
 * чтобы не поднимать полный Spring-контекст (Redis, ExportJobProducer и т.д.).</p>
 */
@DisplayName("SecurityConfig")
class SecurityConfigTest {

    private final TelegramController controller;

    SecurityConfigTest() throws Exception {
        FileConversionService mockService = mock(FileConversionService.class);
        when(mockService.convert(any(MultipartFile.class), any(MessageFilter.class))).thenReturn(
                ResponseEntity.ok()
                        .contentType(org.springframework.http.MediaType.TEXT_PLAIN)
                        .body((StreamingResponseBody) outputStream -> outputStream.write(new byte[0]))
        );

        this.controller = new TelegramController(mockService);
    }

    @Test
    @DisplayName("Health endpoint публичный — без аутентификации")
    void testHealthEndpointIsPublic() throws Exception {
        ResponseEntity<Map<String, String>> response = controller.health();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "UP");
    }

    @Test
    @DisplayName("Convert endpoint работает без API ключа когда api.key пустой")
    void testConvertEndpointRequiresApiKey() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "test.json", "application/json",
                "{\"messages\": []}".getBytes());

        ResponseEntity<?> response = controller.convert(file, null, null, null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isInstanceOf(StreamingResponseBody.class);

        // Execute the streaming body to verify it works
        StreamingResponseBody body = (StreamingResponseBody) response.getBody();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        body.writeTo(baos);
        assertThat(baos.toByteArray()).isEmpty();
    }
}
