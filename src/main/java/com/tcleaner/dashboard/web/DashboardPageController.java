package com.tcleaner.dashboard.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
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

    /** Заглушка для PR-9: реальный overview наполняется в PR-10. */
    @GetMapping("/overview")
    public String overview() {
        return "dashboard/overview";
    }

    @GetMapping("/error")
    public String error() {
        return "dashboard/error";
    }
}
