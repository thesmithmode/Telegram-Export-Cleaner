/**
 * events.js — raw-таблица последних экспортов на /dashboard/events.
 * Данные: GET /dashboard/api/stats/events.
 * Поддерживает фильтр по статусу без перезагрузки страницы.
 */
(function () {
    "use strict";

    const { fetchJson, formatNumber, formatBytes, formatDate, escapeHtml,
            setCountBadge, initSortableTable, onReady } = window.Dashboard || {};
    if (!fetchJson) { return; }

    const STATUS_CLASS = {
        completed: "status-chip--completed",
        failed: "status-chip--failed",
        processing: "status-chip--processing",
        queued: "status-chip--queued",
        cancelled: "status-chip--cancelled",
    };

    function chip(status) {
        const cls = STATUS_CLASS[status] || "";
        return `<span class="status-chip ${cls}">${escapeHtml(String(status ?? ""))}</span>`;
    }

    function row(e) {
        const user = e.username
            ? `<code>@${escapeHtml(e.username)}</code>`
            : (e.botUserId ? `<code>${escapeHtml(String(e.botUserId))}</code>` : "—");
        const chat = e.chatTitle
            ? escapeHtml(e.chatTitle)
            : (e.canonicalChatId ? `<code>${escapeHtml(String(e.canonicalChatId))}</code>` : "—");
        return `<tr>
          <td>${user}</td>
          <td>${chat}</td>
          <td>${chip(e.status)}</td>
          <td>${formatNumber(e.messagesCount)}</td>
          <td>${formatBytes(e.bytesCount)}</td>
          <td>${formatDate(e.startedAt)}</td>
        </tr>`;
    }

    function render(tbody, rows) {
        tbody.innerHTML = rows.length
            ? rows.map(row).join("")
            : `<tr><td colspan="6" style="text-align:center;color:var(--muted)">Нет данных</td></tr>`;
    }

    function sortValue(e, key) {
        if (key === "user") { return e.username || String(e.botUserId || ""); }
        if (key === "chat") { return e.chatTitle || String(e.canonicalChatId || ""); }
        return e[key];
    }

    let _loadAbort = null;

    async function load(status) {
        if (_loadAbort) { _loadAbort.abort(); }
        _loadAbort = new AbortController();
        const signal = _loadAbort.signal;
        const tbody = document.getElementById("events-tbody");
        if (!tbody) { return; }
        const params = { limit: 100 };
        if (status) { params.status = status; }
        try {
            const events = await fetchJson("/dashboard/api/stats/recent", params, signal);
            render(tbody, events);
            setCountBadge("events", events.length);
            if (initSortableTable) {
                initSortableTable(document.getElementById("events-table"), {
                    rows: events,
                    rerender: (sorted) => render(tbody, sorted),
                    getValue: sortValue,
                });
            }
        } catch (e) {
            if (e.name === "AbortError") { return; }
            const ec = document.createElement("td");
            ec.colSpan = 6; ec.style.color = "var(--danger)"; ec.textContent = `Ошибка: ${e.message}`;
            tbody.textContent = "";
            tbody.appendChild(document.createElement("tr")).appendChild(ec);
        }
    }

    function init() {
        const select = document.getElementById("status-filter");
        if (select) {
            select.addEventListener("change", () => load(select.value));
        }
        load(select ? select.value : "");
    }

    onReady(init);
})();
