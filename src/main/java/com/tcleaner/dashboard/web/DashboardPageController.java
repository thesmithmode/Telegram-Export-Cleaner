package com.tcleaner.dashboard.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * HTML-страницы дашборда (Thymeleaf). FormLogin/logout обслуживает Spring Security;
 * здесь — только GET-рендер (login, overview, error) и root-redirect на overview.
 */
@Controller
@RequestMapping("/dashboard")
public class DashboardPageController {

    @GetMapping({"", "/"})
    public String root() {
        return "redirect:/dashboard/overview";
    }

    @GetMapping("/login")
    public String login(@RequestParam(required = false) String error,
                        @RequestParam(required = false) String logout,
                        Model model) {
        if (error != null) {
            model.addAttribute("loginError", true);
        }
        if (logout != null) {
            model.addAttribute("loggedOut", true);
        }
        return "dashboard/login";
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

    /** Карточка одного пользователя. RBAC на данные — в /dashboard/api/stats/user/{id}. */
    @GetMapping("/user/{botUserId}")
    public String userDetail(@PathVariable long botUserId, Model model) {
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
