package com.tcleaner.dashboard.web;

import com.tcleaner.dashboard.auth.DashboardUserDetails;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Страница личного кабинета {@code /dashboard/me}. Доступна любому
 * авторизованному; данные тянет с {@code /dashboard/api/me/**} через JS.
 * Если у principal нет {@code botUserId} — шаблон покажет empty-state вместо KPI.
 */
@Controller
@RequestMapping("/dashboard")
public class DashboardMePageController {

    @GetMapping("/me")
    public String me(@AuthenticationPrincipal DashboardUserDetails principal, Model model) {
        model.addAttribute("displayName", principal.getUsername());
        model.addAttribute("hasBotUserId", principal.getBotUserId() != null);
        return "dashboard/me";
    }
}
