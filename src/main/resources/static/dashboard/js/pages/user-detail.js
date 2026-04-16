/**
 * user-detail.js — KPI + 2 графика для /dashboard/user/{botUserId}.
 * Данные: /dashboard/api/stats/user/{id} + /timeseries.
 */
(function () {
    "use strict";

    const { fetchJson, formatNumber, formatBytes, readPeriodFromUrl,
            setKpi, renderTimeseries, renderBarChart, onReady } = window.Dashboard || {};
    if (!fetchJson) { return; }

    const root = document.querySelector("[data-bot-user-id]");
    const botUserId = root ? root.dataset.botUserId : null;
    if (!botUserId) { return; }

    async function load() {
        const period = readPeriodFromUrl();
        const params = { period: period.period, from: period.from, to: period.to };
        try {
            const [detail, timeseries, chats] = await Promise.all([
                fetchJson(`/dashboard/api/stats/user/${botUserId}`),
                fetchJson("/dashboard/api/stats/timeseries",
                        { ...params, metric: "exports", granularity: "auto", userId: botUserId }),
                fetchJson("/dashboard/api/stats/chats",
                        { ...params, userId: botUserId, limit: 10 }),
            ]);
            setKpi("totalExports", formatNumber(detail.totalExports));
            setKpi("totalMessages", formatNumber(detail.totalMessages));
            setKpi("totalBytes", formatBytes(detail.totalBytes));
            renderTimeseries("chart-timeseries", timeseries);
            renderBarChart("chart-top-chats", chats, {
                labelFn: c => c.chatTitle || c.canonicalChatId,
                valueFn: c => c.totalBytes,
                label: "bytes", color: "#1e50c8", tickFn: v => formatBytes(v),
            });
        } catch (e) {
            console.error("user-detail load failed:", e);
        }
    }

    onReady(load);
})();
