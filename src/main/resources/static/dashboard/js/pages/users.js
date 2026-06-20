(function () {
    "use strict";

    const { fetchJson, formatNumber, formatBytes, formatDate,
            setCountBadge, initSortableTable, onReady, createElement } = window.Dashboard || {};
    if (!fetchJson) { return; }
    const el = createElement;

    function normalizeUsername(username) {
        if (!username) { return null; }
        return String(username).replace(/^@+/, "");
    }

    function telegramUserLink(u) {
        const username = normalizeUsername(u.username);
        if (username) {
            return {
                href: `https://t.me/${encodeURIComponent(username)}`,
                appHref: `tg://resolve?domain=${encodeURIComponent(username)}`,
                tme: true,
            };
        }
        if (u.botUserId) {
            const id = encodeURIComponent(u.botUserId);
            return { href: `tg://user?id=${id}`, appHref: `tg://user?id=${id}`, tme: false };
        }
        return null;
    }

    function closeMiniAppAfterOpening() {
        const webApp = window.Telegram?.WebApp;
        if (webApp?.close) {
            window.setTimeout(() => webApp.close(), 120);
        }
    }

    function openTelegramChat(event, telegramLink) {
        if (!telegramLink) { return; }
        event.preventDefault();
        const webApp = window.Telegram?.WebApp;

        if (telegramLink.tme && webApp?.openTelegramLink) {
            webApp.openTelegramLink(telegramLink.href, { force_request: true });
            closeMiniAppAfterOpening();
            return;
        }

        window.location.href = telegramLink.appHref;
        closeMiniAppAfterOpening();
    }

    function telegramIcon() {
        const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
        svg.setAttribute("viewBox", "0 0 24 24");
        svg.setAttribute("aria-hidden", "true");
        svg.setAttribute("focusable", "false");
        const path = document.createElementNS("http://www.w3.org/2000/svg", "path");
        path.setAttribute("d", "M21.9 4.2 18.7 20c-.2 1-.8 1.2-1.6.8l-4.8-3.6-2.3 2.2c-.3.3-.5.5-1 .5l.3-4.9 8.9-8c.4-.3-.1-.5-.6-.2L6.6 13.7 1.9 12.2c-1-.3-1-1 .2-1.4L20.5 3.7c.9-.3 1.6.2 1.4.5Z");
        svg.appendChild(path);
        return svg;
    }

    function telegramAction(u) {
        const telegramLink = telegramUserLink(u);
        if (!telegramLink) { return el("span", { class: "telegram-user-link is-disabled", text: "—" }); }

        const action = el("a", {
            href: telegramLink.href,
            class: "telegram-user-link",
            title: "Открыть чат в Telegram",
            "aria-label": "Открыть чат в Telegram",
        }, telegramIcon());
        action.addEventListener("click", (event) => openTelegramChat(event, telegramLink));
        return action;
    }

    function usernameCell(u) {
        return u.username
            ? el("code", { text: "@" + normalizeUsername(u.username) })
            : el("span", { class: "muted-cell", text: "—" });
    }

    function row(u) {
        const link = el("a", { href: `/dashboard/user/${encodeURIComponent(u.botUserId)}`,
                               text: u.displayName || u.username || `id ${u.botUserId}` });
        return el("tr", null,
            el("td", null, link),
            el("td", null, usernameCell(u)),
            el("td", { class: "telegram-user-cell" }, telegramAction(u)),
            el("td", { text: formatNumber(u.totalExports) }),
            el("td", { text: formatNumber(u.totalMessages) }),
            el("td", { text: formatBytes(u.totalBytes) }),
            el("td", { text: formatDate(u.lastSeen) }));
    }

    function emptyRow(colspan, text) {
        return el("tr", null, el("td", { colSpan: colspan, style: "text-align:center;color:var(--muted)", text }));
    }

    function render(tbody, rows) {
        tbody.replaceChildren(...(rows.length ? rows.map(row) : [emptyRow(7, "Нет данных")]));
    }

    function sortValue(u, key) {
        if (key === "user") { return u.displayName || u.username || String(u.botUserId || ""); }
        return u[key];
    }

    async function load() {
        const tbody = document.getElementById("users-tbody");
        if (!tbody) { return; }
        try {
            const readPeriod = window.Dashboard?.readPeriodFromUrl;
            const { period, from, to } = readPeriod ? readPeriod() : {};
            const params = { limit: 200 };
            if (period) params.period = period;
            if (from) params.from = from;
            if (to) params.to = to;
            const rows = await fetchJson("/dashboard/api/stats/users", params);
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
            tbody.replaceChildren(el("tr", null,
                el("td", { colSpan: 7, style: "color:var(--danger)", text: `Ошибка загрузки: ${e.message}` })));
        }
    }

    onReady(load);
})();
