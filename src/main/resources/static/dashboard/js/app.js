/**
 * Dashboard — общий фронтенд-helper.
 * Содержит:
 *   - fetchJson(path, params) — GET /dashboard/api/... с CSRF-заголовком и ошибками.
 *   - formatBytes / formatNumber — форматирование для таблиц и KPI.
 *   - активация кнопок period-filter (без перезагрузки страницы).
 */
(function () {
    "use strict";

    const csrfToken = document.querySelector('meta[name="_csrf"]')?.content;
    const csrfHeader = document.querySelector('meta[name="_csrf_header"]')?.content;

    /** Строит URL с query-параметрами, фильтрует null/undefined. */
    function buildUrl(path, params) {
        const url = new URL(path, window.location.origin);
        if (params) {
            Object.entries(params).forEach(([k, v]) => {
                if (v !== null && v !== undefined && v !== "") url.searchParams.set(k, v);
            });
        }
        return url.toString();
    }

    /** GET /dashboard/api/... → JSON. Бросает ошибку при не-2xx. */
    async function fetchJson(path, params) {
        const headers = { "Accept": "application/json" };
        if (csrfToken && csrfHeader) headers[csrfHeader] = csrfToken;
        const res = await fetch(buildUrl(path, params), {
            method: "GET",
            credentials: "same-origin",
            headers,
        });
        if (res.status === 401 || res.status === 403) {
            window.location.href = "/dashboard/login";
            throw new Error("Unauthorized");
        }
        if (!res.ok) {
            const body = await res.json().catch(() => ({}));
            throw new Error(body.message || `HTTP ${res.status}`);
        }
        return res.json();
    }

    function formatNumber(n) {
        if (n === null || n === undefined) return "—";
        return Number(n).toLocaleString("ru-RU");
    }

    function formatBytes(bytes) {
        if (!bytes) return "0 B";
        const units = ["B", "KB", "MB", "GB", "TB"];
        let i = 0;
        let n = Number(bytes);
        while (n >= 1024 && i < units.length - 1) { n /= 1024; i++; }
        return `${n.toFixed(i === 0 ? 0 : 1)} ${units[i]}`;
    }

    function formatDate(iso) {
        if (!iso) return "—";
        const d = new Date(iso);
        if (Number.isNaN(d.getTime())) return iso;
        return d.toLocaleString("ru-RU", {
            year: "numeric", month: "2-digit", day: "2-digit",
            hour: "2-digit", minute: "2-digit",
        });
    }

    /** Период из URL (period=, from=, to=, userId=). */
    function readPeriodFromUrl() {
        const qs = new URLSearchParams(window.location.search);
        return {
            period: qs.get("period") || "month",
            from: qs.get("from"),
            to: qs.get("to"),
            userId: qs.get("userId"),
        };
    }

    /** Подсветка активной кнопки periodFilter + перезагрузка на клик. */
    function initPeriodFilter() {
        const buttons = document.querySelectorAll("[data-period]");
        const current = readPeriodFromUrl();
        buttons.forEach(btn => {
            if (btn.dataset.period === current.period) btn.classList.add("is-active");
            btn.addEventListener("click", () => {
                const qs = new URLSearchParams(window.location.search);
                qs.set("period", btn.dataset.period);
                qs.delete("from"); qs.delete("to");
                window.location.search = qs.toString();
            });
        });
    }

    /** Экранирование HTML-спецсимволов для защиты от XSS. */
    function escapeHtml(str) {
        if (str === null || str === undefined) return "";
        return String(str)
            .replace(/&/g, "&amp;")
            .replace(/</g, "&lt;")
            .replace(/>/g, "&gt;")
            .replace(/"/g, "&quot;")
            .replace(/'/g, "&#039;");
    }

    window.Dashboard = {
        fetchJson, formatNumber, formatBytes, formatDate,
        readPeriodFromUrl, escapeHtml,
    };

    document.addEventListener("DOMContentLoaded", () => {
        initPeriodFilter();
    });
})();
