(function () {
    const tg = window.Telegram && window.Telegram.WebApp;
    if (!tg || !tg.initData) {
        showFallback();
        return;
    }
    tg.ready();
    tg.expand();
    document.getElementById('initDataInput').value = tg.initData;
    document.getElementById('loginForm').submit();

    function showFallback() {
        const content = document.getElementById('content');
        while (content.firstChild) {
            content.removeChild(content.firstChild);
        }
        const h2 = document.createElement('h2');
        h2.textContent = 'Telegram Export Cleaner';
        const p = document.createElement('p');
        p.appendChild(document.createTextNode('Эта страница открывается только в Telegram.'));
        p.appendChild(document.createElement('br'));
        p.appendChild(document.createTextNode('Найдите бота и нажмите кнопку '));
        const strong = document.createElement('strong');
        strong.textContent = 'Dashboard';
        p.appendChild(strong);
        p.appendChild(document.createTextNode('.'));
        content.appendChild(h2);
        content.appendChild(p);
    }
})();
