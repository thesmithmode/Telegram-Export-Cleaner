package com.tcleaner.dashboard.web;

import com.tcleaner.core.BotLanguage;
import com.tcleaner.dashboard.auth.DashboardUserDetails;
import com.tcleaner.dashboard.dto.LanguageUpdateRequest;
import com.tcleaner.dashboard.service.ingestion.BotUserUpserter;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;

/**
 * Личные настройки пользователя. Сейчас только язык UI; дальше может расти
 * (тема, уведомления и т.п.) — поэтому в отдельном контроллере.
 */
@RestController
@RequestMapping("/dashboard/api/me/settings")
public class DashboardMeSettingsController {

    private static final Logger log = LoggerFactory.getLogger(DashboardMeSettingsController.class);

    private final BotUserUpserter userUpserter;

    public DashboardMeSettingsController(BotUserUpserter userUpserter) {
        this.userUpserter = userUpserter;
    }

    @PostMapping("/language")
    public ResponseEntity<Void> updateLanguage(
            @AuthenticationPrincipal DashboardUserDetails principal,
            @Valid @RequestBody LanguageUpdateRequest request) {
        if (principal == null || principal.getBotUserId() == null) {
            throw new ResponseStatusException(UNAUTHORIZED, "No bot user binding");
        }
        BotLanguage lang = BotLanguage.fromCode(request.language())
                .orElseThrow(() -> new ResponseStatusException(
                        BAD_REQUEST, "Unsupported language code: " + request.language()));

        userUpserter.setLanguage(principal.getBotUserId(), lang.getCode());
        log.info("Dashboard: смена языка через API — botUserId={} code={}",
                principal.getBotUserId(), lang.getCode());
        return ResponseEntity.noContent().build();
    }
}
