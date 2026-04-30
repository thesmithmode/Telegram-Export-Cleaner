(function () {
    "use strict";

    const { fetchJson, formatNumber, formatBytes, readPeriodFromUrl,
            renderBarChart, setCountBadge, initSortableTable, onReady } = window.Dashboard || {};
    if (!fetchJson) { return; }

    function el(tag, props, ...children) {
        const node = document.createElement(tag);
        if (props) {
            for (const [k, v] of Object.entries(props)) {
                if (k === "style") { node.style.cssText = v; }
                else if (k === "text") { node.textContent = v; }
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

    function row(c) {
        const nameNode = c.chatTitle
            ? document.createTextNode(c.chatTitle)
            : el("code", { text: String(c.canonicalChatId) });
        return el("tr", null,
            el("td", null, nameNode),
            el("td", { text: formatNumber(c.exportCount) }),
            el("td", { text: formatNumber(c.totalMessages) }),
            el("td", { text: formatBytes(c.totalBytes) }));
    }

    function emptyRow(text) {
        return el("tr", null,
            el("td", { colSpan: 4, style: "text-align:center;color:var(--muted)", text }));
    }

    function render(tbody, rows) {
        tbody.replaceChildren(...(rows.length ? rows.map(row) : [emptyRow("Нет данных")]));
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
                tbody.replaceChildren(el("tr", null,
                    el("td", { colSpan: 4, style: "color:var(--danger)", text: `Ошибка: ${e.message}` })));
            }
        }
    }

    onReady(load);
})();
