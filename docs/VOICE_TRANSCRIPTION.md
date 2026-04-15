# Транскрибация голосовых и видеокружочков

> **Статус:** исследование. Реализация ещё не начата. Документ фиксирует найденное решение, лимиты и план интеграции, чтобы не терять контекст.

## Зачем

Сейчас голосовые (`voice`) и видеокружочки (`video_note`) **полностью теряются** в итоговом текстовом экспорте:

- `export-worker/json_converter.py:153` — `known_media_fields` не содержит `video_note`, filename для кружочков не сохраняется.
- `export-worker/models.py:154` — в `ExportedMessage` есть поле `voice`, но нет `video_note`.
- `src/main/java/com/tcleaner/core/MessageProcessor.java:46-50` — обрабатывается только поле `text` из JSON, если оно пустое → сообщение выбрасывается (`return null`). Чистое голосовое/кружочек без подписи просто исчезает из экспорта.

Проблема: многие каналы и чаты излагают мысли именно в аудиоформате — наш экспорт такие сообщения упускает.

## Решение: нативный Telegram API

Использовать **`messages.transcribeAudio`** — Telegram транскрибирует голосовые и кружочки сам, на своей стороне. Нам не нужно скачивать аудио, гонять его через whisper/vosk, держать ffmpeg или ML-модель — ничего этого, и ноль дополнительной нагрузки на сервер.

В Pyrogram 2.0.106 метод доступен как raw-функция:

```python
from pyrogram.raw import functions

result = await client.invoke(
    functions.messages.TranscribeAudio(
        peer=await client.resolve_peer(chat_id),
        msg_id=message_id,
    )
)
# result: messages.TranscribedAudio
#   .text: str
#   .pending: bool
#   .transcription_id: int
#   .trial_remains_num: int
#   .trial_remains_until_date: int
```

Метод один и тот же для `voice` и `video_note` — отдельного для кружочков нет. Звук из MP4-кружочка Telegram вытаскивает сам.

### Асинхронный ответ (pending)

Если `result.pending == True` — Telegram всё ещё транскрибирует, итоговый текст прилетит через update-событие:

```python
from pyrogram.raw import types as raw_types

@client.on_raw_update()
async def _handler(client, update, users, chats):
    if isinstance(update, raw_types.UpdateTranscribedAudio):
        # update.transcription_id — ключ для матчинга с pending-запросом
        # update.pending — ещё не финально, если True
        # update.text — финальный текст
        ...
```

Типичная задержка — секунды. Нужно ждать через `asyncio.Future`, зарегистрированный под `transcription_id`, с таймаутом (предлагается 45 сек).

## Лимиты

| Кто использует метод | Что разрешено |
|---|---|
| **Telegram Premium** | Безлимит. |
| **Без Premium** | `transcribe_audio_trial_weekly_number` ≈ **2 сообщения в неделю**, каждое до `transcribe_audio_trial_duration_max` секунд. |
| **Без Premium в бустнутой супергруппе** (достигла `group_transcribe_level_min`) | Безлимит **внутри этой группы**, квота аккаунта не тратится. |

Числа присылает сервер Telegram: config + поля `trial_remains_num` и `trial_remains_until_date` в ответе метода. «2 в неделю» — актуальное прод-значение на апрель 2026, периодически меняется.

### Ограничения нашей архитектуры

- **Только user-session.** Метод работает исключительно в MTProto user-клиенте. Боты через Bot API его **не умеют**. У нас Pyrogram-worker авторизован как user → метод доступен.
- **Premium — на аккаунте worker-а.** Наличие безлимита определяется подпиской именно того Telegram-аккаунта, под которым залогинен `export-worker`, а не пользователя бота.
- **FloodWait на всю сессию.** Массированный запуск транскрипций (например, чат с 10 000 голосовых) вызовет `FLOOD_WAIT_N` на весь Pyrogram-клиент — ломает и параллельный экспорт истории. Обязательно bounded concurrency (предлагается 5) + экспоненциальный бэкофф.

### Возможные ошибки

| Ошибка | Обработка |
|---|---|
| `FLOOD_WAIT_N` | Sleep N сек, ретрай до 3 раз. |
| `MSG_ID_INVALID` / `MSG_VOICE_MISSING` | Пропуск, `transcript=None`. |
| `TRANSCRIPTION_FAILED` | Пропуск, `transcript=None`. |
| `trial_remains_num == 0` / non-premium quota | Sticky `quota_exhausted=True` на весь job, остальные голосовые пропускаются без запросов. Пользователь получает предупреждение в боте. |
| Timeout pending-ответа (>45 сек) | `transcript=None`, `transcript_pending=True`. При следующем экспорте того же чата — ретрай из кэша. |

## Формат в итоговом экспорте

**Инлайн-маркер** (решение пользователя):

- Голосовое: `🎤 [голосовое 0:42]: расшифрованный текст...`
- Кружочек: `🎥 [кружочек 0:15]: расшифрованный текст...`
- Голосовое с подписью: подпись + пробел + маркер.
- Без транскрипции (квота кончилась / API отвалился, но пользователь попросил расшифровать): `🎤 [голосовое 0:42 без текста]`.
- Транскрипция выключена пользователем: **старое поведение** — сообщение выбрасывается (если в нём нет текста).

## Кэширование

В SQLite message-cache (worker) добавить таблицу:

```sql
CREATE TABLE IF NOT EXISTS transcripts (
    chat_id    INTEGER NOT NULL,
    msg_id     INTEGER NOT NULL,
    text       TEXT    NOT NULL,
    created_at REAL    NOT NULL,
    PRIMARY KEY (chat_id, msg_id)
);
```

Повторный экспорт того же чата читает из кэша и **не тратит квоту** Premium/non-Premium.

Миграция безопасная: `CREATE TABLE IF NOT EXISTS`, без `ALTER` на существующей `messages`.

## Активация из UI бота

Отдельный шаг в wizard-е `ExportBot`, после выбора дат, перед стартом:

```
🎙 Расшифровать голосовые и видеокружочки в тексте?

⚠️ Без Telegram Premium доступно ~2 расшифровки в неделю,
   остальные будут пропущены.

[ ✅ Да, расшифровать ]
[ ➡️ Пропустить      ]
[ ◀️ Назад           ]
```

Флаг `transcribe_voice: bool` прокидывается через Redis job-payload в Python worker. По умолчанию — **выключено**, чтобы не ломать текущее поведение и скорость.

## Пайплайн

```
User → ExportBot wizard → [ 🎙 Да/Нет ] → ExportJobProducer
                                              │ transcribe_voice=true
                                              ▼
                                         Redis queue
                                              │
                                              ▼
                                    Python ExportWorker
                                              │
                               ┌──────────────┴──────────────┐
                               │                             │
                    iter_messages() (Pyrogram)       TranscriptionService
                               │                             │
                               │  для voice/video_note:      │
                               └────►  cache miss?  ─────────┤
                                                             ▼
                                              messages.transcribeAudio
                                                             │
                                       pending? ─── yes ──► wait UpdateTranscribedAudio
                                       │                     (asyncio.Future, timeout 45s)
                                       │                             │
                                       └─── no ──────────────────────┤
                                                                     ▼
                                                           transcripts table (SQLite)
                                                                     │
                                                                     ▼
                                                    ExportedMessage.transcript
                                                                     │
                                                                     ▼
                                                           POST /api/convert
                                                                     │
                                                                     ▼
                                                     Java MessageProcessor
                                                     → 🎤 [голосовое 0:42]: ...
```

## Файлы, которые затронет реализация

### Python worker
- `export-worker/transcriber.py` — **новый** модуль, `TranscriptionService` + `TranscriptionResult`.
- `export-worker/models.py` — добавить `transcribe_voice` в `ExportRequest`; `transcript`, `transcript_pending`, `video_note` в `ExportedMessage`.
- `export-worker/json_converter.py` — `known_media_fields` + `"video_note"`.
- `export-worker/main.py` — инициализация `TranscriptionService`, обогащение per-batch в `_fetch_all_messages` / `_export_with_id_cache` / `_export_with_date_cache`.
- `export-worker/message_cache.py` — таблица `transcripts`, методы `get_transcript` / `store_transcript`, join при чтении из кэша.

### Java
- `src/main/java/com/tcleaner/bot/UserSession.java` — state `AWAITING_TRANSCRIBE_CHOICE`, поле `transcribeVoice`.
- `src/main/java/com/tcleaner/bot/ExportBot.java` — новые callbacks, шаг wizard-а, проброс флага в `enqueue`.
- `src/main/java/com/tcleaner/bot/ExportJobProducer.java` — overload с `transcribeVoice`, `job.put("transcribe_voice", ...)`.
- `src/main/java/com/tcleaner/core/MessageProcessor.java` — читать `media_type`, `transcript`, `duration`; не выбрасывать voice/video_note если `transcript_requested`.
- `src/main/java/com/tcleaner/format/MessageFormatter.java` — `renderBody`, `formatDuration`, константы маркеров.

### Docker / зависимости

**Никаких изменений.** Это ключевая причина выбора этого решения:
- `requirements.txt` — без новых пакетов (`messages.TranscribeAudio` уже в Pyrogram 2.0.106).
- `pom.xml` — без изменений.
- `Dockerfile` / `docker-compose.yml` — без изменений.
- Память worker-а: ожидаемый прирост < 20 MB (словарь pending-futures + semaphore).

## Почему не whisper / vosk / cloud

Рассматривались и **отклонены**:

| Вариант | Почему нет |
|---|---|
| Локальный `faster-whisper` (medium для русского) | +0.5–1.5 ГБ RAM на worker, нужен ffmpeg для кружочков, первый прогон медленный. Для нашего прод-сервера — риск падения по памяти. |
| `vosk` | Быстрее whisper, но заметно хуже на русском в реалистичных условиях (спонтанная речь, шум). Всё равно +модель в образе, +ffmpeg. |
| Cloud API (Yandex SpeechKit / OpenAI Whisper) | Платный счёт за минуту, нужен API-ключ, вопросы приватности (выгружаем чужие голосовые во внешний сервис). |

Telegram API — бесплатно, безлимитно для Premium, без нагрузки на наш сервер, качество продакшен-уровня для русского (Telegram тренировал на своём огромном трафике).

## Верификация (после реализации)

1. Экспорт канала с голосовыми, флаг включён, аккаунт worker-а с Premium → в `.txt` есть строки `🎤 [голосовое 0:42]: ...`.
2. Флаг выключен → поведение как сейчас, никаких API-вызовов `transcribeAudio` в логах, voice/video_note без текста выбрасываются.
3. Non-Premium аккаунт, 50 голосовых с флагом включён → первые ~2 расшифрованы, остальные с маркером «без текста», в логах `quota_exhausted=True`, экспорт не падает.
4. Повторный экспорт того же чата → в логах `transcript cache hit`, нулевые обращения к API.
5. `/cancel` во время ожидания pending → worker выходит за ~1 сек, частичные транскрипции сохранены в БД.

## Ссылки

- [Voice message transcription — Telegram API](https://core.telegram.org/api/transcribe)
- [messages.transcribeAudio — method](https://core.telegram.org/method/messages.transcribeAudio)
- [messages.TranscribeAudio — Pyrogram](https://docs.pyrogram.org/telegram/functions/messages/transcribe-audio)
- [Invoking raw methods — Pyrogram](https://docs.pyrogram.org/start/invoking)
