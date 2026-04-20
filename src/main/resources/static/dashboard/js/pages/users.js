/**
 * users.js — наполняет таблицу топ-пользователей на /dashboard/users (ADMIN only).
 * Данные: GET /dashboard/api/stats/users.
 */
(function () {
    "use strict";

    const { fetchJson, formatNumber, formatBytes, formatDate, escapeHtml, setCountBadge, onReady } = window.Dashboard || {};
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

    async function load() {
        const tbody = document.getElementById("users-tbody");
        if (!tbody) { return; }
        try {
            const rows = await fetchJson("/dashboard/api/stats/users", { limit: 200 });
            tbody.innerHTML = rows.length
                ? rows.map(row).join("")
                : `<tr><td colspan="5" style="text-align:center;color:var(--muted)">Нет данных</td></tr>`;
            setCountBadge("users", rows.length);
        } catch (e) {
            tbody.innerHTML =
                `<tr><td colspan="5" style="color:var(--danger)">Ошибка загрузки: ${e.message}</td></tr>`;
        }
    }

    onReady(load);
})();
