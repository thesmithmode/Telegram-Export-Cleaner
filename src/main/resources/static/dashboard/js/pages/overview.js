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
            renderTimeseries, renderBarChart, initSortableTable, onReady } = window.Dashboard || {};
    if (!fetchJson) { return; }

    const METRICS = ["exports", "messages", "bytes", "users"];

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
            const tsUsers  = series[3];

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
            renderTimeseries("chart-users", tsUsers);

            renderStatusDoughnut("chart-status", overview.statusBreakdown || {});

            renderBarChart("chart-top-users", overview.topUsers || [], {
                labelFn: r => r.username || `id ${r.botUserId}`,
                valueFn: r => r.totalExports,
                label: "exports", color: "#2563eb",
            });
            setCountBadge("top-users", (overview.topUsers || []).length);

            const totalBytes = Number(overview.totalBytes) || 0;
            renderBarChart("chart-top-chats", overview.topChats || [], {
                labelFn: r => r.chatTitle || r.canonicalChatId,
                valueFn: r => totalBytes > 0 ? (Number(r.totalBytes) || 0) / totalBytes * 100 : 0,
                label: "% от общего", color: "#1e50c8", tickFn: v => v.toFixed(1) + "%",
            });
            setCountBadge("top-chats", (overview.topChats || []).length);
        } catch (e) {
            console.error("overview load failed:", e);
        }
    }

    function renderCachePanel(data) {
        const statusEl = document.querySelector("[data-cache-status]");
        const msgEl = document.querySelector("[data-cache-status-msg]");
        if (!data || !data.available) {
            if (statusEl) statusEl.hidden = false;
            if (msgEl && data?.message) msgEl.textContent = data.message;
            return;
        }
        if (statusEl) statusEl.hidden = true;

        const pctRaw = Number(data.pct) || 0;
        const pct = Math.max(0, Math.min(100, pctRaw));

        const setElementText = (selector, text) => {
            const el = document.querySelector(selector);
            if (el) el.textContent = text;
        };

        setElementText("[data-cache-kpi='usage']", pct.toFixed(1) + "%");
        setElementText("[data-cache-kpi-meta='usage']",
            formatBytes(data.usedBytes) + " / " + formatBytes(data.limitBytes));
        setElementText("[data-cache-kpi='chats']", formatNumber(data.totalChats));
        setElementText("[data-cache-kpi='messages']", formatNumber(data.totalMessages));

        const bar = document.querySelector("[data-cache-bar]");
        if (bar) {
            bar.style.width = pct.toFixed(1) + "%";
            bar.dataset.level = pct >= 90 ? "danger" : pct >= 70 ? "warn" : "ok";
        }

        const progress = document.querySelector("[data-cache-progress]");
        if (progress) progress.setAttribute("aria-valuenow", String(Math.round(pct)));

        const genEl = document.querySelector("[data-cache-kpi='generated']");
        if (genEl && data.generatedAt) {
            const ageSec = Math.max(0, Math.floor(Date.now() / 1000 - data.generatedAt));
            genEl.textContent = ageSec < 90 ? ageSec + "s назад" : Math.floor(ageSec / 60) + "мин назад";
        }

        const used = Number(data.usedBytes) || 0;
        const heatmap = (data.heatmap || []).reduce((acc, h) => {
            acc[h.bucket] = h.sizeBytes;
            return acc;
        }, {});
        const pctOf = (b) => used > 0 ? (b / used * 100) : 0;
        renderBarChart("chart-cache-heatmap", [
            { label: "Hot <7д", value: pctOf(heatmap.hot || 0) },
            { label: "Warm 7–30д", value: pctOf(heatmap.warm || 0) },
            { label: "Cold >30д", value: pctOf(heatmap.cold || 0) },
        ], {
            labelFn: r => r.label, valueFn: r => r.value,
            label: "% кэша", color: "#b7791f", tickFn: v => v.toFixed(1) + "%",
        });

        const segEntries = Object.entries(data.chatTypeSegmentation || {})
            .map(([type, seg]) => ({
                type,
                sizeBytes: Number(seg && seg.sizeBytes) || 0,
                chatCount: Number(seg && seg.chatCount) || 0,
            }))
            .sort((a, b) => b.sizeBytes - a.sizeBytes);
        renderBarChart("chart-cache-types", segEntries, {
            labelFn: r => r.type + " · " + r.chatCount,
            valueFn: r => r.sizeBytes,
            label: "bytes", color: "#2b8a3e", tickFn: v => formatBytes(v),
        });

        const tbody = document.querySelector("#table-cache-top tbody");
        if (tbody) {
            const topChats = data.topChats || [];
            fillCacheTopTable(tbody, topChats);
            if (initSortableTable) {
                initSortableTable(document.getElementById("table-cache-top"), {
                    rows: topChats,
                    rerender: (sorted) => fillCacheTopTable(tbody, sorted),
                    getValue: cacheTopSortValue,
                });
            }
        }
    }

    function cacheTopTitle(c) {
        if (c.username) { return "@" + c.username; }
        if (c.title) { return c.topicId ? c.title + " (топик " + c.topicId + ")" : c.title; }
        return String(c.chatId);
    }

    function cacheTopSortValue(c, key) {
        if (key === "chat") { return cacheTopTitle(c); }
        return c[key];
    }

    function fillCacheTopTable(tbody, rows) {
        while (tbody.firstChild) { tbody.removeChild(tbody.firstChild); }
        rows.forEach((c, i) => {
            const tr = document.createElement("tr");
            // МСК-время для админ-панели. Intl сам учитывает DST и переходы часовых
            // поясов — без хардкода смещения. Локаль sv-SE даёт ISO-подобный
            // формат "YYYY-MM-DD HH:MM" без переводов.
            const lastAccess = c.lastAccessed
                ? new Intl.DateTimeFormat("sv-SE", {
                      timeZone: "Europe/Moscow",
                      year: "numeric", month: "2-digit", day: "2-digit",
                      hour: "2-digit", minute: "2-digit",
                      hour12: false,
                  }).format(new Date(c.lastAccessed * 1000)).replace(",", "")
                : "—";
            const cells = [
                String(i + 1),
                cacheTopTitle(c),
                c.chatType || "—",
                formatNumber(c.msgCount),
                formatBytes(c.sizeBytes),
                (Number(c.pct) || 0).toFixed(1) + "%",
                lastAccess,
            ];
            cells.forEach(v => {
                const td = document.createElement("td");
                td.textContent = v;
                tr.appendChild(td);
            });
            tbody.appendChild(tr);
        });
    }

    async function loadCachePanel() {
        try {
            const data = await fetchJson("/dashboard/api/admin/cache-metrics");
            renderCachePanel(data);
        } catch (e) {
            console.error("cache-metrics load failed:", e);
            renderCachePanel({ available: false, message: "Ошибка загрузки: " + (e.message || e) });
        }
    }

    onReady(() => { load(); loadCachePanel(); });
})();
