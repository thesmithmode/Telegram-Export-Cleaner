package com.tcleaner.api;

import com.tcleaner.core.TelegramExporter;
import com.tcleaner.dashboard.events.StatsEventPayload;
import com.tcleaner.dashboard.events.StatsEventType;
import com.tcleaner.dashboard.events.StatsStreamPublisher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Проверяет, что TelegramController публикует bytes_measured и export.completed
 * в Stats-стрим после успешной стриминг-конвертации.
 * StreamingResponseBody выполняется асинхронно — используем asyncDispatch.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("TelegramController — bytes counting & stats publish")
class TelegramControllerBytesCountTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TelegramExporter mockExporter;

    @MockitoBean
    private StatsStreamPublisher statsPublisher;

    @Test
    @DisplayName("POST /api/convert с taskId публикует bytes_measured и export.completed")
    void publishesBytesAndCompleted() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "result.json", "application/json",
                "{\"messages\":[]}".getBytes());

        // Шаг 1: инициировать запрос, Spring запускает async
        MvcResult asyncResult = mockMvc.perform(multipart("/api/convert")
                        .file(file)
                        .param("taskId", "task-test")
                        .param("botUserId", "42")
                        .param("messagesCount", "100"))
                .andExpect(request().asyncStarted())
                .andReturn();

        // Шаг 2: dispatch — дождаться завершения StreamingResponseBody
        mockMvc.perform(asyncDispatch(asyncResult))
                .andExpect(status().isOk());

        ArgumentCaptor<StatsEventPayload> captor = ArgumentCaptor.forClass(StatsEventPayload.class);
        verify(statsPublisher, atLeast(2)).publish(captor.capture());

        List<StatsEventPayload> published = captor.getAllValues();
        List<StatsEventType> types = published.stream()
                .map(StatsEventPayload::getType).toList();

        assertThat(types).contains(StatsEventType.EXPORT_BYTES_MEASURED, StatsEventType.EXPORT_COMPLETED);

        StatsEventPayload completed = published.stream()
                .filter(p -> p.getType() == StatsEventType.EXPORT_COMPLETED)
                .findFirst().orElseThrow();
        assertThat(completed.getTaskId()).isEqualTo("task-test");
        assertThat(completed.getBotUserId()).isEqualTo(42L);
        assertThat(completed.getMessagesCount()).isEqualTo(100L);
        assertThat(completed.getBytesCount()).isNotNull().isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("POST /api/convert без taskId — publish не вызывается")
    void noPublishWhenNoTaskId() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "result.json", "application/json",
                "{\"messages\":[]}".getBytes());

        MvcResult asyncResult = mockMvc.perform(multipart("/api/convert").file(file))
                .andExpect(request().asyncStarted())
                .andReturn();
        mockMvc.perform(asyncDispatch(asyncResult)).andExpect(status().isOk());

        verify(statsPublisher, org.mockito.Mockito.never()).publish(any());
    }
}
