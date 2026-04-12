# Исследование проблемы с прогрессом и скоростью экспорта

## Проблемы
1. **Прогресс-бар не появляется** после "Экспорт начался..."
2. **Экспорт 7 дней очень медленный** (FloodWait 20+ сек за вызов)

## Flow экспорта (Python Worker)

### Точка входа: `process_job()` (main.py:326)
```
process_job(job: ExportRequest)
  ├─ mark_job_processing()
  ├─ verify_and_get_info() — get chat info
  ├─ [ВЫБОР ПУТИ]:
  │   ├─ [С DATE FILTER] → _export_with_date_cache() (main.py:609)
  │   │   ├─ tracker.start() — отправить спиннер "⏳ Экспорт начался..."
  │   │   ├─ get_messages_count(chat_id, from_date, to_date) ← МЕДЛЕННО для 7 дней
  │   │   │   └─ get_date_range_count() — 2 MTProto GetHistory(limit=1) вызова
  │   │   ├─ tracker.set_total(total) — обновить "0 из N"
  │   │   ├─ get_chat_history() — FETCH сообщений с FloodWait
  │   │   │   └─ FloodWait на 20+ сек за вызов!
  │   │   ├─ tracker.track(count) — обновить прогресс (2-5% за раз)
  │   │   └─ tracker.finalize() — 100%
  │   │
  │   ├─ [БЕЗ DATE FILTER] → _export_with_id_cache() (main.py:740)
  │   │   ├─ tracker.start()
  │   │   ├─ get_messages_count(chat_id) — быстро (1 GetHistory call)
  │   │   ├─ tracker.set_total(total) — "0 из N"
  │   │   ├─ tracker.seed(cached_count) — прыг на X% если есть кэш
  │   │   ├─ get_chat_history() fetch новых → FloodWait
  │   │   ├─ get_chat_history() fill gaps → FloodWait
  │   │   ├─ get_chat_history() старых → FloodWait
  │   │   └─ tracker.finalize()
  │   │
  │   └─ [NO CACHE] → _fetch_all_messages() (main.py:911)
  │       ├─ get_messages_count() → МОЖЕТ БЫТЬ МЕДЛЕННО
  │       ├─ tracker.start(total=total)
  │       ├─ get_chat_history() → FloodWait
  │       └─ tracker.finalize()
  │
  └─ send_response() → обработка в Java
```

## Узкие места

### 1. **FloodWait на 20+ сек** (основная проблема!)
- **Где**: pyrogram_client.py:223-300 в get_chat_history()
- **Почему 7 дней медленнее 24ч**: Больше gap'ов в кэше → больше отдельных get_chat_history() вызовов → каждый может получить FloodWait
- **Логи показывают**: Waiting 20-24 сек между вызовами к messages.GetHistory
- **Пример**: 
  ```
  22:25:01 - начало fetch gap [2026-04-05 - 2026-04-08]
  22:25:12 - первый FloodWait 20s (первый API call в цикле)
  22:25:41 - второй FloodWait 22s
  22:26:11 - третий FloodWait 23s
  ...в итоге ~3 мин на fetch 4-дневного gap'а (в то время как сами данные качаются быстро)
  ```

### 2. **get_messages_count() с date filter может быть медленно**
- **Где**: main.py:673-674 в _export_with_date_cache()
- **Что**: 2x MTProto GetHistory(limit=1) с offset_date
- **Может ли быть bottleneck**: Если offset_date требует сканирования, то Telegram может ответить медленно
- **Не явное в логах**, но возможно

### 3. **Как связано с progress bar'ом:**
```python
# main.py:666-675 (без date filter):
tracker = self._create_tracker(job)  # создаём tracker
await tracker.start()                 # ← отправляем spinner (⏳ начался...)
total = await self.telegram_client.get_messages_count(job.chat_id, from_dt, to_dt)  
# ← ЗДЕСЬ МОЖЕТ БЫТЬ ЗАДЕРЖКА (milliseconds до нескольких секунд)
await tracker.set_total(total)        # ← обновляем на "0 из N"
```

## Почему 24 часа быстрее чем 7 дней

**24 часа в кэше, 7 дней — нет:**

| Сценарий | Кэш MISS | gap'ов | get_chat_history calls | FloodWait'ов | Время |
|----------|---------|--------|------------------------|--------------|-------|
| 24h (cached) | NO | 0 | 0 | 0 | ~1 сек |
| 7d (no cache) | YES | 1 или 2-3 | 1-3 | 3-6 | 60-180 сек |

Каждый gap требует отдельного `get_chat_history()` с потенциальным FloodWait.

## Progress bar не появляется: почему?

1. **Спиннер "⏳ Экспорт начался..."** отправляется на старте (tracker.start(), строка 668/771)
2. **Progress bar "📊 0 из N"** отправляется после set_total() (строка 682/778)
3. **Если задержка в get_messages_count()** (строка 673/774), то юзер видит спиннер ~2-3 сек, потом progress bar

**Но если:**
- get_messages_count() возвращает None или 0 → set_total() её игнорирует (java_client.py:439)
- → прогресс-бар НЕ появляется, только спиннер
- → при count=0 → tracker.track() не отправляет обновления (java_client.py:484)

## Логичная гипотеза для "прогресс-бара нет"

```python
# java_client.py:435-449
async def set_total(self, total):
    if not total:  # ← если total=None или 0
        return      # ← пропускаем обновление!
```

**Если get_messages_count() вернула 0 для 7-дневного диапазона:**
- Telegram API вернула count=0 (ошибка в подсчёте или API bug?)
- Или date range query возвращает 0 неправильно
- → Прогресс-бар не рисуется
- → tracker.track() не шлёт обновления

## Рекомендации по исправлению

1. **Основное**: Reduce FloodWait frequency
   - Batch multiple gaps into single get_chat_history()?
   - Или cache all date-ranges immediately на start?

2. **Для progress bar**: 
   - Не ждать get_messages_count() перед tracker.start(total=) в _export_with_date_cache?
   - Или показывать progress-bar even if total=0, с подсчётом по мере fetch?

3. **For logging**:
   - Add timing logs around get_messages_count()
   - Log when set_total(0) happens

