/**
 * overview.js — наполняет KPI, таблицы и рисует 4 Chart.js-графика на /dashboard/overview.
 * Данные берёт из /dashboard/api/stats/overview и /timeseries.
 * Chart.js подключён глобально через layout.html (static/dashboard/vendor/chart.min.js).
 */
(function () {
    "use strict";

    const { fetchJson, formatNumber, formatBytes, readPeriodFromUrl } = window.Dashboard || {};
    if (!fetchJson) { return; }

    const STATUS_COLORS = {
        completed: "#2b8a3e",
        failed: "#d64545",
        processing: "#b7791f",
        queued: "#3358d4",
        cancelled: "#666e7b",
    };

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
                    fill: true,
                    tension: 0.25,
                }],
            },
            options: { responsive: true, maintainAspectRatio: false,
                scales: { y: { beginAtZero: true, ticks: { precision: 0 } } } },
        });
    }

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

    function renderTopUsers(rows) {
        const ctx = makeCanvas("chart-top-users");
        if (!ctx || !window.Chart) { return; }
        new Chart(ctx, {
            type: "bar",
            data: {
                labels: rows.map(r => r.username || `id ${r.botUserId}`),
                datasets: [{
                    label: "exports",
                    data: rows.map(r => r.totalExports),
                    backgroundColor: "#2563eb",
                }],
            },
            options: { responsive: true, maintainAspectRatio: false, indexAxis: "y",
                scales: { x: { beginAtZero: true, ticks: { precision: 0 } } } },
        });
    }

    function renderTopChats(rows) {
        const ctx = makeCanvas("chart-top-chats");
        if (!ctx || !window.Chart) { return; }
        new Chart(ctx, {
            type: "bar",
            data: {
                labels: rows.map(r => r.chatTitle || r.canonicalChatId),
                datasets: [{
                    label: "bytes",
                    data: rows.map(r => r.totalBytes),
                    backgroundColor: "#1e50c8",
                }],
            },
            options: { responsive: true, maintainAspectRatio: false, indexAxis: "y",
                scales: {
                    x: { beginAtZero: true,
                        ticks: { callback: v => formatBytes(v) } },
                } },
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
            renderTimeseries(timeseries);
            renderStatus(overview.statusBreakdown || {});
            renderTopUsers(overview.topUsers || []);
            renderTopChats(overview.topChats || []);
        } catch (e) {
            console.error("overview load failed:", e);
        }
    }

    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", load);
    } else {
        load();
    }
})();
