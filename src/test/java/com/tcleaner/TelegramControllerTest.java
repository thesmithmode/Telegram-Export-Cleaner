package com.tcleaner;

import com.tcleaner.api.ApiExceptionHandler;
import com.tcleaner.api.TelegramController;
import com.tcleaner.core.TelegramExporter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.Writer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(TelegramController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({SecurityConfig.class, ApiExceptionHandler.class, WebConfig.class,
         TelegramControllerTest.MeterRegistryTestConfig.class})
@DisplayName("TelegramController")
class TelegramControllerTest {

    @TestConfiguration
    static class MeterRegistryTestConfig {
        // @WebMvcTest не подгружает Actuator autoconfig → MeterRegistry bean
        // отсутствует. SimpleMeterRegistry — in-memory replacement без зависимостей.
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    @MockitoBean
    private TelegramExporter mockExporter;

    // IdempotencyKeyFilter требует StringRedisTemplate; в @WebMvcTest auto-config Redis
    // не поднимается, поэтому подставляем мок.
    @MockitoBean
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("Успешный асинхронный стриминг")
    void testSuccessfulStreaming() throws Exception {
        doAnswer(inv -> {
            Writer w = inv.getArgument(2);
            w.write("HELLO FROM ASYNC");
            return 1;
        }).when(mockExporter).processFileStreaming(any(), any(), any());

        MockMultipartFile file = new MockMultipartFile("file", "test.json", "application/json", "{}".getBytes());

        // Выполняем запрос. ОЖИДАЕМ, что асинхронный процесс начнется.
        MvcResult mvcResult = mockMvc.perform(multipart("/api/convert").file(file))
                .andExpect(request().asyncStarted())
                .andReturn();

        // Дожидаемся завершения и проверяем результат
        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                // T23: успешный response теперь заканчивается sentinel "\n##OK##".
                // Python java_client отрезает sentinel перед использованием контента.
                .andExpect(content().string("HELLO FROM ASYNC\n##OK##"));
    }

    @Test
    @DisplayName("Пустой файл возвращает 400")
    void testEmptyFile() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "test.json", "application/json", new byte[0]);
        mockMvc.perform(multipart("/api/convert").file(file))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Health check работает")
    void testHealth() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
