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

    /** GET /dashboard/api/... → JSON. Бросает ошибку при не-2xx. Принимает опциональный signal для AbortController. */
    async function fetchJson(path, params, signal) {
        const headers = { "Accept": "application/json" };
        if (csrfToken && csrfHeader) headers[csrfHeader] = csrfToken;
        const res = await fetch(buildUrl(path, params), {
            method: "GET",
            credentials: "same-origin",
            headers,
            signal,
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

    /**
     * POST /dashboard/api/... JSON-body + CSRF. 401/403 → редирект на login.
     * Возвращает Response — вызывающий сам решает как интерпретировать статус
     * (204, 429, 503 — все валидные исходы feedback-формы).
     */
    async function fetchPost(path, body) {
        const headers = { "Content-Type": "application/json", "Accept": "application/json" };
        if (csrfToken && csrfHeader) headers[csrfHeader] = csrfToken;
        const res = await fetch(path, {
            method: "POST",
            credentials: "same-origin",
            headers,
            body: body === undefined ? undefined : JSON.stringify(body),
        });
        if (res.status === 401 || res.status === 403) {
            window.location.href = "/dashboard/login";
            throw new Error("Unauthorized");
        }
        return res;
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
        // Фиксированная МСК (UTC+3) независимо от зоны браузера — дашборд админа ожидает МСК.
        return d.toLocaleString("ru-RU", {
            year: "numeric", month: "2-digit", day: "2-digit",
            hour: "2-digit", minute: "2-digit",
            timeZone: "Europe/Moscow",
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

    function makeCanvas(id) {
        const el = document.getElementById(id);
        if (!el) { return null; }
        const h = Number(el.dataset.height) || 300;
        const wrap = el.closest('.chart-block__canvas-wrap');
        if (wrap) { wrap.style.height = `${h}px`; }
        return el.getContext("2d");
    }

    function setKpi(name, value) {
        const el = document.querySelector(`[data-kpi="${name}"]`);
        if (el) { el.textContent = value; }
    }

    /**
     * Заполняет .kpi__delta рядом со значением. delta — число (абсолют или процент).
     * opts.kind: "percent" (default) | "number" — формат, направление по знаку.
     * Если delta === null/undefined — очищает (через :empty элемент скрывается).
     */
    function setKpiDelta(name, delta, opts) {
        const el = document.querySelector(`[data-kpi-delta="${name}"]`);
        if (!el) { return; }
        el.classList.remove("up", "down", "flat");
        if (delta === null || delta === undefined || Number.isNaN(Number(delta))) {
            el.textContent = "";
            return;
        }
        const n = Number(delta);
        const kind = (opts && opts.kind) || "percent";
        const sign = n > 0 ? "↑" : (n < 0 ? "↓" : "—");
        const cls = n > 0 ? "up" : (n < 0 ? "down" : "flat");
        el.classList.add(cls);
        const abs = Math.abs(n);
        const formatted = kind === "percent"
            ? `${abs.toFixed(abs >= 10 ? 0 : 1)}%`
            : formatNumber(Math.round(abs));
        el.textContent = `${sign} ${formatted}`;
    }

    /**
     * Заполняет .kpi__meta парами label/value.
     * pairs — [{label:"/сут", value:"417"}, {label:"pk", value:"821"}]
     * Строится безопасно через textContent (без innerHTML).
     */
    function setKpiMeta(name, pairs) {
        const el = document.querySelector(`[data-kpi-meta="${name}"]`);
        if (!el) { return; }
        while (el.firstChild) { el.removeChild(el.firstChild); }
        if (!Array.isArray(pairs) || pairs.length === 0) { return; }
        pairs.forEach((p, i) => {
            if (i > 0) {
                const sep = document.createElement("span");
                sep.className = "sep";
                sep.textContent = "·";
                el.appendChild(sep);
            }
            if (p.value !== null && p.value !== undefined && p.value !== "") {
                const b = document.createElement("b");
                b.textContent = String(p.value);
                el.appendChild(b);
            }
            if (p.label) {
                el.appendChild(document.createTextNode(` ${p.label}`));
            }
        });
    }

    /**
     * Рисует sparkline в SVG-элементе.
     * svgEl — <svg viewBox="0 0 60 22">, values — number[].
     * Пустой/короткий массив → SVG очищается (graceful).
     */
    function renderSparkline(svgEl, values) {
        if (!svgEl) { return; }
        while (svgEl.firstChild) { svgEl.removeChild(svgEl.firstChild); }
        if (!Array.isArray(values) || values.length < 2) { return; }
        const w = 60, h = 22;
        const min = Math.min(...values);
        const max = Math.max(...values);
        const range = max - min || 1;
        const step = w / (values.length - 1);
        const coords = values.map((v, i) => {
            const x = i * step;
            const y = h - ((v - min) / range) * (h - 2) - 1;
            return [x, y];
        });
        const lineD = coords.map((p, i) => `${i === 0 ? "M" : "L"}${p[0].toFixed(2)},${p[1].toFixed(2)}`).join(" ");
        const areaD = `${lineD} L${w},${h} L0,${h} Z`;
        const NS = "http://www.w3.org/2000/svg";
        const area = document.createElementNS(NS, "path");
        area.setAttribute("class", "area");
        area.setAttribute("d", areaD);
        svgEl.appendChild(area);
        const line = document.createElementNS(NS, "path");
        line.setAttribute("class", "line");
        line.setAttribute("d", lineD);
        svgEl.appendChild(line);
    }

    /** Медиана без мутации входа. */
    function median(arr) {
        if (!arr.length) { return 0; }
        const sorted = [...arr].sort((a, b) => a - b);
        const mid = Math.floor(sorted.length / 2);
        return sorted.length % 2 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2;
    }

    /**
     * Рисует stats-bar под канвасом.
     * containerId — id контейнера (.chart-stats).
     * points — массив {period, value} или массив чисел.
     * opts.valueFormatter — fn(n) → string (default formatNumber).
     */
    function renderStatsBar(containerId, points, opts) {
        const el = document.getElementById(containerId);
        if (!el) { return; }
        while (el.firstChild) { el.removeChild(el.firstChild); }
        if (!Array.isArray(points) || points.length === 0) { return; }
        const values = points.map(p => Number(typeof p === "object" ? p.value : p) || 0);
        const fmt = (opts && opts.valueFormatter) || formatNumber;
        const sum = values.reduce((a, b) => a + b, 0);
        const avg = sum / values.length;
        const peak = Math.max(...values);
        const low = Math.min(...values);
        const med = median(values);
        const items = [
            { label: "Σ всего", value: fmt(sum) },
            { label: "среднее", value: fmt(Math.round(avg)) },
            { label: "медиана", value: fmt(Math.round(med)) },
            { label: "пик", value: fmt(peak) },
            { label: "мин", value: fmt(low) },
            { label: "точек", value: formatNumber(values.length) },
        ];
        for (const it of items) {
            const wrap = document.createElement("div");
            wrap.className = "chart-stats__item";
            const lbl = document.createElement("div");
            lbl.className = "chart-stats__label";
            lbl.textContent = it.label;
            const val = document.createElement("div");
            val.className = "chart-stats__value";
            val.textContent = it.value;
            wrap.appendChild(lbl);
            wrap.appendChild(val);
            el.appendChild(wrap);
        }
    }

    /** Заполняет .count-badge[data-count-target="name"]. */
    function setCountBadge(target, count) {
        const el = document.querySelector(`[data-count-target="${target}"]`);
        if (!el) { return; }
        if (count === null || count === undefined) { el.textContent = ""; return; }
        el.textContent = formatNumber(count);
    }

    function renderTimeseries(canvasId, points) {
        const ctx = makeCanvas(canvasId);
        if (!ctx || !window.Chart) { return; }
        new Chart(ctx, {
            type: "line",
            data: {
                labels: points.map(p => p.period),
                datasets: [{
                    label: "exports",
                    data: points.map(p => p.value),
                    borderColor: "#2563eb",
                    backgroundColor: "rgba(37,99,235,.12)",
                    fill: true, tension: 0.25,
                }],
            },
            options: { responsive: true, maintainAspectRatio: false,
                scales: { y: { beginAtZero: true, ticks: { precision: 0 } } } },
        });
    }

    function renderBarChart(canvasId, items, { labelFn, valueFn, label, color, tickFn }) {
        // Filter out items where label is empty/null — they produce invisible bars
        // but still affect the X-axis scale, making real bars look tiny.
        const validItems = items.filter(item => {
            const lbl = labelFn(item);
            return lbl != null && String(lbl).trim() !== '';
        });
        const ctx = makeCanvas(canvasId);
        if (!ctx || !window.Chart) { return; }
        // Dynamic height: 28px per bar so labels are evenly spaced.
        const dynH = Math.max(220, validItems.length * 28 + 48);
        const wrap = ctx.canvas.closest('.chart-block__canvas-wrap');
        if (wrap) { wrap.style.height = dynH + 'px'; }
        new Chart(ctx, {
            type: "bar",
            data: {
                labels: validItems.map(labelFn),
                datasets: [{ label, data: validItems.map(valueFn), backgroundColor: color, barThickness: 16 }],
            },
            options: {
                responsive: true, maintainAspectRatio: false, indexAxis: "y",
                scales: {
                    x: { beginAtZero: true, ticks: tickFn ? { callback: tickFn } : { precision: 0 } },
                    y: { ticks: { autoSkip: false } },
                },
            },
        });
    }

    function onReady(fn) {
        if (document.readyState === "loading") {
            document.addEventListener("DOMContentLoaded", fn);
        } else {
            fn();
        }
    }

    const STATUS_COLORS = {
        completed: "#2b8a3e",
        failed: "#d64545",
        processing: "#b7791f",
        queued: "#3358d4",
        cancelled: "#666e7b",
    };

    function renderStatusDoughnut(canvasId, breakdown) {
        const ctx = makeCanvas(canvasId);
        if (!ctx || !window.Chart) { return; }
        const labels = Object.keys(breakdown || {});
        new Chart(ctx, {
            type: "doughnut",
            data: {
                labels,
                datasets: [{
                    data: labels.map(k => breakdown[k]),
                    backgroundColor: labels.map(k => STATUS_COLORS[String(k).toLowerCase()] || "#888"),
                }],
            },
            options: { responsive: true, maintainAspectRatio: false },
        });
    }

    function renderKpiSparkline(name, points) {
        const svg = document.querySelector(`[data-kpi-spark="${name}"]`);
        if (!svg || !Array.isArray(points)) { return; }
        renderSparkline(svg, points.map(p => Number(p.value) || 0));
    }

    /**
     * Client-side сортировка таблицы по клику на <th data-sort-key="...">.
     * Тройной цикл: default → desc → asc → default.
     *
     * opts:
     *   rows      — массив DTO (исходный порядок сохраняется).
     *   rerender  — fn(sortedRows) перерендер tbody.
     *   getValue  — fn(row, key) → значение для сортировки (string|number|null).
     *               По умолчанию row[key].
     */
    function initSortableTable(tableEl, opts) {
        if (!tableEl || !opts || !Array.isArray(opts.rows) || typeof opts.rerender !== "function") { return; }
        if (!tableEl.tHead) { return; }
        // Клонируем <th> чтобы сбросить listeners от предыдущей инициализации
        // (например в /dashboard/events при смене фильтра статуса load() вызывается повторно).
        Array.from(tableEl.tHead.querySelectorAll("th[data-sort-key]")).forEach(th => {
            th.parentNode.replaceChild(th.cloneNode(true), th);
        });
        const headers = tableEl.tHead.querySelectorAll("th[data-sort-key]");
        if (!headers.length) { return; }

        const original = opts.rows.slice();
        const getValue = typeof opts.getValue === "function"
            ? opts.getValue
            : (row, key) => (row == null ? null : row[key]);
        const state = { key: null, dir: null };

        function compare(a, b, key, type) {
            const va = getValue(a, key);
            const vb = getValue(b, key);
            const aNull = va === null || va === undefined || va === "";
            const bNull = vb === null || vb === undefined || vb === "";
            if (aNull && bNull) { return 0; }
            if (aNull) { return 1; }
            if (bNull) { return -1; }
            if (type === "number") {
                const na = Number(va);
                const nb = Number(vb);
                if (Number.isNaN(na) && Number.isNaN(nb)) { return 0; }
                if (Number.isNaN(na)) { return 1; }
                if (Number.isNaN(nb)) { return -1; }
                return na - nb;
            }
            if (type === "date") {
                return String(va).localeCompare(String(vb));
            }
            return String(va).localeCompare(String(vb), "ru");
        }

        function applyIndicators() {
            headers.forEach(th => {
                const key = th.dataset.sortKey;
                th.classList.remove("is-sort-asc", "is-sort-desc");
                if (state.key === key && state.dir) {
                    th.classList.add(state.dir === "asc" ? "is-sort-asc" : "is-sort-desc");
                    th.setAttribute("aria-sort", state.dir === "asc" ? "ascending" : "descending");
                } else {
                    th.setAttribute("aria-sort", "none");
                }
            });
        }

        function render() {
            if (!state.key || !state.dir) {
                opts.rerender(original.slice());
                return;
            }
            const type = (Array.from(headers).find(th => th.dataset.sortKey === state.key) || {})
                .dataset?.sortType || "string";
            const sorted = original.slice().sort((a, b) => {
                const c = compare(a, b, state.key, type);
                return state.dir === "asc" ? c : -c;
            });
            opts.rerender(sorted);
        }

        applyIndicators();
        headers.forEach(th => {
            th.addEventListener("click", () => {
                const key = th.dataset.sortKey;
                if (state.key !== key) {
                    state.key = key;
                    state.dir = "desc";
                } else if (state.dir === "desc") {
                    state.dir = "asc";
                } else {
                    state.key = null;
                    state.dir = null;
                }
                applyIndicators();
                render();
            });
        });
    }

    window.Dashboard = {
        fetchJson, fetchPost, formatNumber, formatBytes, formatDate,
        readPeriodFromUrl, escapeHtml,
        makeCanvas, setKpi, setKpiDelta, setKpiMeta,
        renderSparkline, renderStatsBar, setCountBadge,
        renderStatusDoughnut, renderKpiSparkline,
        renderTimeseries, renderBarChart, onReady,
        initSortableTable,
    };

    // bfcache restore → форсируем свежий запрос с актуальной JSESSIONID.
    window.addEventListener("pageshow", (e) => {
        if (e.persisted) { window.location.reload(); }
    });

    onReady(initPeriodFilter);
})();
