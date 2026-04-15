package com.tcleaner.dashboard.web;

import com.tcleaner.dashboard.auth.DashboardUserDetails;
import com.tcleaner.dashboard.dto.ChatStatsRow;
import com.tcleaner.dashboard.dto.EventRowDto;
import com.tcleaner.dashboard.dto.MeDto;
import com.tcleaner.dashboard.dto.OverviewDto;
import com.tcleaner.dashboard.dto.TimeSeriesPointDto;
import com.tcleaner.dashboard.dto.UserDetailDto;
import com.tcleaner.dashboard.dto.UserStatsRow;
import com.tcleaner.dashboard.security.BotUserAccessPolicy;
import com.tcleaner.dashboard.service.stats.PeriodResolver;
import com.tcleaner.dashboard.service.stats.StatsPeriod;
import com.tcleaner.dashboard.service.stats.StatsQueryService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * JSON API дашборда: {@code /dashboard/api/me} + {@code /dashboard/api/stats/**}.
 * RBAC централизован через {@link BotUserAccessPolicy} — ADMIN видит всех,
 * USER — только свой {@code botUserId}. Требует аутентификации (контроль — в
 * {@code DashboardSecurityConfig}); {@code /stats/users} дополнительно ADMIN-only.
 */
@RestController
@RequestMapping("/dashboard/api")
public class DashboardApiController {

    private final StatsQueryService statsQueryService;
    private final PeriodResolver periodResolver;
    private final BotUserAccessPolicy accessPolicy;

    public DashboardApiController(StatsQueryService statsQueryService,
                                  PeriodResolver periodResolver,
                                  BotUserAccessPolicy accessPolicy) {
        this.statsQueryService = statsQueryService;
        this.periodResolver = periodResolver;
        this.accessPolicy = accessPolicy;
    }

    // ─── /dashboard/api/me ───────────────────────────────────────────────────

    @GetMapping("/me")
    public MeDto me(@AuthenticationPrincipal DashboardUserDetails principal) {
        return new MeDto(
                principal.getUsername(),
                principal.getDashboardRole(),
                principal.getBotUserId());
    }

    // ─── /dashboard/api/stats/overview ───────────────────────────────────────

    @GetMapping("/stats/overview")
    public OverviewDto overview(
            @AuthenticationPrincipal DashboardUserDetails principal,
            @RequestParam(required = false) String period,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long userId) {
        StatsPeriod resolved = periodResolver.resolve(period, from, to);
        long effective = accessPolicy.effectiveUserId(
                principal.getDashboardRole(), principal.getBotUserId(), userId);
        return statsQueryService.overview(resolved, effective > 0 ? effective : null);
    }

    // ─── /dashboard/api/stats/users (ADMIN only, URL-guard в security config) ─

    @GetMapping("/stats/users")
    public List<UserStatsRow> users(@RequestParam(defaultValue = "50") int limit) {
        return statsQueryService.topUsers(clamp(limit, 500), null);
    }

    // ─── /dashboard/api/stats/user/{botUserId} ───────────────────────────────

    @GetMapping("/stats/user/{botUserId}")
    public UserDetailDto userDetail(
            @AuthenticationPrincipal DashboardUserDetails principal,
            @PathVariable long botUserId) {
        if (!accessPolicy.canSeeUser(
                principal.getDashboardRole(), principal.getBotUserId(), botUserId)) {
            throw new AccessDeniedException(
                    "Доступ запрещён: нельзя просматривать данные другого пользователя");
        }
        return statsQueryService.userDetail(botUserId);
    }

    // ─── /dashboard/api/stats/chats ──────────────────────────────────────────

    @GetMapping("/stats/chats")
    public List<ChatStatsRow> chats(
            @AuthenticationPrincipal DashboardUserDetails principal,
            @RequestParam(required = false) String period,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long userId,
            @RequestParam(defaultValue = "20") int limit) {
        StatsPeriod resolved = periodResolver.resolve(period, from, to);
        long effective = accessPolicy.effectiveUserId(
                principal.getDashboardRole(), principal.getBotUserId(), userId);
        return statsQueryService.topChats(resolved,
                effective > 0 ? effective : null, clamp(limit, 200));
    }

    // ─── /dashboard/api/stats/timeseries ─────────────────────────────────────

    @GetMapping("/stats/timeseries")
    public List<TimeSeriesPointDto> timeSeries(
            @AuthenticationPrincipal DashboardUserDetails principal,
            @RequestParam(required = false) String period,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false, defaultValue = "exports") String metric,
            @RequestParam(required = false) String granularity) {
        StatsPeriod base = periodResolver.resolve(period, from, to);
        StatsPeriod resolved = overrideGranularity(base, granularity);
        long effective = accessPolicy.effectiveUserId(
                principal.getDashboardRole(), principal.getBotUserId(), userId);
        return statsQueryService.timeSeries(resolved, metric,
                effective > 0 ? effective : null);
    }

    // ─── /dashboard/api/stats/status-breakdown ───────────────────────────────

    @GetMapping("/stats/status-breakdown")
    public Map<String, Long> statusBreakdown(
            @AuthenticationPrincipal DashboardUserDetails principal,
            @RequestParam(required = false) String period,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long userId) {
        StatsPeriod resolved = periodResolver.resolve(period, from, to);
        long effective = accessPolicy.effectiveUserId(
                principal.getDashboardRole(), principal.getBotUserId(), userId);
        return statsQueryService.statusBreakdown(resolved,
                effective > 0 ? effective : null);
    }

    // ─── /dashboard/api/stats/events ─────────────────────────────────────────

    @GetMapping("/stats/events")
    public List<EventRowDto> events(
            @AuthenticationPrincipal DashboardUserDetails principal,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) Long chatId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "100") int limit) {
        long effective = accessPolicy.effectiveUserId(
                principal.getDashboardRole(), principal.getBotUserId(), userId);
        return statsQueryService.recentEvents(
                effective > 0 ? effective : null, chatId, status, clamp(limit, 500));
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private static int clamp(int value, int max) {
        return Math.max(1, Math.min(value, max));
    }

    private static StatsPeriod overrideGranularity(StatsPeriod base, String granularity) {
        if (granularity == null || granularity.isBlank() || "auto".equalsIgnoreCase(granularity)) {
            return base;
        }
        StatsPeriod.Granularity g = switch (granularity.toLowerCase()) {
            case "day" -> StatsPeriod.Granularity.DAY;
            case "week" -> StatsPeriod.Granularity.WEEK;
            case "month" -> StatsPeriod.Granularity.MONTH;
            default -> throw new IllegalArgumentException(
                    "Невалидная granularity: " + granularity + " (ожидалось day/week/month/auto)");
        };
        return new StatsPeriod(base.from(), base.to(), g);
    }
}
