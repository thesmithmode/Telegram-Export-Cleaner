package com.tcleaner.dashboard.web;

import com.tcleaner.dashboard.auth.DashboardUserDetails;
import com.tcleaner.dashboard.domain.DashboardRole;
import com.tcleaner.dashboard.security.BotUserAccessPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * HTML-страницы дашборда (Thymeleaf). Logout обслуживает Spring Security;
 * здесь — GET-рендер страниц и root-redirect на overview.
 */
@Controller
@RequestMapping("/dashboard")
public class DashboardPageController {

    @Value("${telegram.bot.username:}")
    private String botUsername;

    private final BotUserAccessPolicy accessPolicy;

    public DashboardPageController(BotUserAccessPolicy accessPolicy) {
        this.accessPolicy = accessPolicy;
    }

    @GetMapping({"", "/"})
    public String root(@AuthenticationPrincipal DashboardUserDetails principal) {
        if (principal == null) {
            return "redirect:/dashboard/login";
        }
        return landingFor(principal);
    }

    @GetMapping("/login")
    public String login(@AuthenticationPrincipal DashboardUserDetails principal, Model model) {
        if (principal != null) {
            return landingFor(principal);
        }
        model.addAttribute("botUsername", botUsername);
        return "dashboard/login";
    }

    /** ADMIN → /overview, любой другой авторизованный → /me. */
    private String landingFor(DashboardUserDetails principal) {
        return principal.getDashboardRole() == DashboardRole.ADMIN
                ? "redirect:/dashboard/overview"
                : "redirect:/dashboard/me";
    }

    @GetMapping("/overview")
    public String overview() {
        return "dashboard/overview";
    }

    /** Таблица всех пользователей — только ADMIN (URL-guard в DashboardSecurityConfig). */
    @GetMapping("/users")
    public String users() {
        return "dashboard/users";
    }

    @GetMapping("/user/{botUserId}")
    public String userDetail(@PathVariable long botUserId,
                             @AuthenticationPrincipal DashboardUserDetails principal,
                             Model model) {
        if (!accessPolicy.canSeeUser(principal.getDashboardRole(), principal.getBotUserId(), botUserId)) {
            throw new AccessDeniedException("Доступ запрещён");
        }
        model.addAttribute("botUserId", botUserId);
        return "dashboard/user-detail";
    }

    @GetMapping("/chats")
    public String chats() {
        return "dashboard/chats";
    }

    @GetMapping("/events")
    public String events() {
        return "dashboard/events";
    }

    @GetMapping("/error")
    public String error() {
        return "dashboard/error";
    }
}
