# Project Index

> Источник истины по структуре проекта. **Обновлять при добавлении/удалении файлов и папок.**

## Корень

```
Dockerfile                  Java-бот образ
docker-compose.yml          Dev: все сервисы
docker-compose.prod.yml     Prod: с Traefik + secrets
.env.example                Переменные окружения (шаблон)
pom.xml                     Maven + зависимости
checkstyle.xml              Java code style
README.md                   Быстрый старт
```

## export-worker/  — Python Pyrogram worker

```
main.py                     ExportWorker (точка входа, 3-path caching)
queue_consumer.py           BLPOP-консьюмер Redis
pyrogram_client.py          Pyrogram + canonical ID resolver
message_cache.py            SQLite-кэш сообщений (WAL, HDD)
java_client.py              HTTP POST /api/convert
json_converter.py           Конвертация Telegram JSON
config.py                   Настройки (env vars)
models.py                   Pydantic-модели
get_session.py              Утилита получения Pyrogram session string
pyproject.toml / requirements*.txt
Dockerfile / .dockerignore
tests/                      pytest (AsyncMock, SQLite tmp_path)
```

## src/main/java/com/tcleaner/  — Java Spring Boot

```
TelegramCleanerApplication.java   Точка входа
SecurityConfig.java               API security chain (@Order 2)
WebConfig.java                    CORS, multipart

api/
  TelegramController.java         POST /api/convert (streaming)
  FileConversionService.java      Оркестрация конвертации
  ApiKeyFilter.java               X-API-Key header guard
  ApiExceptionHandler.java        @RestControllerAdvice

bot/
  ExportBot.java                  TelegramLongPollingBot (wizard UI)
  ExportJobProducer.java          SET NX + LPUSH в Redis-очередь
  BotMessenger.java               Отправка сообщений пользователю
  BotConfig.java                  @ConfigurationProperties
  UserSession.java                Состояние диалога пользователя

core/
  TelegramExporter.java           Tree + Streaming экспорт
  TelegramExporterInterface.java  Контракт экспортера
  MessageFilter.java              Фильтрация сообщений
  MessageProcessor.java           Обработка одного сообщения
  JsonUtils.java                  Jackson helpers
  TelegramExporterException.java  Доменное исключение

format/
  MarkdownParser.java             20+ entity types
  MessageFormatter.java           Финальное форматирование
  DateFormatter.java              Форматирование дат
  UrlValidator.java               Валидация URL
  StringUtils.java                Утилиты строк

dashboard/
  auth/
    DashboardUserDetails.java     Spring Security principal
    DashboardUserService.java     UserDetailsService
    EnvUserBootstrap.java         Создание admin из env при старте
    telegram/
      TelegramAuthController.java   POST /dashboard/login/telegram
      TelegramAuthVerifier.java     HMAC-SHA256 проверка виджета
      TelegramAuthenticationException.java
      TelegramLoginData.java

  config/
    CacheConfig.java              Caffeine: 3 тира (live/historical/profile)
    DashboardSecurityConfig.java  Security chain (@Order 1), permitAll
    DashboardAccessDeniedHandler.java  USER → тихий редирект на /me
    RedisStreamsConfig.java        Redis Stream listener container

  domain/
    BotUser.java / Chat.java / ExportEvent.java   JPA-сущности
    DashboardUser.java            Пользователь дашборда
    DashboardRole.java / AuthProvider.java / ExportStatus.java / ExportSource.java

  dto/
    OverviewDto.java / EventRowDto.java / ChatStatsRow.java
    UserStatsRow.java / UserDetailDto.java / TimeSeriesPointDto.java
    MeDto.java

  events/
    StatsStreamPublisher.java     Публикация события в Redis Stream
    StatsStreamConsumer.java      Консьюмер → ExportEventIngestionService
    StatsEventPayload.java / StatsEventType.java / StatsStreamProperties.java

  repository/
    BotUserRepository.java / ChatRepository.java
    ExportEventRepository.java / DashboardUserRepository.java

  security/
    BotUserAccessPolicy.java      RBAC: effectiveUserId, canSeeUser

  service/
    ingestion/
      ExportEventIngestionService.java  Запись события, вызов upsert'еров
      BotUserUpserter.java              Upsert bot_users
      ChatUpserter.java                 Upsert chats
    stats/
      StatsQueryService.java    Агрегации через JdbcTemplate (native SQL)
      PeriodResolver.java       period/from/to → StatsPeriod
      StatsPeriod.java          Value object: from, to, granularity

  web/
    DashboardApiController.java     GET /dashboard/api/stats/**
    DashboardMeApiController.java   GET /dashboard/api/me/**
    DashboardPageController.java    SSR-страницы (Thymeleaf)
    DashboardMePageController.java  /dashboard/me SSR
    DashboardExceptionHandler.java  @ControllerAdvice дашборда
```

## src/main/resources/

```
application.properties              Конфиг Spring Boot
logback.xml                         Логирование
messages.properties                 i18n (Thymeleaf #{...})

db/changelog/db.changelog-master.sql   Liquibase schema + changesets

static/dashboard/
  css/app.css                       Дизайн-система (--tec-* токены)
  img/logo-mark.svg                 Логотип
  js/app.js                         Общие утилиты (fetchJson, formatBytes…)
  js/pages/overview.js              Скрипт страницы overview
  js/pages/events.js                Скрипт страницы events
  js/pages/chats.js                 Скрипт страницы chats
  js/pages/users.js                 Скрипт страницы users
  js/pages/user-detail.js           Скрипт страницы user-detail
  js/pages/me.js                    Скрипт личного кабинета
  vendor/                           Сторонние JS-библиотеки (Chart.js и др.)

templates/dashboard/
  layout.html                       Базовый layout (Thymeleaf)
  login.html                        Страница входа + Telegram Widget
  overview.html / chats.html / events.html / users.html
  user-detail.html / me.html / error.html
  fragments/header.html             Шапка с логотипом
  fragments/chart-block.html        Блок графика
  fragments/period-filter.html      Фильтр периода
```

## src/test/

```
resources/
  application.properties            Тестовый конфиг (SQLite :memory:, cache=Caffeine)
  application-test.properties       Доп. оверрайды

java/com/tcleaner/
  [корневые тесты]                  TelegramExporter*, MarkdownParser*, MessageFilter* и др.

  api/                              ApiKeyFilter, ApiExceptionHandler, TelegramController
  bot/                              ExportBot, ExportJobProducer, BotMessenger, UserSession
  format/                           StringUtils, UrlValidator

  dashboard/
    DashboardTestUsers.java         Фабрика test-principal'ов
    DashboardInfrastructureSmokeTest.java
    DashboardSchemaIndexesTest.java
    DashboardSecurityIntegrationTest.java

    auth/                           DashboardUserService, EnvUserBootstrap, TelegramAuth*
    config/
      DashboardAccessIsolationIntegrationTest.java   RBAC-матрица
    domain/                         BotUser/Chat/ExportEvent/DashboardUser репозитории
    events/                         StatsStream*, StatsEventPayload
    security/                       BotUserAccessPolicy
    service/ingestion/              BotUserUpserter, ChatUpserter, ExportEventIngestionService
    service/stats/                  PeriodResolver, StatsQueryService
    web/                            DashboardApiController, DashboardMeApi*, DashboardPage*
```

## docs/

```
ARCHITECTURE.md     Полная схема системы
API.md              REST API reference (/api/convert)
DASHBOARD.md        Dashboard: страницы, RBAC, кэш
BOT.md              Telegram-бот: wizard, сессии, очередь
PYTHON_WORKER.md    Python worker internals
DEVELOPMENT.md      Dev-процесс, CI/CD
SETUP.md            Деплой и конфигурация
SERVER_SETUP.md     Сервер: Traefik, fail2ban, UFW
```

## .github/workflows/

```
ci.yml      Тесты (Maven + pytest) при push/PR
build.yml   Сборка + деплой при push в main
```

## ops/

```
backup-cache.sh     Ежедневный бэкап SQLite + session → /root/telegram-cleaner/backups/
```

## design/  — исходники дизайн-системы (не деплоятся)

```
colors_and_type.css    Токены --tec-*, шрифты
assets/logo-mark.svg   Исходник логотипа
```
