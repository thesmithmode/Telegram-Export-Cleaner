package com.tcleaner;
import com.tcleaner.status.ProcessingStatus;
import com.tcleaner.status.ProcessingResult;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Тесты для ProcessingResult — фабричные методы и базовые поля.
 */
@DisplayName("ProcessingResult")
class ProcessingResultTest {

    @Test
    @DisplayName("success() создаёт результат со статусом COMPLETED")
    void success_hasCompletedStatus() {
        ProcessingResult result = ProcessingResult.success("file-id-123");

        assertThat(result.getFileId()).isEqualTo("file-id-123");
        assertThat(result.getStatus()).isEqualTo(ProcessingStatus.COMPLETED);
        assertThat(result.getErrorMessage()).isNull();
        assertThat(result.getCompletedAt()).isNotNull();
    }

    @Test
    @DisplayName("error() создаёт результат со статусом FAILED и сообщением об ошибке")
    void error_hasFailedStatusAndMessage() {
        ProcessingResult result = ProcessingResult.error("file-id-456", "Что-то сломалось");

        assertThat(result.getFileId()).isEqualTo("file-id-456");
        assertThat(result.getStatus()).isEqualTo(ProcessingStatus.FAILED);
        assertThat(result.getErrorMessage()).isEqualTo("Что-то сломалось");
        assertThat(result.getCompletedAt()).isNotNull();
    }

    @Test
    @DisplayName("success() — startedAt заполнен при создании")
    void success_hasStartedAt() {
        ProcessingResult result = ProcessingResult.success("id");

        assertThat(result.getStartedAt()).isNotNull();
    }

    @Test
    @DisplayName("error() — startedAt заполнен при создании")
    void error_hasStartedAt() {
        ProcessingResult result = ProcessingResult.error("id", "error");

        assertThat(result.getStartedAt()).isNotNull();
    }

    @Test
    @DisplayName("два разных fileId не смешиваются")
    void twoResults_areIndependent() {
        ProcessingResult ok = ProcessingResult.success("aaa");
        ProcessingResult fail = ProcessingResult.error("bbb", "oops");

        assertThat(ok.getFileId()).isNotEqualTo(fail.getFileId());
        assertThat(ok.getStatus()).isNotEqualTo(fail.getStatus());
    }
}
