/**
 * Переключатель языка UI. Отправляет POST /dashboard/api/me/settings/language
 * с CSRF-токеном и перезагружает страницу: LocaleResolver подхватит новый
 * BotUser.language на следующем запросе, Thymeleaf перерендерит с нужной локалью.
 */
(function () {
    "use strict";

    document.addEventListener("DOMContentLoaded", function () {
        const select = document.getElementById("lang-switch");
        if (!select) return;

        select.addEventListener("change", async function (event) {
            const code = event.target.value;
            const csrfToken = document.querySelector('meta[name="_csrf"]')?.content;
            const csrfHeader = document.querySelector('meta[name="_csrf_header"]')?.content;
            const headers = { "Content-Type": "application/json", "Accept": "application/json" };
            if (csrfToken && csrfHeader) headers[csrfHeader] = csrfToken;

            // Disable во время запроса: защита от race при быстрых кликах
            // (два параллельных POST → два reload → последний может проиграть).
            select.disabled = true;

            try {
                const res = await fetch("/dashboard/api/me/settings/language", {
                    method: "POST",
                    credentials: "same-origin",
                    headers,
                    body: JSON.stringify({ language: code }),
                });
                if (res.status === 401 || res.status === 403) {
                    window.location.href = "/dashboard/login";
                    return;
                }
                if (!res.ok) {
                    console.warn("language update failed:", res.status);
                    select.disabled = false;
                    return;
                }
                window.location.reload();
            } catch (err) {
                console.error("language switch error:", err);
                select.disabled = false;
            }
        });
    });
})();
