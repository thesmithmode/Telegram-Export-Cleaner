package com.tcleaner.dashboard.config;

import com.tcleaner.dashboard.domain.DashboardRole;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * USER, попавший на admin-URL, тихо уезжает на /dashboard/me — не показываем
 * 403-страницу, чтобы не палить существование admin-раздела. Для ADMIN/anonymous — 403.
 */
@Component
public class DashboardAccessDeniedHandler implements AccessDeniedHandler {

    @Override
    public void handle(HttpServletRequest req, HttpServletResponse res,
                       AccessDeniedException ex) throws IOException {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        boolean isUser = a != null
                && a.isAuthenticated()
                && a.getAuthorities().contains(
                        new SimpleGrantedAuthority(DashboardRole.USER.authority()));
        boolean isApiRequest = req.getRequestURI().startsWith(req.getContextPath() + "/dashboard/api/");
        if (isUser && !isApiRequest) {
            res.sendRedirect(req.getContextPath() + "/dashboard/me");
        } else {
            res.sendError(HttpServletResponse.SC_FORBIDDEN);
        }
    }
}
