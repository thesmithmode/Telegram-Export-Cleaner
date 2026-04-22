/**
 * users.js — наполняет таблицу топ-пользователей на /dashboard/users (ADMIN only).
 * Данные: GET /dashboard/api/stats/users.
 */
(function () {
    "use strict";

    const { fetchJson, formatNumber, formatBytes, formatDate, escapeHtml,
            setCountBadge, initSortableTable, onReady } = window.Dashboard || {};
    if (!fetchJson) { return; }

    function row(u) {
        const name = escapeHtml(u.displayName || u.username || `id ${u.botUserId}`);
        const link = `/dashboard/user/${encodeURIComponent(u.botUserId)}`;
        return `<tr>
          <td><a href="${link}">${name}</a> <small style="color:var(--muted)"><code>@${escapeHtml(u.username || "")}</code></small></td>
          <td>${formatNumber(u.totalExports)}</td>
          <td>${formatNumber(u.totalMessages)}</td>
          <td>${formatBytes(u.totalBytes)}</td>
          <td>${formatDate(u.lastSeen)}</td>
        </tr>`;
    }

    function render(tbody, rows) {
        tbody.innerHTML = rows.length
            ? rows.map(row).join("")
            : `<tr><td colspan="5" style="text-align:center;color:var(--muted)">Нет данных</td></tr>`;
    }

    function sortValue(u, key) {
        if (key === "user") { return u.displayName || u.username || String(u.botUserId || ""); }
        return u[key];
    }

    async function load() {
        const tbody = document.getElementById("users-tbody");
        if (!tbody) { return; }
        try {
            const rows = await fetchJson("/dashboard/api/stats/users", { limit: 200 });
            render(tbody, rows);
            setCountBadge("users", rows.length);
            if (initSortableTable) {
                initSortableTable(document.getElementById("users-table"), {
                    rows,
                    rerender: (sorted) => render(tbody, sorted),
                    getValue: sortValue,
                });
            }
        } catch (e) {
            tbody.innerHTML =
                `<tr><td colspan="5" style="color:var(--danger)">Ошибка загрузки: ${e.message}</td></tr>`;
        }
    }

    onReady(load);
})();
