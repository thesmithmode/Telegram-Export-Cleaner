package com.tcleaner.dashboard.auth.telegram;

import com.tcleaner.dashboard.auth.DashboardUserDetails;
import com.tcleaner.dashboard.auth.DashboardUserService;
import com.tcleaner.dashboard.domain.DashboardRole;
import com.tcleaner.dashboard.domain.DashboardUser;
import com.tcleaner.dashboard.service.ingestion.BotUserUpserter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Instant;
import java.util.List;

@Controller
@RequestMapping("/dashboard/login/telegram")
public class TelegramAuthController {

    private static final Logger log = LoggerFactory.getLogger(TelegramAuthController.class);

    private final TelegramMiniAppAuthVerifier verifier;
    private final DashboardUserService userService;
    private final BotUserUpserter botUserUpserter;
    private final long adminTelegramId;
    private final SecurityContextRepository contextRepository =
            new HttpSessionSecurityContextRepository();

    public TelegramAuthController(TelegramMiniAppAuthVerifier verifier,
                                  DashboardUserService userService,
                                  BotUserUpserter botUserUpserter,
                                  @Value("${dashboard.auth.admin.telegram-id}") long adminTelegramId) {
        if (adminTelegramId <= 0) {
            throw new IllegalArgumentException(
                    "DASHBOARD_ADMIN_TG_ID не настроен (=" + adminTelegramId + ") — запуск невозможен");
        }
        this.verifier = verifier;
        this.userService = userService;
        this.botUserUpserter = botUserUpserter;
        this.adminTelegramId = adminTelegramId;
    }

    @PostMapping
    @Transactional
    public String callback(@RequestParam("initData") String initData,
                           HttpServletRequest request,
                           HttpServletResponse response) {
        TelegramMiniAppLoginData data;
        try {
            data = TelegramMiniAppLoginData.parse(initData);
        } catch (Exception e) {
            log.warn("Telegram Mini App login: ошибка парсинга initData: {}", e.getMessage());
            return "redirect:/dashboard/login?error=invalid";
        }

        try {
            verifier.verify(data);
        } catch (TelegramAuthenticationException e) {
            log.warn("Telegram Mini App login rejected: id={} reason={}", data.id(), e.getMessage());
            return "redirect:/dashboard/login?error=invalid";
        }

        long id = data.id();
        if (id <= 0) {
            log.warn("Telegram Mini App login rejected: отсутствует user.id в initData");
            return "redirect:/dashboard/login?error=invalid";
        }
        String firstName = data.firstName();
        String username = data.username();

        DashboardRole role;
        Long botUserId;
        if (id == adminTelegramId) {
            role = DashboardRole.ADMIN;
            botUserId = null;
        } else {
            botUserUpserter.upsert(id, username, firstName, Instant.ofEpochSecond(data.authDate()));
            role = DashboardRole.USER;
            botUserId = id;
        }

        DashboardUser user = userService.findOrCreate(id, firstName, username, role, botUserId);
        DashboardUserDetails principal = new DashboardUserDetails(
                user.getUsername(), "",
                List.of(new SimpleGrantedAuthority(role.authority())),
                role, user.getBotUserId());

        HttpSession oldSession = request.getSession(false);
        if (oldSession != null) {
            oldSession.invalidate();
        }
        request.getSession(true);

        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);

        log.info("Telegram Mini App login: tgId={} role={} user='{}'", id, role, user.getUsername());
        return role == DashboardRole.ADMIN
                ? "redirect:/dashboard/overview"
                : "redirect:/dashboard/me";
    }
}
