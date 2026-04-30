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
import org.springframework.data.redis.core.StringRedisTemplate;
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

    private static final String NONCE_PREFIX = "tg:nonce:";

    private final TelegramMiniAppAuthVerifier verifier;
    private final TelegramLoginService loginService;
    private final long adminTelegramId;
    private final SecurityContextRepository contextRepository;
    private final StringRedisTemplate redis;

    public TelegramAuthController(TelegramMiniAppAuthVerifier verifier,
                                  TelegramLoginService loginService,
                                  SecurityContextRepository contextRepository,
                                  StringRedisTemplate redis,
                                  @Value("${dashboard.auth.admin.telegram-id}") long adminTelegramId) {
        if (adminTelegramId <= 0) {
            throw new IllegalArgumentException(
                    "DASHBOARD_ADMIN_TG_ID не настроен (=" + adminTelegramId + ") — запуск невозможен");
        }
        this.verifier = verifier;
        this.loginService = loginService;
        this.contextRepository = contextRepository;
        this.redis = redis;
        this.adminTelegramId = adminTelegramId;
    }

    @PostMapping
    public String callback(@RequestParam("initData") String initData,
                           HttpServletRequest request,
                           HttpServletResponse response) {
        String ip = clientIp(request);
        TelegramMiniAppLoginData data;
        try {
            data = TelegramMiniAppLoginData.parse(initData);
        } catch (IllegalArgumentException e) {
            log.warn("Telegram Mini App login: ошибка парсинга initData ip={}: {}", ip, e.getMessage());
            return "redirect:/dashboard/login?error=invalid";
        }

        try {
            verifier.verify(data);
        } catch (TelegramAuthenticationException e) {
            log.warn("Telegram Mini App login rejected: id={} ip={} reason={}", data.id(), ip, e.getMessage());
            return "redirect:/dashboard/login?error=invalid";
        }

        // Replay protection: одноразовый nonce по hash, TTL = MAX_AGE окна initData.
        // Fail-closed: при недоступности Redis отказываем в логине, иначе replay-окно
        // открыто на всё время outage (initData можно использовать повторно до auth_date+MAX_AGE).
        try {
            Boolean isNew = redis.opsForValue().setIfAbsent(
                    NONCE_PREFIX + data.hash(), "1", TelegramMiniAppAuthVerifier.MAX_AGE);
            if (!Boolean.TRUE.equals(isNew)) {
                log.warn("Telegram Mini App login rejected: replay detected, id={} ip={}", data.id(), ip);
                return "redirect:/dashboard/login?error=invalid";
            }
        } catch (Exception e) {
            log.error("Redis nonce check failed ip={}: {}", ip, e.getMessage(), e);
            return "redirect:/dashboard/login?error=infra";
        }

        long id = data.id();
        if (id <= 0) {
            log.warn("Telegram Mini App login rejected: отсутствует user.id в initData ip={}", ip);
            return "redirect:/dashboard/login?error=invalid";
        }

        TelegramLoginService.LoginResult result = loginService.loginOrCreate(data, adminTelegramId);
        DashboardUser user = result.user();
        DashboardRole role = result.role();
        DashboardUserDetails principal = new DashboardUserDetails(
                user.getUsername(), "",
                List.of(new SimpleGrantedAuthority(role.authority())),
                role, user.getBotUserId());

        HttpSession oldSession = request.getSession(false);
        if (oldSession != null) {
            Object oldCtx = oldSession.getAttribute(
                    HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
            if (oldCtx instanceof SecurityContext sc
                    && sc.getAuthentication() != null
                    && sc.getAuthentication().getPrincipal() instanceof DashboardUserDetails prev) {
                Long prevBotUserId = prev.getBotUserId();
                if (prevBotUserId != null && !prevBotUserId.equals(id)) {
                    log.warn("Mini-App: смена principal в сессии prevBotUserId={} → newTgId={} ip={} (cross-user session reuse)",
                            prevBotUserId, id, ip);
                }
            }
            oldSession.invalidate();
        }
        request.getSession(true);

        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);

        writeTgUidCookie(response, id, request.isSecure());

        log.info("Telegram Mini App login: tgId={} role={} user='{}' ip={}", id, role, user.getUsername(), ip);
        return role == DashboardRole.ADMIN
                ? "redirect:/dashboard/overview"
                : "redirect:/dashboard/me";
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
