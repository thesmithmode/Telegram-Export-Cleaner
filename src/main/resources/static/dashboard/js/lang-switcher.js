/**
 * Переключатель языка UI. POST /dashboard/api/me/settings/language →
 * BotUser.language → LocaleResolver на следующем запросе → Thymeleaf перерендерит.
 */
(function () {
    "use strict";

    document.addEventListener("DOMContentLoaded", function () {
        const select = document.getElementById("lang-switch");
        if (!select) return;

        select.addEventListener("change", async function (event) {
            const code = event.target.value;

            // Защита от race при быстрых кликах: два параллельных POST → два reload,
            // последний может проиграть актуальное значение.
            select.disabled = true;

            try {
                const res = await window.Dashboard.fetchPost(
                    "/dashboard/api/me/settings/language", { language: code });
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
