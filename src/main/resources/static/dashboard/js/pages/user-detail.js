/**
 * user-detail.js — KPI + 2 графика для /dashboard/user/{botUserId}.
 * Данные: /dashboard/api/stats/user/{id} + /timeseries.
 * botUserId читается из data-bot-user-id на .user-detail.
 */
(function () {
    "use strict";

    const { fetchJson, formatNumber, formatBytes, readPeriodFromUrl } = window.Dashboard || {};
    if (!fetchJson) { return; }

    const root = document.querySelector("[data-bot-user-id]");
    const botUserId = root ? root.dataset.botUserId : null;
    if (!botUserId) { return; }

    function setKpi(name, value) {
        const el = document.querySelector(`[data-kpi="${name}"]`);
        if (el) { el.textContent = value; }
    }

    function makeCanvas(id) {
        const el = document.getElementById(id);
        if (!el) { return null; }
        const h = Number(el.dataset.height) || 300;
        el.style.height = `${h}px`;
        return el.getContext("2d");
    }

    function renderTimeseries(points) {
        const ctx = makeCanvas("chart-timeseries");
        if (!ctx || !window.Chart) { return; }
        new Chart(ctx, {
            type: "line",
            data: {
                labels: points.map(p => p.period),
                datasets: [{
                    label: "exports",
                    data: points.map(p => p.value),
                    borderColor: "#2563eb",
                    backgroundColor: "rgba(37,99,235,.12)",
                    fill: true, tension: 0.25,
                }],
            },
            options: { responsive: true, maintainAspectRatio: false,
                scales: { y: { beginAtZero: true, ticks: { precision: 0 } } } },
        });
    }

    function renderTopChats(chats) {
        const ctx = makeCanvas("chart-top-chats");
        if (!ctx || !window.Chart) { return; }
        new Chart(ctx, {
            type: "bar",
            data: {
                labels: chats.map(c => c.chatTitle || c.canonicalChatId),
                datasets: [{
                    label: "bytes",
                    data: chats.map(c => c.totalBytes),
                    backgroundColor: "#1e50c8",
                }],
            },
            options: { responsive: true, maintainAspectRatio: false, indexAxis: "y",
                scales: { x: { beginAtZero: true,
                    ticks: { callback: v => formatBytes(v) } } } },
        });
    }

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
            renderTimeseries(timeseries);
            renderTopChats(chats);
        } catch (e) {
            console.error("user-detail load failed:", e);
        }
    }

    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", load);
    } else {
        load();
    }
})();
