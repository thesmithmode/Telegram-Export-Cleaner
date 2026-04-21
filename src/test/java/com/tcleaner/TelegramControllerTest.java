package com.tcleaner;

import com.tcleaner.api.ApiExceptionHandler;
import com.tcleaner.api.TelegramController;
import com.tcleaner.core.TelegramExporter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
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
@Import({SecurityConfig.class, ApiExceptionHandler.class, WebConfig.class})
@DisplayName("TelegramController")
class TelegramControllerTest {

    @MockitoBean
    private TelegramExporter mockExporter;

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
                .andExpect(content().string("HELLO FROM ASYNC"));
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
