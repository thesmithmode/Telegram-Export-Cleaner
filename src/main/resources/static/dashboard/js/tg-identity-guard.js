/**
 * Identity guard для Telegram Mini App.
 *
 * Бэк ставит readable cookie tg_uid=<telegram user.id> на POST /login/telegram.
 * На каждой загрузке страницы дашборда сравниваем cookie с текущим
 * Telegram.WebApp.initDataUnsafe.user.id. Mismatch (или cookie отсутствует
 * при наличии initData) → форсим re-login через скрытую форму.
 *
 * Закрывает дыру переиспользования WebView в Telegram attachment menu:
 * старая JSESSIONID юзера A остаётся в WebView → без этого guard юзер B
 * увидел бы дашборд юзера A.
 *
 * Подключается в layout.html ДО app.js и работает синхронно (блокирующе)
 * чтобы дочерние скрипты не успели сделать запросы со старой сессией.
 */
(function () {
    "use strict";

    const tg = window.Telegram && window.Telegram.WebApp;
    // Не в Telegram WebView / initData недоступен — ничего не делаем.
    // На десктопе/обычном браузере юзер логинится стандартным flow.
    if (!tg || !tg.initData || !tg.initDataUnsafe || !tg.initDataUnsafe.user) {
        return;
    }

    const currentTgId = String(tg.initDataUnsafe.user.id || "");
    if (!currentTgId) return;

    const cookieTgId = readCookie("tg_uid");

    // Identity совпадает → сессия валидна. Чистим sessionStorage-флаг
    // попытки re-login чтобы не блокировать будущие смены identity.
    if (cookieTgId === currentTgId) {
        try { sessionStorage.removeItem("tg_identity_guard_attempted"); } catch (e) { /* ignore */ }
        return;
    }

    // Loop-guard: если для этого же tgId re-login уже пробовали в текущем
    // запуске WebView и он не поставил cookie (бэк вернул ?error=invalid
    // из-за протухшего auth_date или broken initData) — не зацикливаемся.
    // Используем sessionStorage с fallback на in-memory переменную (private mode).
    const ATTEMPT_KEY = "tg_identity_guard_attempted";
    try {
        if (sessionStorage.getItem(ATTEMPT_KEY) === currentTgId) return;
        sessionStorage.setItem(ATTEMPT_KEY, currentTgId);
    } catch (e) {
        // sessionStorage недоступен (приватный режим) → используем in-memory fallback.
        if (window._tgGuardAttempted === currentTgId) return;
        window._tgGuardAttempted = currentTgId;
    }

    // Mismatch или нет cookie → форсим re-login. 200ms лоадера не заметен,
    // а гарантия свежей сессии критична.
    forceRelogin(tg.initData);

    function readCookie(name) {
        const prefix = name + "=";
        const parts = document.cookie.split(";");
        for (let i = 0; i < parts.length; i++) {
            const c = parts[i].trim();
            if (c.indexOf(prefix) === 0) {
                return decodeURIComponent(c.substring(prefix.length));
            }
        }
        return null;
    }

    function forceRelogin(initData) {
        const form = document.createElement("form");
        form.method = "POST";
        form.action = "/dashboard/login/telegram";
        form.style.display = "none";

        const input = document.createElement("input");
        input.type = "hidden";
        input.name = "initData";
        input.value = initData;
        form.appendChild(input);

        document.documentElement.appendChild(form);
        form.submit();
    }
})();
