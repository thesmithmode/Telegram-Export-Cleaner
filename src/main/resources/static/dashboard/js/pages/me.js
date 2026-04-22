/**
 * me.js — персональный дашборд /dashboard/me. Всё тянет из /api/me/**;
 * скоуп = principal.botUserId (обеспечивается сервером).
 */
(function () {
    "use strict";

    const { fetchJson, formatNumber, formatBytes, readPeriodFromUrl,
            setKpi, setKpiDelta, setKpiMeta, setCountBadge,
            renderKpiSparkline, renderStatsBar, renderStatusDoughnut,
            renderTimeseries, initSortableTable, onReady } = window.Dashboard || {};
    if (!fetchJson) { return; }

    const METRICS = ["exports", "messages", "bytes"];

    // Локализованные empty-строки приходят из me.html data-атрибутов
    // (ключи me.chats.empty / me.events.empty; fallback — английский).
    const meRoot = document.querySelector(".user-detail");
    const EMPTY_CHATS = (meRoot && meRoot.dataset.emptyChats) || "No chats yet.";
    const EMPTY_EVENTS = (meRoot && meRoot.dataset.emptyEvents) || "No events yet.";

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

    function fillChatsTable(tbody, rows) {
        clear(tbody);
        if (!rows || !rows.length) {
            tbody.appendChild(emptyRow(4, EMPTY_CHATS));
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

    function fillEventsTable(tbody, rows) {
        clear(tbody);
        if (!rows || !rows.length) {
            tbody.appendChild(emptyRow(4, EMPTY_EVENTS));
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

    function chatsSortValue(c, key) {
        if (key === "chat") { return c.chatTitle || String(c.canonicalChatId || ""); }
        return c[key];
    }

    function eventsSortValue(e, key) {
        if (key === "chat") { return e.chatTitle || String(e.canonicalChatId || ""); }
        return e[key];
    }

    function renderChatsTable(rows) {
        const tbody = document.getElementById("my-chats-body");
        if (!tbody) { return; }
        const list = rows || [];
        fillChatsTable(tbody, list);
        setCountBadge("my-chats", list.length);
        if (initSortableTable) {
            const table = tbody.closest("table");
            if (table) {
                initSortableTable(table, {
                    rows: list,
                    rerender: (sorted) => fillChatsTable(tbody, sorted),
                    getValue: chatsSortValue,
                });
            }
        }
    }

    function renderEventsTable(rows) {
        const tbody = document.getElementById("my-events-body");
        if (!tbody) { return; }
        const list = rows || [];
        fillEventsTable(tbody, list);
        setCountBadge("my-events", list.length);
        if (initSortableTable) {
            const table = tbody.closest("table");
            if (table) {
                initSortableTable(table, {
                    rows: list,
                    rerender: (sorted) => fillEventsTable(tbody, sorted),
                    getValue: eventsSortValue,
                });
            }
        }
    }

    async function load() {
        const period = readPeriodFromUrl();
        const params = { period: period.period, from: period.from, to: period.to };
        try {
            const [overview, tsExports, tsMessages, tsBytes, chats, events, status] = await Promise.all([
                fetchJson("/dashboard/api/me/overview", params),
                ...METRICS.map(m => fetchJson("/dashboard/api/me/timeseries",
                        { ...params, metric: m, granularity: "auto" })),
                fetchJson("/dashboard/api/me/chats", { ...params, limit: 20 }),
                fetchJson("/dashboard/api/me/events", { limit: 50 }),
                fetchJson("/dashboard/api/me/status-breakdown", params),
            ]);
            setKpi("totalExports", formatNumber(overview.totalExports));
            setKpi("totalMessages", formatNumber(overview.totalMessages));
            setKpi("totalBytes", formatBytes(overview.totalBytes));

            setKpiDelta("exports", overview.deltaExports, { kind: "percent" });
            setKpiDelta("messages", overview.deltaMessages, { kind: "percent" });
            setKpiDelta("bytes", overview.deltaBytes, { kind: "percent" });

            [tsExports, tsMessages, tsBytes].forEach((ts, i) => renderKpiSparkline(METRICS[i], ts));

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

            renderStatusDoughnut("chart-status", status || {});
            renderChatsTable(chats);
            renderEventsTable(events);
        } catch (e) {
            console.error("me load failed:", e);
        }
    }

    onReady(load);
})();
