# Dashboard — vendored JS

Файл `chart.min.js` (Chart.js v4) подтягивается при Docker build
(см. `Dockerfile`, stage `build`). Локально для разработки можно либо
запустить `docker compose build java-bot`, либо вручную скачать:

```bash
curl -sfL -o src/main/resources/static/dashboard/vendor/chart.min.js \
  https://cdn.jsdelivr.net/npm/chart.js@4.4.1/dist/chart.umd.min.js
```

Файл помечен в `.gitignore` — в репозиторий не коммитится (lock-free
обновление версии через `ARG CHART_JS_VERSION` в Dockerfile).
