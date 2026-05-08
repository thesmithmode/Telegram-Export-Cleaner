/**
 * subscriptions.js — страница /dashboard/subscriptions.
 * Загружает список подписок, рендерит таблицу, обрабатывает форму создания,
 * и действия pause/resume/delete через /dashboard/api/subscriptions/*.
 *
 * XSS-защита: все пользовательские данные вставляются через textContent/
 * setAttribute, никогда через innerHTML напрямую.
 */
(function () {
    "use strict";

    const { fetchJson, escapeHtml, setCountBadge, initSortableTable, onReady } = window.Dashboard || {};
    if (!fetchJson) { return; }

    const csrfToken  = document.querySelector('meta[name="_csrf"]')?.content;
    const csrfHeader = document.querySelector('meta[name="_csrf_header"]')?.content;

    // i18n-строки из data-атрибутов, чтобы не дублировать переводы в JS
    const i18nEl = document.getElementById("subs-i18n");
    const I18N = {
        empty:          i18nEl?.dataset.empty         || "Подписок нет",
        conflict:       i18nEl?.dataset.conflict       || "Уже есть активная подписка",
        firstRun:       i18nEl?.dataset.firstRun       || "Ожидается первый запуск",
        inProgress:     i18nEl?.dataset.inProgress     || "Выполняется…",
        statusActive:   i18nEl?.dataset.statusActive   || "Активна",
        statusPaused:   i18nEl?.dataset.statusPaused   || "Приостановлена",
        statusArchived: i18nEl?.dataset.statusArchived || "Архивирована",
        pause:          i18nEl?.dataset.actionPause    || "Пауза",
        resume:         i18nEl?.dataset.actionResume   || "Возобновить",
        delete:         i18nEl?.dataset.actionDelete   || "Удалить",
        period1d:       i18nEl?.dataset.period1d       || "Every day",
        period2d:       i18nEl?.dataset.period2d       || "Every 2 days",
        period3d:       i18nEl?.dataset.period3d       || "Every 3 days",
        period7d:       i18nEl?.dataset.period7d       || "Every week",
    };

    const PAGE_ROOT = document.querySelector(".subscriptions-page");
    const ROLE      = PAGE_ROOT?.dataset.role || "";

    // ── Helpers ────────────────────────────────────────────────────────────────

    /**
     * Конвертирует ISO-строку Instant в МСК (UTC+3) и возвращает HH:mm.
     * Если строка пустая/null — возвращает "—".
     */
    function formatMsk(instant) {
        if (!instant) { return "—"; }
        const d = new Date(instant);
        if (Number.isNaN(d.getTime())) { return "—"; }
        return d.toLocaleTimeString("ru-RU", {
            hour: "2-digit",
            minute: "2-digit",
            timeZone: "Europe/Moscow",
        });
    }

    /**
     * Возвращает дату в МСК: "дд.мм.гггг ЧЧ:мм".
     */
    function formatDateMsk(instant) {
        if (!instant) { return "—"; }
        const d = new Date(instant);
        if (Number.isNaN(d.getTime())) { return "—"; }
        return d.toLocaleString("ru-RU", {
            year: "numeric", month: "2-digit", day: "2-digit",
            hour: "2-digit", minute: "2-digit",
            timeZone: "Europe/Moscow",
        });
    }

    /**
     * Вычисляет следующий запуск локально в браузере.
     * Якорь — последнее событие подписки (success / run / failure), чтобы
     * не показывать "Ожидается первый запуск" пока идёт/провалился первый экспорт.
     */
    const MAX_JOB_MS = 4 * 60 * 60 * 1000; // 4 часа — максимальная длительность экспорта

    function computeNextRun(sub) {
        const success = sub.lastSuccessAt ? new Date(sub.lastSuccessAt).getTime() : null;
        const run     = sub.lastRunAt     ? new Date(sub.lastRunAt).getTime()     : null;
        const failure = sub.lastFailureAt ? new Date(sub.lastFailureAt).getTime() : null;

        // Запуск начат, но terminal-событие не пришло → "Выполняется…"
        // Таймаут 4ч: если lastRunAt слишком давно — terminal событие потеряно, не зависаем.
        if (run && (!success || run > success) && (!failure || run > failure)
                && (Date.now() - run) < MAX_JOB_MS) {
            return I18N.inProgress;
        }

        const anchor = Math.max(success || 0, run || 0, failure || 0);
        if (!anchor) { return I18N.firstRun; }

        const next = new Date(anchor + sub.periodHours * 3600 * 1000);
        return formatDateMsk(next.toISOString());
    }

    function statusInfo(status) {
        if (status === "ACTIVE")   { return { label: I18N.statusActive,   cls: "status-chip--completed" }; }
        if (status === "PAUSED")   { return { label: I18N.statusPaused,   cls: "status-chip--queued" }; }
        if (status === "ARCHIVED") { return { label: I18N.statusArchived, cls: "status-chip--cancelled" }; }
        return { label: status || "", cls: "" };
    }

    function periodLabel(hours) {
        if (hours === 24)  { return I18N.period1d; }
        if (hours === 48)  { return I18N.period2d; }
        if (hours === 72)  { return I18N.period3d; }
        if (hours === 168) { return I18N.period7d; }
        return `${hours}h`;
    }

    /** Создаёт кнопку действия через DOM API (без innerHTML, без XSS). */
    function makeActionBtn(label, cssClass, onClick) {
        const btn = document.createElement("button");
        btn.type = "button";
        btn.className = cssClass;
        btn.textContent = label;
        btn.addEventListener("click", onClick);
        return btn;
    }

    /** Создаёт TD с текстом (XSS-safe через textContent). */
    function td(text) {
        const cell = document.createElement("td");
        cell.textContent = text == null ? "—" : String(text);
        return cell;
    }

    /** Строит строку таблицы через DOM API. */
    function buildRow(sub) {
        const tr = document.createElement("tr");

        tr.appendChild(td(sub.userDisplay || sub.botUserId || "—"));
        tr.appendChild(td(sub.chatDisplay || sub.chatRefId));
        tr.appendChild(td(periodLabel(sub.periodHours)));
        tr.appendChild(td(sub.desiredTimeMsk || "—"));
        tr.appendChild(td(formatDateMsk(sub.sinceDate)));

        // Статус — span с классом
        const stCell = document.createElement("td");
        const st = statusInfo(sub.status);
        const stSpan = document.createElement("span");
        stSpan.className = `status-chip ${st.cls}`;
        stSpan.textContent = st.label;
        stCell.appendChild(stSpan);
        tr.appendChild(stCell);

        tr.appendChild(td(computeNextRun(sub)));

        // Кнопки действий
        const actCell = document.createElement("td");
        actCell.className = "actions-cell";

        if (sub.status === "ACTIVE") {
            actCell.appendChild(makeActionBtn(I18N.pause, "btn btn--sm", () => pauseSubscription(sub.id)));
        }
        if (sub.status === "PAUSED") {
            actCell.appendChild(makeActionBtn(I18N.resume, "btn btn--sm btn--primary", () => resumeSubscription(sub.id)));
        }
        actCell.appendChild(makeActionBtn(I18N.delete, "btn btn--sm btn--danger", () => deleteSubscription(sub.id)));

        tr.appendChild(actCell);
        return tr;
    }

    // ── Render ─────────────────────────────────────────────────────────────────

    function renderRows(tbody, rows) {
        while (tbody.firstChild) { tbody.removeChild(tbody.firstChild); }
        if (!rows || rows.length === 0) {
            const emptyRow = document.createElement("tr");
            const emptyCell = document.createElement("td");
            emptyCell.colSpan = 8;
            emptyCell.style.textAlign = "center";
            emptyCell.style.color = "var(--muted)";
            emptyCell.textContent = I18N.empty;
            emptyRow.appendChild(emptyCell);
            tbody.appendChild(emptyRow);
        } else {
            rows.forEach(sub => tbody.appendChild(buildRow(sub)));
        }
    }

    function renderTable(rows) {
        const tbody = document.getElementById("subscriptions-body");
        if (!tbody) { return; }
        renderRows(tbody, rows);
        setCountBadge("subscriptions", (rows || []).length);
        if (initSortableTable) {
            initSortableTable(document.getElementById("subscriptions-table"), {
                rows: rows || [],
                rerender: (sorted) => renderRows(tbody, sorted),
                getValue: (sub, key) => sub[key],
            });
        }
    }

    // ── API calls ──────────────────────────────────────────────────────────────

    let _pollTimer = null;
    let _inFlight = false;
    // Sequence-id: последний выпущенный fetch выигрывает render. Если polling
    // и forceReload летят параллельно, stale ответ не затрёт свежий.
    let _seqNext = 0;
    let _seqLast = 0;

    function schedulePollingIfNeeded(rows) {
        clearTimeout(_pollTimer);
        const hasInProgress = rows.some(sub =>
            sub.status === "ACTIVE" && computeNextRun(sub) === I18N.inProgress
        );
        if (hasInProgress && !document.hidden) {
            _pollTimer = setTimeout(loadSubscriptions, 30000);
        }
    }

    document.addEventListener("visibilitychange", function () {
        if (!document.hidden) {
            // _inFlight guard: tab flap → не дублировать fetch если предыдущий ещё летит
            if (!_inFlight) loadSubscriptions();
        } else {
            clearTimeout(_pollTimer);
        }
    });

    // После явной мутации (pause/resume/delete/create) — гарантируем что свежий
    // ответ выиграет, даже если polling-fetch уже в полёте: bumped seq инвалидирует
    // старший response.
    async function forceReload() {
        _inFlight = false;
        await loadSubscriptions();
    }

    async function loadSubscriptions() {
        if (_inFlight) return;
        _inFlight = true;
        const mySeq = ++_seqNext;
        try {
            const data = await fetchJson("/dashboard/api/subscriptions");
            // Stale ответ (более ранний seq, чем последний отрендеренный) — выбрасываем.
            if (mySeq < _seqLast) return;
            _seqLast = mySeq;
            const rows = Array.isArray(data) ? data : (data.content || []);
            renderTable(rows);
            schedulePollingIfNeeded(rows);
        } catch (e) {
            if (mySeq < _seqLast) return;
            _seqLast = mySeq;
            const tbody = document.getElementById("subscriptions-body");
            if (tbody) {
                while (tbody.firstChild) { tbody.removeChild(tbody.firstChild); }
                const errRow = document.createElement("tr");
                const errCell = document.createElement("td");
                errCell.colSpan = 8;
                errCell.style.color = "var(--danger)";
                errCell.textContent = `Ошибка загрузки: ${e.message}`;
                errRow.appendChild(errCell);
                tbody.appendChild(errRow);
            }
        } finally {
            _inFlight = false;
        }
    }

    function tgConfirm(msg) {
        const twa = window.Telegram?.WebApp;
        if (twa?.showConfirm) {
            return new Promise(resolve => twa.showConfirm(msg, ok => resolve(ok)));
        }
        return Promise.resolve(window.confirm(msg));
    }

    function tgAlert(msg) {
        const twa = window.Telegram?.WebApp;
        if (twa?.showAlert) {
            return new Promise(resolve => twa.showAlert(msg, resolve));
        }
        window.alert(msg);
        return Promise.resolve();
    }

    async function mutate(method, url) {
        const headers = { "Content-Type": "application/json", "Accept": "application/json" };
        if (csrfToken && csrfHeader) { headers[csrfHeader] = csrfToken; }
        const res = await fetch(url, { method, credentials: "same-origin", headers });
        if (res.status === 401 || res.status === 403) {
            window.location.href = "/dashboard/login";
            throw new Error("Unauthorized");
        }
        if (!res.ok) {
            const body = await res.json().catch(() => ({}));
            throw new Error(body.message || `HTTP ${res.status}`);
        }
        return res;
    }

    async function pauseSubscription(id) {
        if (!await tgConfirm(`Приостановить подписку #${id}?`)) { return; }
        try {
            await mutate("PATCH", `/dashboard/api/subscriptions/${id}/pause`);
            await forceReload();
        } catch (e) {
            await tgAlert(`Ошибка: ${e.message}`);
        }
    }

    async function resumeSubscription(id) {
        if (!await tgConfirm(`Возобновить подписку #${id}?`)) { return; }
        try {
            await mutate("PATCH", `/dashboard/api/subscriptions/${id}/resume`);
            await forceReload();
        } catch (e) {
            await tgAlert(`Ошибка: ${e.message}`);
        }
    }

    async function deleteSubscription(id) {
        if (!await tgConfirm(`Удалить подписку #${id}? Это действие нельзя отменить.`)) { return; }
        try {
            await mutate("DELETE", `/dashboard/api/subscriptions/${id}`);
            await forceReload();
        } catch (e) {
            await tgAlert(`Ошибка: ${e.message}`);
        }
    }

    // ── Form ───────────────────────────────────────────────────────────────────

    function showError(el, message) {
        if (!el) { return; }
        el.textContent = message;
        el.hidden = false;
    }

    async function createSubscription(event) {
        event.preventDefault();
        const form      = event.target;
        const errorEl   = document.getElementById("create-error");
        const submitBtn = form.querySelector("[type=submit]");

        if (errorEl) { errorEl.hidden = true; }

        const chatIdentifier = form.querySelector("[name=chatIdentifier]")?.value?.trim();
        const periodHoursEl  = form.querySelector("[name=periodHours]:checked");
        const timeMsk        = form.querySelector("[name=desiredTimeMsk]")?.value?.trim();
        const sinceDateRaw   = form.querySelector("[name=sinceDate]")?.value?.trim();

        // Базовая клиентская валидация
        if (!chatIdentifier || !periodHoursEl || !timeMsk) {
            showError(errorEl, "Заполните все обязательные поля");
            return;
        }

        const periodHours = parseInt(periodHoursEl.value, 10);

        // Конвертируем datetime-local → ISO-8601 Instant (UTC).
        // Пустое поле — null, сервер подставит текущее время.
        let sinceDate = null;
        if (sinceDateRaw && sinceDateRaw.trim()) {
            const d = new Date(sinceDateRaw);
            if (!Number.isNaN(d.getTime())) {
                sinceDate = d.toISOString();
            }
        }

        const payload = { chatIdentifier, periodHours, desiredTimeMsk: timeMsk, sinceDate };
        const headers = { "Content-Type": "application/json", "Accept": "application/json" };
        if (csrfToken && csrfHeader) { headers[csrfHeader] = csrfToken; }

        if (submitBtn) { submitBtn.disabled = true; }
        try {
            const res = await fetch("/dashboard/api/subscriptions", {
                method: "POST",
                credentials: "same-origin",
                headers,
                body: JSON.stringify(payload),
            });

            if (res.status === 401 || res.status === 403) {
                window.location.href = "/dashboard/login";
                return;
            }
            if (res.status === 409) {
                showError(errorEl, I18N.conflict);
                return;
            }
            if (res.status === 400) {
                const body = await res.json().catch(() => ({}));
                showError(errorEl, body.message || "Ошибка валидации");
                return;
            }
            if (!res.ok) {
                showError(errorEl, `Ошибка сервера: HTTP ${res.status}`);
                return;
            }

            // Успех — сбрасываем форму и перезагружаем таблицу
            form.reset();
            await forceReload();
        } catch (e) {
            showError(errorEl, `Сетевая ошибка: ${e.message}`);
        } finally {
            if (submitBtn) { submitBtn.disabled = false; }
        }
    }

    // ── Init ───────────────────────────────────────────────────────────────────

    function init() {
        const form = document.getElementById("create-subscription-form");
        if (form && ROLE === "USER") {
            form.addEventListener("submit", createSubscription);
        }
        loadSubscriptions();
    }

    onReady(init);
})();
