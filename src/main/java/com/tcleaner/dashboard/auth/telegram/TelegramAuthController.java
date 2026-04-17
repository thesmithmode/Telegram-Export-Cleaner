package com.tcleaner.dashboard.auth.telegram;

import com.tcleaner.dashboard.auth.DashboardUserDetails;
import com.tcleaner.dashboard.auth.DashboardUserService;
import com.tcleaner.dashboard.domain.DashboardRole;
import com.tcleaner.dashboard.domain.DashboardUser;
import com.tcleaner.dashboard.repository.BotUserRepository;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * Обрабатывает callback от Telegram Login Widget:
 * GET /dashboard/login/telegram?id=...&first_name=...&hash=...
 * Верифицирует HMAC-SHA256, определяет роль, создаёт Spring Security сессию.
 */
@Controller
@RequestMapping("/dashboard/login/telegram")
public class TelegramAuthController {

    private static final Logger log = LoggerFactory.getLogger(TelegramAuthController.class);

    private final TelegramAuthVerifier verifier;
    private final DashboardUserService userService;
    private final BotUserRepository botUsers;
    private final long adminTelegramId;
    private final SecurityContextRepository contextRepository =
            new HttpSessionSecurityContextRepository();

    public TelegramAuthController(TelegramAuthVerifier verifier,
                                  DashboardUserService userService,
                                  BotUserRepository botUsers,
                                  @Value("${dashboard.auth.admin.telegram-id}") long adminTelegramId) {
        if (adminTelegramId <= 0) {
            throw new IllegalArgumentException(
                    "DASHBOARD_ADMIN_TG_ID не настроен (=" + adminTelegramId + ") — запуск невозможен");
        }
        this.verifier = verifier;
        this.userService = userService;
        this.botUsers = botUsers;
        this.adminTelegramId = adminTelegramId;
    }

    @GetMapping
    @Transactional
    public String callback(@RequestParam("id") long id,
                           @RequestParam(value = "first_name", required = false) String firstName,
                           @RequestParam(value = "last_name", required = false) String lastName,
                           @RequestParam(value = "username", required = false) String username,
                           @RequestParam(value = "photo_url", required = false) String photoUrl,
                           @RequestParam("auth_date") long authDate,
                           @RequestParam("hash") String hash,
                           HttpServletRequest request,
                           HttpServletResponse response) {
        TelegramLoginData data = new TelegramLoginData(
                id, firstName, lastName, username, photoUrl, authDate, hash);
        try {
            verifier.verify(data);
        } catch (TelegramAuthenticationException e) {
            log.warn("Telegram login rejected (invalid): id={} reason={}", id, e.getMessage());
            return "redirect:/dashboard/login?error=invalid";
        }

        DashboardRole role;
        Long botUserId;
        if (id == adminTelegramId) {
            role = DashboardRole.ADMIN;
            botUserId = null;
        } else {
            if (botUsers.findById(id).isEmpty()) {
                log.warn("Telegram login rejected (forbidden): id={} не найден в bot_users", id);
                return "redirect:/dashboard/login?error=forbidden";
            }
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

        log.info("Telegram login: tgId={} role={} user='{}'", id, role, user.getUsername());
        return "redirect:/dashboard/overview";
    }
}
