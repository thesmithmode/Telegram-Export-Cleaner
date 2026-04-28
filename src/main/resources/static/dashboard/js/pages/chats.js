/**
 * chats.js — бар-чарт топ-20 + таблица чатов на /dashboard/chats.
 * Данные: GET /dashboard/api/stats/chats.
 */
(function () {
    "use strict";

    const { fetchJson, formatNumber, formatBytes, readPeriodFromUrl, escapeHtml,
            renderBarChart, setCountBadge, initSortableTable, onReady } = window.Dashboard || {};
    if (!fetchJson) { return; }

    function row(c) {
        const name = c.chatTitle
            ? escapeHtml(c.chatTitle)
            : `<code>${escapeHtml(String(c.canonicalChatId))}</code>`;
        return `<tr>
          <td>${name}</td>
          <td>${formatNumber(c.exportCount)}</td>
          <td>${formatNumber(c.totalMessages)}</td>
          <td>${formatBytes(c.totalBytes)}</td>
        </tr>`;
    }

    function render(tbody, rows) {
        tbody.innerHTML = rows.length
            ? rows.map(row).join("")
            : `<tr><td colspan="4" style="text-align:center;color:var(--muted)">Нет данных</td></tr>`;
    }

    function sortValue(c, key) {
        if (key === "chat") { return c.chatTitle || String(c.canonicalChatId || ""); }
        return c[key];
    }

    async function load() {
        const period = readPeriodFromUrl();
        const params = { period: period.period, from: period.from, to: period.to, limit: 100 };
        const tbody = document.getElementById("chats-tbody");
        try {
            const chats = await fetchJson("/dashboard/api/stats/chats", params);
            renderBarChart("chart-top-chats", chats.slice(0, 20), {
                labelFn: c => c.chatTitle || c.canonicalChatId,
                valueFn: c => c.totalBytes,
                label: "bytes", color: "#1e50c8", tickFn: v => formatBytes(v),
            });
            if (tbody) {
                render(tbody, chats);
                setCountBadge("chats", chats.length);
                if (initSortableTable) {
                    initSortableTable(document.getElementById("chats-table"), {
                        rows: chats,
                        rerender: (sorted) => render(tbody, sorted),
                        getValue: sortValue,
                    });
                }
            }
        } catch (e) {
            if (tbody) {
                const ec = document.createElement("td");
                ec.colSpan = 4; ec.style.color = "var(--danger)"; ec.textContent = `Ошибка: ${e.message}`;
                tbody.textContent = "";
                tbody.appendChild(document.createElement("tr")).appendChild(ec);
            }
        }
    }

    onReady(load);
})();
