package com.tcleaner.dashboard.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

import java.time.format.DateTimeParseException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("DashboardExceptionHandler")
class DashboardExceptionHandlerTest {

    private final DashboardExceptionHandler handler = new DashboardExceptionHandler();

    @Test
    @DisplayName("AccessDeniedException → 403 forbidden")
    void handleAccessDenied() {
        ResponseEntity<Map<String, String>> resp = handler.handleAccessDenied(
                new AccessDeniedException("denied"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(resp.getBody()).containsEntry("error", "forbidden");
        assertThat(resp.getBody()).containsEntry("message", "denied");
    }

    @Test
    @DisplayName("EmptyResultDataAccessException → 404 not_found")
    void handleNotFound() {
        ResponseEntity<Map<String, String>> resp = handler.handleNotFound(
                new EmptyResultDataAccessException("missing", 1));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(resp.getBody()).containsEntry("error", "not_found");
    }

    @Test
    @DisplayName("DateTimeParseException → 400 bad_request")
    void handleBadParamDateTime() {
        DateTimeParseException ex = new DateTimeParseException("bad date", "not-a-date", 0);

        ResponseEntity<Map<String, String>> resp = handler.handleBadParam(ex);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody()).containsEntry("error", "bad_request");
    }

    @Test
    @DisplayName("MethodArgumentTypeMismatchException → 400 bad_request")
    void handleBadParamTypeMismatch() {
        MethodArgumentTypeMismatchException ex = mock(MethodArgumentTypeMismatchException.class);
        when(ex.getMessage()).thenReturn("type mismatch");

        ResponseEntity<Map<String, String>> resp = handler.handleBadParam(ex);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody()).containsEntry("error", "bad_request");
    }

    @Test
    @DisplayName("ObjectOptimisticLockingFailureException → 409 conflict")
    void handleOptimisticLock() {
        ResponseEntity<Map<String, String>> resp = handler.handleOptimisticLock(
                new ObjectOptimisticLockingFailureException(Object.class, "key"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(resp.getBody()).containsEntry("error", "conflict");
    }

    @Test
    @DisplayName("DataIntegrityViolationException → 409 conflict")
    void handleDataIntegrity() {
        ResponseEntity<Map<String, String>> resp = handler.handleDataIntegrity(
                new DataIntegrityViolationException("dup key"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(resp.getBody()).containsEntry("error", "conflict");
    }

    @Test
    @DisplayName("IllegalArgumentException → 400 bad_request с message")
    void handleIllegalArg() {
        ResponseEntity<Map<String, String>> resp = handler.handleIllegalArg(
                new IllegalArgumentException("invalid period"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody()).containsEntry("error", "bad_request");
        assertThat(resp.getBody()).containsEntry("message", "invalid period");
    }

    @Test
    @DisplayName("MethodArgumentNotValidException → 400 validation_failed")
    void handleBodyValidation() {
        MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
        when(ex.getMessage()).thenReturn("validation");

        ResponseEntity<Map<String, String>> resp = handler.handleBodyValidation(ex);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody()).containsEntry("error", "validation_failed");
    }

    @Test
    @DisplayName("ResponseStatusException → пробрасывает status и reason")
    void handleResponseStatus() {
        ResponseStatusException ex = new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED, "pay up");

        ResponseEntity<Map<String, String>> resp = handler.handleResponseStatus(ex);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
        assertThat(resp.getBody()).containsEntry("message", "pay up");
    }

    @Test
    @DisplayName("ResponseStatusException с null reason → message = 'Unknown'")
    void handleResponseStatusWithNullReason() {
        ResponseStatusException ex = new ResponseStatusException(HttpStatus.BAD_GATEWAY);

        ResponseEntity<Map<String, String>> resp = handler.handleResponseStatus(ex);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(resp.getBody()).containsEntry("message", "Unknown");
    }

    @Test
    @DisplayName("любое другое Exception → 500 internal_error")
    void handleGeneric() {
        ResponseEntity<Map<String, String>> resp = handler.handleGeneric(
                new RuntimeException("boom"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(resp.getBody()).containsEntry("error", "internal_error");
    }

    @Test
    @DisplayName("Exception с null message → handleGeneric корректно отрабатывает")
    void handleGenericWithNullMessage() {
        ResponseEntity<Map<String, String>> resp = handler.handleGeneric(new RuntimeException());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(resp.getBody()).containsEntry("error", "internal_error");
    }
}
