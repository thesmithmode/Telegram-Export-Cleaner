package com.tcleaner.dashboard.auth.telegram;

import com.tcleaner.dashboard.auth.DashboardUserDetails;
import com.tcleaner.dashboard.domain.DashboardRole;
import com.tcleaner.dashboard.domain.DashboardUser;
import jakarta.servlet.http.Cookie;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@Controller
@RequestMapping("/dashboard/login/telegram")
public class TelegramAuthController {

    private static final Logger log = LoggerFactory.getLogger(TelegramAuthController.class);

    private final TelegramAuthService authService;
    private final long adminTelegramId;
    private final SecurityContextRepository contextRepository;

    public TelegramAuthController(TelegramAuthService authService,
                                  SecurityContextRepository contextRepository,
                                  @Value("${dashboard.auth.admin.telegram-id}") long adminTelegramId) {
        if (adminTelegramId <= 0) {
            throw new IllegalArgumentException(
                    "DASHBOARD_ADMIN_TG_ID не настроен (=" + adminTelegramId + ") — запуск невозможен");
        }
        this.authService = authService;
        this.contextRepository = contextRepository;
        this.adminTelegramId = adminTelegramId;
    }

    @PostMapping
    public String callback(@RequestParam("initData") String initData,
                           HttpServletRequest request,
                           HttpServletResponse response) {
        String ip = clientIp(request);
        TelegramAuthService.LoginOutcome outcome = authService.login(initData, ip, adminTelegramId);
        if (outcome.isFailure()) {
            return "redirect:/dashboard/login?error=" + outcome.errorCode();
        }
        return finalizeLogin(outcome.loginResult(), outcome.tgUserId(), request, response, ip);
    }

    private String finalizeLogin(TelegramLoginService.LoginResult result,
                                  long tgUserId,
                                  HttpServletRequest request,
                                  HttpServletResponse response,
                                  String ip) {
        DashboardUser user = result.user();
        DashboardRole role = result.role();
        // tgUserId — из исходного initData (DashboardUser.botUserId nullable для ADMIN).
        Long principalBotUserId = user.getBotUserId();
        DashboardUserDetails principal = new DashboardUserDetails(
                user.getUsername(), "",
                List.of(new SimpleGrantedAuthority(role.authority())),
                role, principalBotUserId);

        rotateSession(request, tgUserId, ip);

        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);

        writeTgUidCookie(response, tgUserId, request.isSecure());

        log.info("Telegram Mini App login: tgId={} role={} user='{}' ip={}",
                tgUserId, role, user.getUsername(), ip);
        return role == DashboardRole.ADMIN
                ? "redirect:/dashboard/overview"
                : "redirect:/dashboard/me";
    }

    /**
     * Session fixation defense: invalidate старую сессию и создать новую.
     * Дополнительно: если предыдущий principal был другим юзером — диагностический
     * warn для отслеживания WebView session reuse (старый JSESSIONID в attachment menu
     * для нового юзера).
     */
    private static void rotateSession(HttpServletRequest request, long newTgId, String ip) {
        HttpSession oldSession = request.getSession(false);
        if (oldSession != null) {
            Object oldCtx = oldSession.getAttribute(
                    HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
            if (oldCtx instanceof SecurityContext sc
                    && sc.getAuthentication() != null
                    && sc.getAuthentication().getPrincipal() instanceof DashboardUserDetails prev) {
                Long prevBotUserId = prev.getBotUserId();
                if (prevBotUserId != null && !prevBotUserId.equals(newTgId)) {
                    log.warn(
                            "Mini-App: смена principal в сессии prevBotUserId={} → newTgId={} ip={} (cross-user session reuse)",
                            prevBotUserId, newTgId, ip);
                }
            }
            oldSession.invalidate();
        }
        request.getSession(true);
    }

    /**
     * server.forward-headers-strategy=NATIVE включает Tomcat RemoteIpValve —
     * она парсит X-Forwarded-For, стрипает internal-proxy hops и кладёт реальный
     * IP клиента в req.getRemoteAddr(). Сырой XFF подделывается клиентом,
     * RemoteIpValve обработанный — нет.
     */
    private static String clientIp(HttpServletRequest req) {
        return req.getRemoteAddr();
    }

    /**
     * Cookie tg_uid — намеренный набор атрибутов:
     * <ul>
     *   <li><b>HttpOnly=false</b>: client-side JS (tg-identity-guard.js) читает значение
     *       и сравнивает с {@code Telegram.WebApp.initDataUnsafe.user.id}.
     *       Mismatch → форс re-login. Закрывает переиспользование WebView Telegram
     *       attachment menu (JSESSIONID старого user'а попадает в сессию нового).</li>
     *   <li><b>SameSite=Lax</b>: нужно для отправки cookie при top-level navigation
     *       из Telegram iframe. Strict ломает first-load. Безопасность держится
     *       на том, что все state-changing endpoints — POST + CSRF (см. DashboardSecurityConfig).
     *       Если введёте GET, изменяющий состояние — Lax недостаточно.</li>
     *   <li><b>Path=/dashboard</b>: cookie не нужен на /api/**.</li>
     *   <li><b>Secure</b>: HTTPS-only в проде (за Traefik request.isSecure()=true).</li>
     * </ul>
     */
    private static void writeTgUidCookie(HttpServletResponse response, long tgUserId, boolean secure) {
        Cookie cookie = new Cookie("tg_uid", Long.toString(tgUserId));
        cookie.setPath("/dashboard");
        cookie.setHttpOnly(false);
        cookie.setSecure(secure);
        cookie.setMaxAge(-1);
        cookie.setAttribute("SameSite", "Lax");
        response.addCookie(cookie);
    }
}
