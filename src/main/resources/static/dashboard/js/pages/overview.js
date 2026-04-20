/**
 * overview.js — наполняет KPI (значение + delta + sparkline + meta),
 * таблицы, 4 Chart.js-графика и stats-bar на /dashboard/overview.
 * Данные: /dashboard/api/stats/overview + /timeseries (×3 метрики).
 */
(function () {
    "use strict";

    const { fetchJson, formatNumber, formatBytes, readPeriodFromUrl,
            setKpi, setKpiDelta, setKpiMeta, setCountBadge,
            renderKpiSparkline, renderStatsBar, renderStatusDoughnut,
            renderTimeseries, renderBarChart, onReady } = window.Dashboard || {};
    if (!fetchJson) { return; }

    const METRICS = ["exports", "messages", "bytes"];

    async function load() {
        const period = readPeriodFromUrl();
        const params = { period: period.period, from: period.from, to: period.to };
        try {
            const [overview, ...series] = await Promise.all([
                fetchJson("/dashboard/api/stats/overview", params),
                ...METRICS.map(m => fetchJson("/dashboard/api/stats/timeseries",
                        { ...params, metric: m, granularity: "auto" })),
            ]);
            const tsExports = series[0];

            setKpi("totalExports", formatNumber(overview.totalExports));
            setKpi("totalMessages", formatNumber(overview.totalMessages));
            setKpi("totalBytes", formatBytes(overview.totalBytes));
            setKpi("totalUsers", formatNumber(overview.totalUsers));

            setKpiDelta("exports", overview.deltaExports, { kind: "percent" });
            setKpiDelta("messages", overview.deltaMessages, { kind: "percent" });
            setKpiDelta("bytes", overview.deltaBytes, { kind: "percent" });
            setKpiDelta("users", overview.deltaUsers, { kind: "percent" });

            METRICS.forEach((m, i) => renderKpiSparkline(m, series[i]));

            const exportsCount = overview.totalExports || 1;
            const peakExports = tsExports.length
                ? Math.max(...tsExports.map(p => Number(p.value) || 0)) : null;
            setKpiMeta("exports", peakExports !== null
                ? [{ value: formatNumber(peakExports), label: "пик" }] : null);
            setKpiMeta("messages", [
                { value: formatNumber(Math.round((overview.totalMessages || 0) / exportsCount)), label: "/эксп" },
            ]);
            setKpiMeta("bytes", [
                { value: formatBytes(Math.round((overview.totalBytes || 0) / exportsCount)), label: "/эксп" },
            ]);

            renderTimeseries("chart-timeseries", tsExports);
            renderStatsBar("stats-chart-timeseries", tsExports);

            renderStatusDoughnut("chart-status", overview.statusBreakdown || {});

            renderBarChart("chart-top-users", overview.topUsers || [], {
                labelFn: r => r.username || `id ${r.botUserId}`,
                valueFn: r => r.totalExports,
                label: "exports", color: "#2563eb",
            });
            setCountBadge("top-users", (overview.topUsers || []).length);

            renderBarChart("chart-top-chats", overview.topChats || [], {
                labelFn: r => r.chatTitle || r.canonicalChatId,
                valueFn: r => r.totalBytes,
                label: "bytes", color: "#1e50c8", tickFn: v => formatBytes(v),
            });
            setCountBadge("top-chats", (overview.topChats || []).length);
        } catch (e) {
            console.error("overview load failed:", e);
        }
    }

    onReady(load);
})();
