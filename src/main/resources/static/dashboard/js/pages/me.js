/**
 * me.js — персональный дашборд /dashboard/me. Всё тянет из /api/me/**;
 * скоуп = principal.botUserId (обеспечивается сервером).
 */
(function () {
    "use strict";

    const { fetchJson, formatNumber, formatBytes, readPeriodFromUrl,
            setKpi, makeCanvas, renderTimeseries, onReady } = window.Dashboard || {};
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

    function clear(el) { while (el.firstChild) { el.removeChild(el.firstChild); } }

    function tr(cells) {
        const row = document.createElement("tr");
        for (const text of cells) {
            const td = document.createElement("td");
            td.textContent = text == null ? "—" : String(text);
            row.appendChild(td);
        }
        return row;
    }

    function emptyRow(colspan, text) {
        const row = document.createElement("tr");
        const td = document.createElement("td");
        td.colSpan = colspan;
        td.textContent = text;
        row.appendChild(td);
        return row;
    }

    function renderChatsTable(rows) {
        const tbody = document.getElementById("my-chats-body");
        if (!tbody) { return; }
        clear(tbody);
        if (!rows || !rows.length) {
            tbody.appendChild(emptyRow(4, "No chats yet."));
            return;
        }
        for (const r of rows) {
            tbody.appendChild(tr([
                r.chatTitle || r.canonicalChatId || "—",
                formatNumber(r.exportCount || 0),
                formatNumber(r.totalMessages || 0),
                formatBytes(r.totalBytes || 0),
            ]));
        }
    }

    function renderEventsTable(rows) {
        const tbody = document.getElementById("my-events-body");
        if (!tbody) { return; }
        clear(tbody);
        if (!rows || !rows.length) {
            tbody.appendChild(emptyRow(4, "No events yet."));
            return;
        }
        for (const r of rows) {
            tbody.appendChild(tr([
                r.startedAt || "—",
                r.chatTitle || r.canonicalChatId || "—",
                r.status || "—",
                formatNumber(r.messagesCount || 0),
            ]));
        }
    }

    async function load() {
        const period = readPeriodFromUrl();
        const params = { period: period.period, from: period.from, to: period.to };
        try {
            const [overview, timeseries, chats, events, status] = await Promise.all([
                fetchJson("/dashboard/api/me/overview", params),
                fetchJson("/dashboard/api/me/timeseries",
                        { ...params, metric: "exports", granularity: "auto" }),
                fetchJson("/dashboard/api/me/chats", { ...params, limit: 20 }),
                fetchJson("/dashboard/api/me/events", { limit: 50 }),
                fetchJson("/dashboard/api/me/status-breakdown", params),
            ]);
            setKpi("totalExports", formatNumber(overview.totalExports));
            setKpi("totalMessages", formatNumber(overview.totalMessages));
            setKpi("totalBytes", formatBytes(overview.totalBytes));
            renderTimeseries("chart-timeseries", timeseries);
            renderStatus(status || {});
            renderChatsTable(chats);
            renderEventsTable(events);
        } catch (e) {
            console.error("me load failed:", e);
        }
    }

    onReady(load);
})();
