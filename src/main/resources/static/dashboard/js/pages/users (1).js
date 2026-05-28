(function () {
    "use strict";

    const { fetchJson, formatNumber, formatBytes, formatDate,
            setCountBadge, initSortableTable, onReady } = window.Dashboard || {};
    if (!fetchJson) { return; }

    function el(tag, props, ...children) {
        const node = document.createElement(tag);
        if (props) {
            for (const [k, v] of Object.entries(props)) {
                if (k === "style") { node.style.cssText = v; }
                else if (k === "text") { node.textContent = v; }
                else if (k === "href") { node.href = v; }
                else if (k === "colSpan") { node.colSpan = v; }
                else { node.setAttribute(k, v); }
            }
        }
        for (const c of children) {
            if (c == null || c === false) continue;
            node.appendChild(typeof c === "string" ? document.createTextNode(c) : c);
        }
        return node;
    }

    function row(u) {
        const link = el("a", { href: `/dashboard/user/${encodeURIComponent(u.botUserId)}`,
                               text: u.displayName || u.username || `id ${u.botUserId}` });
        const small = u.username
            ? el("small", { style: "color:var(--muted)" }, " ", el("code", { text: "@" + u.username }))
            : null;
        return el("tr", null,
            el("td", null, link, small),
            el("td", { text: formatNumber(u.totalExports) }),
            el("td", { text: formatNumber(u.totalMessages) }),
            el("td", { text: formatBytes(u.totalBytes) }),
            el("td", { text: formatDate(u.lastSeen) }));
    }

    function emptyRow(colspan, text) {
        return el("tr", null, el("td", { colSpan: colspan, style: "text-align:center;color:var(--muted)", text }));
    }

    function render(tbody, rows) {
        tbody.replaceChildren(...(rows.length ? rows.map(row) : [emptyRow(5, "Нет данных")]));
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
                el("td", { colSpan: 5, style: "color:var(--danger)", text: `Ошибка загрузки: ${e.message}` })));
        }
    }

    onReady(load);
})();
