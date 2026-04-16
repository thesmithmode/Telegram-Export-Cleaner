/**
 * overview.js — наполняет KPI, таблицы и рисует 4 Chart.js-графика на /dashboard/overview.
 * Данные берёт из /dashboard/api/stats/overview и /timeseries.
 */
(function () {
    "use strict";

    const { fetchJson, formatNumber, formatBytes, readPeriodFromUrl,
            setKpi, makeCanvas, renderTimeseries, renderBarChart, onReady } = window.Dashboard || {};
    if (!fetchJson) { return; }

    const STATUS_COLORS = {
        completed: "#2b8a3e",
        failed: "#d64545",
        processing: "#b7791f",
        queued: "#3358d4",
        cancelled: "#666e7b",
    };

    function renderStatus(breakdown) {
        const ctx = makeCanvas("chart-status");
        if (!ctx || !window.Chart) { return; }
        const labels = Object.keys(breakdown);
        new Chart(ctx, {
            type: "doughnut",
            data: {
                labels,
                datasets: [{
                    data: labels.map(k => breakdown[k]),
                    backgroundColor: labels.map(k => STATUS_COLORS[k] || "#888"),
                }],
            },
            options: { responsive: true, maintainAspectRatio: false },
        });
    }

    async function load() {
        const period = readPeriodFromUrl();
        const params = { period: period.period, from: period.from, to: period.to };
        try {
            const [overview, timeseries] = await Promise.all([
                fetchJson("/dashboard/api/stats/overview", params),
                fetchJson("/dashboard/api/stats/timeseries",
                        { ...params, metric: "exports", granularity: "auto" }),
            ]);
            setKpi("totalExports", formatNumber(overview.totalExports));
            setKpi("totalMessages", formatNumber(overview.totalMessages));
            setKpi("totalBytes", formatBytes(overview.totalBytes));
            setKpi("totalUsers", formatNumber(overview.totalUsers));
            renderTimeseries("chart-timeseries", timeseries);
            renderStatus(overview.statusBreakdown || {});
            renderBarChart("chart-top-users", overview.topUsers || [], {
                labelFn: r => r.username || `id ${r.botUserId}`,
                valueFn: r => r.totalExports,
                label: "exports", color: "#2563eb",
            });
            renderBarChart("chart-top-chats", overview.topChats || [], {
                labelFn: r => r.chatTitle || r.canonicalChatId,
                valueFn: r => r.totalBytes,
                label: "bytes", color: "#1e50c8", tickFn: v => formatBytes(v),
            });
        } catch (e) {
            console.error("overview load failed:", e);
        }
    }

    onReady(load);
})();
