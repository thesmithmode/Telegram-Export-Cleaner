package com.tcleaner.dashboard.web;

import com.tcleaner.dashboard.auth.DashboardUserDetails;
import com.tcleaner.dashboard.dto.CacheMetricsDto;
import com.tcleaner.dashboard.dto.ChatStatsRow;
import com.tcleaner.dashboard.dto.EventRowDto;
import com.tcleaner.dashboard.dto.MeDto;
import com.tcleaner.dashboard.dto.OverviewDto;
import com.tcleaner.dashboard.dto.TimeSeriesPointDto;
import com.tcleaner.dashboard.dto.UserDetailDto;
import com.tcleaner.dashboard.dto.UserStatsRow;
import com.tcleaner.dashboard.security.BotUserAccessPolicy;
import com.tcleaner.dashboard.service.cache.CacheMetricsService;
import com.tcleaner.dashboard.service.stats.PeriodResolver;
import com.tcleaner.dashboard.service.stats.StatsPeriod;
import com.tcleaner.dashboard.service.stats.StatsQueryService;
import com.tcleaner.dashboard.util.PaginationUtils;
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
    private final CacheMetricsService cacheMetricsService;

    public DashboardApiController(StatsQueryService statsQueryService,
                                  PeriodResolver periodResolver,
                                  BotUserAccessPolicy accessPolicy,
                                  CacheMetricsService cacheMetricsService) {
        this.statsQueryService = statsQueryService;
        this.periodResolver = periodResolver;
        this.accessPolicy = accessPolicy;
        this.cacheMetricsService = cacheMetricsService;
    }

    @GetMapping("/admin/cache-metrics")
    public CacheMetricsDto cacheMetrics() {
        return cacheMetricsService.get();
    }

    @GetMapping("/me")
    public MeDto me(@AuthenticationPrincipal DashboardUserDetails principal) {
        return new MeDto(
                principal.getUsername(),
                principal.getDashboardRole(),
                principal.getBotUserId());
    }

    @GetMapping("/stats/overview")
    public OverviewDto overview(
            @AuthenticationPrincipal DashboardUserDetails principal,
            @RequestParam(required = false) String period,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long userId) {
        Scope s = scope(principal, period, from, to, userId);
        return statsQueryService.overviewWithDelta(s.period(), s.botUserId());
    }

    @GetMapping("/stats/users")
    public List<UserStatsRow> users(
            @AuthenticationPrincipal DashboardUserDetails principal,
            @RequestParam(required = false) String period,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "200") int limit) {
        Scope s = scope(principal, period, from, to, null);
        if (period != null && !"all".equalsIgnoreCase(period)) {
            return statsQueryService.topUsersByPeriod(s.period(), PaginationUtils.clamp(limit, 500), s.botUserId());
        }
        return statsQueryService.topUsers(PaginationUtils.clamp(limit, 500), s.botUserId());
    }

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

    @GetMapping("/stats/chats")
    public List<ChatStatsRow> chats(
            @AuthenticationPrincipal DashboardUserDetails principal,
            @RequestParam(required = false) String period,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long userId,
            @RequestParam(defaultValue = "20") int limit) {
        Scope s = scope(principal, period, from, to, userId);
        return statsQueryService.topChats(s.period(), s.botUserId(), PaginationUtils.clamp(limit, 200));
    }

    @GetMapping("/stats/timeseries")
    public List<TimeSeriesPointDto> timeSeries(
            @AuthenticationPrincipal DashboardUserDetails principal,
            @RequestParam(required = false) String period,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false, defaultValue = "exports") String metric,
            @RequestParam(required = false) String granularity) {
        Scope s = scope(principal, period, from, to, userId);
        StatsPeriod resolved = overrideGranularity(s.period(), granularity);
        return statsQueryService.timeSeries(resolved, metric, s.botUserId());
    }

    @GetMapping("/stats/status-breakdown")
    public Map<String, Long> statusBreakdown(
            @AuthenticationPrincipal DashboardUserDetails principal,
            @RequestParam(required = false) String period,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long userId) {
        Scope s = scope(principal, period, from, to, userId);
        return statsQueryService.statusBreakdown(s.period(), s.botUserId());
    }

    // /stats/events переименован в /stats/recent: EasyPrivacy/uBlock блокирует
    // паттерн "stats/events" как tracking endpoint — запрос не доходил до сервера.
    @GetMapping("/stats/recent")
    public List<EventRowDto> events(
            @AuthenticationPrincipal DashboardUserDetails principal,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) Long chatId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "100") int limit) {
        Long effective = effectiveUserId(principal, userId);
        return statsQueryService.recentEvents(effective, chatId, status, PaginationUtils.clamp(limit, 500));
    }

    private Scope scope(DashboardUserDetails principal, String period,
                        LocalDate from, LocalDate to, Long userId) {
        return new Scope(
                periodResolver.resolve(period, from, to),
                effectiveUserId(principal, userId));
    }

    /** RBAC: возвращает {@code null} для «всех» (только ADMIN), либо конкретный id. */
    private Long effectiveUserId(DashboardUserDetails principal, Long userId) {
        long eff = accessPolicy.effectiveUserId(
                principal.getDashboardRole(), principal.getBotUserId(), userId);
        return eff > 0 ? eff : null;
    }

    private record Scope(StatsPeriod period, Long botUserId) {}

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
