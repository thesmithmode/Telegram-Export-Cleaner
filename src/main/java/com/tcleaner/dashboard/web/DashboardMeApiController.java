package com.tcleaner.dashboard.web;

import com.tcleaner.dashboard.auth.DashboardUserDetails;
import com.tcleaner.dashboard.dto.ChatStatsRow;
import com.tcleaner.dashboard.dto.EventRowDto;
import com.tcleaner.dashboard.dto.OverviewDto;
import com.tcleaner.dashboard.dto.TimeSeriesPointDto;
import com.tcleaner.dashboard.service.stats.PeriodResolver;
import com.tcleaner.dashboard.service.stats.StatsQueryService;
import com.tcleaner.dashboard.util.PaginationUtils;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Личное API пользователя. Скоуп ВСЕГДА = {@code principal.botUserId}.
 * Контроллер намеренно не принимает параметр {@code userId} ни в каком виде —
 * защита от подмены (IDOR): даже если USER добавит {@code ?userId=...} вручную,
 * он просто проигнорируется сервлет-контейнером.
 *
 * Если {@code botUserId} отсутствует (случай ADMIN без bot-привязки), возвращаем
 * пустые коллекции/нулевой DTO — чтобы страница /me не падала.
 */
@RestController
@RequestMapping("/dashboard/api/me")
public class DashboardMeApiController {

    private final StatsQueryService stats;
    private final PeriodResolver periods;

    public DashboardMeApiController(StatsQueryService stats, PeriodResolver periods) {
        this.stats = stats;
        this.periods = periods;
    }

    @GetMapping("/overview")
    public OverviewDto overview(@AuthenticationPrincipal DashboardUserDetails p,
                                @RequestParam(required = false) String period,
                                @RequestParam(required = false)
                                @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                @RequestParam(required = false)
                                @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        Long my = p.getBotUserId();
        if (my == null) {
            return OverviewDto.empty();
        }
        return stats.overviewWithDelta(periods.resolve(period, from, to), my);
    }

    @GetMapping("/chats")
    public List<ChatStatsRow> chats(@AuthenticationPrincipal DashboardUserDetails p,
                                    @RequestParam(required = false) String period,
                                    @RequestParam(required = false)
                                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                    @RequestParam(required = false)
                                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                    @RequestParam(defaultValue = "20") int limit) {
        Long my = p.getBotUserId();
        if (my == null) {
            return List.of();
        }
        return stats.topChats(periods.resolve(period, from, to), my, PaginationUtils.clamp(limit, 200));
    }

    @GetMapping("/events")
    public List<EventRowDto> events(@AuthenticationPrincipal DashboardUserDetails p,
                                    @RequestParam(required = false) Long chatId,
                                    @RequestParam(required = false) String status,
                                    @RequestParam(defaultValue = "100") int limit) {
        Long my = p.getBotUserId();
        if (my == null) {
            return List.of();
        }
        return stats.recentEvents(my, chatId, status, PaginationUtils.clamp(limit, 500));
    }

    @GetMapping("/timeseries")
    public List<TimeSeriesPointDto> timeseries(@AuthenticationPrincipal DashboardUserDetails p,
                                               @RequestParam(required = false) String period,
                                               @RequestParam(required = false)
                                               @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                               @RequestParam(required = false)
                                               @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                               @RequestParam(defaultValue = "exports") String metric) {
        Long my = p.getBotUserId();
        if (my == null) {
            return List.of();
        }
        return stats.timeSeries(periods.resolve(period, from, to), metric, my);
    }

    @GetMapping("/status-breakdown")
    public Map<String, Long> statusBreakdown(@AuthenticationPrincipal DashboardUserDetails p,
                                             @RequestParam(required = false) String period,
                                             @RequestParam(required = false)
                                             @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                             @RequestParam(required = false)
                                             @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        Long my = p.getBotUserId();
        if (my == null) {
            return Map.of();
        }
        return stats.statusBreakdown(periods.resolve(period, from, to), my);
    }
}
