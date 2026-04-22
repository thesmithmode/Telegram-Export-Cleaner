package com.tcleaner.dashboard.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Страница "О проекте" — /dashboard/about. Форма обратной связи + раздел
 * поддержки (TON-кошелёк из {@code dashboard.donate.ton}). Доступна любому
 * аутентифицированному; ADMIN-пользователя в меню не показываем (см. header.html),
 * а серверная валидация в {@link FeedbackController} блокирует POST от ADMIN.
 */
@Controller
@RequestMapping("/dashboard")
public class DashboardAboutPageController {

    private final String donateTon;

    public DashboardAboutPageController(@Value("${dashboard.donate.ton:}") String donateTon) {
        this.donateTon = donateTon;
    }

    @GetMapping("/about")
    public String about(Model model) {
        model.addAttribute("donateTon", donateTon == null ? "" : donateTon.trim());
        return "dashboard/about";
    }
}
