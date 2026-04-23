package com.tcleaner.dashboard.web;

import com.tcleaner.dashboard.auth.DashboardUserDetails;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Страница управления подписками {@code /dashboard/subscriptions}.
 * Доступна любому аутентифицированному пользователю (USER + ADMIN).
 * Данные загружаются через JS из {@code /dashboard/api/subscriptions/**}.
 * USER видит форму создания; ADMIN — только таблицу (может просматривать все подписки).
 */
@Controller
@RequestMapping("/dashboard")
public class SubscriptionPageController {

    @GetMapping("/subscriptions")
    public String subscriptions(@AuthenticationPrincipal DashboardUserDetails principal, Model model) {
        model.addAttribute("role", principal.getDashboardRole().name());
        model.addAttribute("userBotId", principal.getBotUserId());
        return "dashboard/subscriptions";
    }
}
