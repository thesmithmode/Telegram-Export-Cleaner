package com.tcleaner.dashboard.web;

import com.tcleaner.dashboard.auth.DashboardUserDetails;
import com.tcleaner.dashboard.dto.FeedbackRequest;
import com.tcleaner.dashboard.service.FeedbackService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;
import static org.springframework.http.HttpStatus.TOO_MANY_REQUESTS;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;

/**
 * Обратная связь со вкладки "О проекте": POST /dashboard/api/me/feedback.
 * Сообщение через бот уходит админу (из {@code dashboard.auth.admin.telegram-id}).
 */
@RestController
@RequestMapping("/dashboard/api/me/feedback")
public class FeedbackController {

    private final FeedbackService feedback;

    public FeedbackController(FeedbackService feedback) {
        this.feedback = feedback;
    }

    @PostMapping
    public ResponseEntity<Void> submit(@AuthenticationPrincipal DashboardUserDetails principal,
                                       @Valid @RequestBody FeedbackRequest request) {
        if (principal == null || principal.getBotUserId() == null) {
            throw new ResponseStatusException(UNAUTHORIZED, "No bot user binding");
        }

        FeedbackService.Result r = feedback.submit(principal.getBotUserId(), request.message());

        return switch (r) {
            case SENT -> ResponseEntity.noContent().build();
            case RATE_LIMITED -> throw new ResponseStatusException(TOO_MANY_REQUESTS, "Feedback rate limit");
            case SEND_FAILED  -> throw new ResponseStatusException(SERVICE_UNAVAILABLE, "Telegram API unavailable");
            case FORBIDDEN    -> throw new ResponseStatusException(FORBIDDEN, "Admin cannot send feedback to self");
        };
    }
}
