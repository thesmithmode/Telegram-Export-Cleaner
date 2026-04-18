/**
 * events.js — raw-таблица последних экспортов на /dashboard/events.
 * Данные: GET /dashboard/api/stats/events.
 * Поддерживает фильтр по статусу без перезагрузки страницы.
 */
(function () {
    "use strict";

    const { fetchJson, formatNumber, formatBytes, formatDate, escapeHtml, onReady } = window.Dashboard || {};
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
        return `<span class="status-chip ${cls}">${status}</span>`;
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

    async function load(status) {
        const tbody = document.getElementById("events-tbody");
        if (!tbody) { return; }
        const params = { limit: 100 };
        if (status) { params.status = status; }
        try {
            const events = await fetchJson("/dashboard/api/stats/events", params);
            tbody.innerHTML = events.length
                ? events.map(row).join("")
                : `<tr><td colspan="6" style="text-align:center;color:var(--muted)">Нет данных</td></tr>`;
        } catch (e) {
            tbody.innerHTML =
                `<tr><td colspan="6" style="color:var(--danger)">Ошибка: ${e.message}</td></tr>`;
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
