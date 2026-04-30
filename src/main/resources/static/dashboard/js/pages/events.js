(function () {
    "use strict";

    const { fetchJson, formatNumber, formatBytes, formatDate,
            setCountBadge, initSortableTable, onReady } = window.Dashboard || {};
    if (!fetchJson) { return; }

    const STATUS_CLASS = {
        completed: "status-chip--completed",
        failed: "status-chip--failed",
        processing: "status-chip--processing",
        queued: "status-chip--queued",
        cancelled: "status-chip--cancelled",
    };

    function el(tag, props, ...children) {
        const node = document.createElement(tag);
        if (props) {
            for (const [k, v] of Object.entries(props)) {
                if (k === "style") { node.style.cssText = v; }
                else if (k === "text") { node.textContent = v; }
                else if (k === "class") { node.className = v; }
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

    function chip(status) {
        const cls = STATUS_CLASS[status] || "";
        return el("span", { class: `status-chip ${cls}`, text: String(status ?? "") });
    }

    function userCell(e) {
        if (e.username) return el("td", null, el("code", { text: "@" + e.username }));
        if (e.botUserId) return el("td", null, el("code", { text: String(e.botUserId) }));
        return el("td", { text: "—" });
    }

    function chatCell(e) {
        if (e.chatTitle) return el("td", { text: e.chatTitle });
        if (e.canonicalChatId) return el("td", null, el("code", { text: String(e.canonicalChatId) }));
        return el("td", { text: "—" });
    }

    function row(e) {
        return el("tr", null,
            userCell(e),
            chatCell(e),
            el("td", null, chip(e.status)),
            el("td", { text: formatNumber(e.messagesCount) }),
            el("td", { text: formatBytes(e.bytesCount) }),
            el("td", { text: formatDate(e.startedAt) }));
    }

    function emptyRow(text) {
        return el("tr", null,
            el("td", { colSpan: 6, style: "text-align:center;color:var(--muted)", text }));
    }

    function render(tbody, rows) {
        tbody.replaceChildren(...(rows.length ? rows.map(row) : [emptyRow("Нет данных")]));
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
            tbody.replaceChildren(el("tr", null,
                el("td", { colSpan: 6, style: "color:var(--danger)", text: `Ошибка: ${e.message}` })));
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
