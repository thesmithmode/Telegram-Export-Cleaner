/**
 * Страница "О проекте": форма feedback + копирование TON-адреса.
 * UI-текст берётся из data-атрибутов шаблона (локализация централизована в i18n).
 */
(function () {
    "use strict";

    const MAX = 2000;
    const ENDPOINT = "/dashboard/api/me/feedback";

    function updateCounter(textarea, counter) {
        const len = textarea.value.length;
        counter.textContent = `${len} / ${MAX}`;
        counter.classList.toggle("is-over", len > MAX);
    }

    function showStatus(el, kind, text) {
        el.classList.remove("is-ok", "is-err");
        if (!kind) { el.textContent = ""; return; }
        el.classList.add(kind === "ok" ? "is-ok" : "is-err");
        el.textContent = text;
    }

    function statusTextFor(res, i18n) {
        if (res.status === 204) return { kind: "ok",  text: i18n.sent };
        if (res.status === 429) return { kind: "err", text: i18n.rateLimit };
        if (res.status === 400) return { kind: "err", text: i18n.invalid };
        return { kind: "err", text: i18n.error };
    }

    function initFeedbackForm() {
        const form = document.getElementById("feedback-form");
        if (!form) return;
        const textarea = document.getElementById("feedback-message");
        const counter = document.getElementById("feedback-counter");
        const submit = document.getElementById("feedback-submit");
        const statusEl = document.getElementById("feedback-status");

        const i18n = {
            sent: statusEl.dataset.sent || "Sent",
            rateLimit: statusEl.dataset.rateLimit || "Too many requests",
            invalid: statusEl.dataset.invalid || "Invalid message",
            error: statusEl.dataset.error || "Error",
        };

        textarea.addEventListener("input", () => updateCounter(textarea, counter));
        updateCounter(textarea, counter);

        form.addEventListener("submit", async (e) => {
            e.preventDefault();
            const message = textarea.value.trim();
            if (!message || message.length > MAX) {
                showStatus(statusEl, "err", i18n.invalid);
                return;
            }
            submit.disabled = true;
            showStatus(statusEl, null);
            try {
                const res = await window.Dashboard.fetchPost(ENDPOINT, { message });
                const s = statusTextFor(res, i18n);
                showStatus(statusEl, s.kind, s.text);
                if (res.status === 204) {
                    textarea.value = "";
                    updateCounter(textarea, counter);
                }
            } catch (err) {
                showStatus(statusEl, "err", i18n.error);
            } finally {
                submit.disabled = false;
            }
        });
    }

    function initCopy() {
        const btn = document.getElementById("donate-copy");
        const input = document.getElementById("donate-ton");
        if (!btn || !input) return;
        btn.addEventListener("click", async () => {
            const label = btn.dataset.copy || btn.textContent;
            const copied = btn.dataset.copied || "Copied";
            try {
                if (navigator.clipboard && window.isSecureContext) {
                    await navigator.clipboard.writeText(input.value);
                } else {
                    input.select();
                    document.execCommand("copy");
                    input.blur();
                }
                btn.textContent = copied;
                setTimeout(() => { btn.textContent = label; }, 1600);
            } catch (err) {
                console.error("copy failed:", err);
            }
        });
    }

    window.Dashboard.onReady(() => {
        initFeedbackForm();
        initCopy();
    });
})();
