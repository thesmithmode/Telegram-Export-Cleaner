package com.tcleaner.dashboard.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/dashboard/mini-app")
public class MiniAppController {

    @GetMapping
    public String miniApp() {
        return "mini-app";
    }
}
