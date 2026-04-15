/**
 * chats.js — бар-чарт топ-20 + таблица чатов на /dashboard/chats.
 * Данные: GET /dashboard/api/stats/chats.
 */
(function () {
    "use strict";

    const { fetchJson, formatNumber, formatBytes, readPeriodFromUrl } = window.Dashboard || {};
    if (!fetchJson) { return; }

    function makeCanvas(id) {
        const el = document.getElementById(id);
        if (!el) { return null; }
        const h = Number(el.dataset.height) || 300;
        el.style.height = `${h}px`;
        return el.getContext("2d");
    }

    function renderChart(chats) {
        const ctx = makeCanvas("chart-top-chats");
        if (!ctx || !window.Chart) { return; }
        const top20 = chats.slice(0, 20);
        new Chart(ctx, {
            type: "bar",
            data: {
                labels: top20.map(c => c.chatTitle || c.canonicalChatId),
                datasets: [{
                    label: "bytes",
                    data: top20.map(c => c.totalBytes),
                    backgroundColor: "#1e50c8",
                }],
            },
            options: { responsive: true, maintainAspectRatio: false, indexAxis: "y",
                scales: { x: { beginAtZero: true,
                    ticks: { callback: v => formatBytes(v) } } } },
        });
    }

    function row(c) {
        const name = c.chatTitle || c.canonicalChatId;
        return `<tr>
          <td>${name}</td>
          <td>${formatNumber(c.exportCount)}</td>
          <td>${formatNumber(c.totalMessages)}</td>
          <td>${formatBytes(c.totalBytes)}</td>
        </tr>`;
    }

    async function load() {
        const period = readPeriodFromUrl();
        const params = { period: period.period, from: period.from, to: period.to, limit: 100 };
        const tbody = document.getElementById("chats-tbody");
        try {
            const chats = await fetchJson("/dashboard/api/stats/chats", params);
            renderChart(chats);
            tbody.innerHTML = chats.length
                ? chats.map(row).join("")
                : `<tr><td colspan="4" style="text-align:center;color:var(--muted)">Нет данных</td></tr>`;
        } catch (e) {
            if (tbody) {
                tbody.innerHTML =
                    `<tr><td colspan="4" style="color:var(--danger)">Ошибка: ${e.message}</td></tr>`;
            }
        }
    }

    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", load);
    } else {
        load();
    }
})();
