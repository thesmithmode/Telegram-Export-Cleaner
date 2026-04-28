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
        TelegramMiniAppLoginData data;
        try {
            data = TelegramMiniAppLoginData.parse(initData);
        } catch (IllegalArgumentException e) {
            log.warn("Telegram Mini App login: ошибка парсинга initData: {}", e.getMessage());
            return "redirect:/dashboard/login?error=invalid";
        }

        try {
            verifier.verify(data);
        } catch (TelegramAuthenticationException e) {
            log.warn("Telegram Mini App login rejected: id={} reason={}", data.id(), e.getMessage());
            return "redirect:/dashboard/login?error=invalid";
        }

        // Replay protection: одноразовый nonce по hash, TTL = MAX_AGE окна initData.
        try {
            Boolean isNew = redis.opsForValue().setIfAbsent(
                    NONCE_PREFIX + data.hash(), "1", TelegramMiniAppAuthVerifier.MAX_AGE);
            if (!Boolean.TRUE.equals(isNew)) {
                log.warn("Telegram Mini App login rejected: replay detected, id={}", data.id());
                return "redirect:/dashboard/login?error=invalid";
            }
        } catch (Exception e) {
            log.warn("Redis nonce check failed, skipping replay protection: {}", e.getMessage());
        }

        long id = data.id();
        if (id <= 0) {
            log.warn("Telegram Mini App login rejected: отсутствует user.id в initData");
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
                    log.warn("Mini-App: смена principal в сессии prevBotUserId={} → newTgId={} (cross-user session reuse)",
                            prevBotUserId, id);
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

        log.info("Telegram Mini App login: tgId={} role={} user='{}'", id, role, user.getUsername());
        return role == DashboardRole.ADMIN
                ? "redirect:/dashboard/overview"
                : "redirect:/dashboard/me";
    }

    // Клиентский identity-guard читает это значение JS-ом и сравнивает
    // с Telegram.WebApp.initDataUnsafe.user.id. Mismatch → принудительный
    // re-login через POST /dashboard/login/telegram. Закрывает дыру
    // переиспользования WebView в Telegram attachment menu, когда
    // JSESSIONID старого юзера прилетает в сессию нового.
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
