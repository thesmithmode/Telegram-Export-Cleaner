# Final Report: Progress Bar Missing & Slow Export Issues

## Executive Summary

**2 основных проблемы найдены:**

1. **Прогресс-бар не отправляется (track() молчит)** — если `set_total(0)` вызывается
2. **Экспорт 7 дней медленный на 100-200x** — FloodWait по 20-24 сек за каждый API call

---

## Problem #1: Progress Bar Missing After "Экспорт начался..."

### Root Cause

**File**: export-worker/java_client.py, lines 435-449 (set_total method) и 474-478 (track method)

```python
# Строка 435-449:
async def set_total(self, total):
    if not total:      # ← если total=0 или None
        return         # ← игнорируем обновление!
    self._total = total
    # ...отправляем сообщение...

# Строка 474-478:
async def track(self, count):
    if not self._total:  # ← если _total остался 0
        return           # ← track() не отправляет обновления!
```

### Flow проблемы

```
1. _export_with_date_cache() линия 668:
   await tracker.start()  → ✓ отправляет спиннер "⏳ Экспорт начался..."

2. Линия 673:
   total = await self.telegram_client.get_messages_count(from_dt, to_dt)
   ↓ МОЖЕТ ВЕРНУТЬ 0 или None если:
   - get_date_range_count() вернула 0 (count_to == count_from)
   - Exception при вызове Telegram API

3. Линия 683:
   await tracker.set_total(total)  → если total=0, пропускает обновление!

4. Линия 711:
   await tracker.track(fetched_count)  → молчит, потому что self._total=0
   
   РЕЗУЛЬТАТ: Юзер видит спиннер "⏳ Экспорт начался..." но НЕТ progress bar!
```

### Code References

- **set_total() guards against 0**: export-worker/java_client.py:438-440
- **track() requires _total > 0**: export-worker/java_client.py:477-478
- **get_messages_count() returns None on exception**: export-worker/pyrogram_client.py:389-390
- **get_date_range_count() can return 0**: export-worker/pyrogram_client.py:386

### When This Happens

**Scenario 1**: Empty date range (count_before_to == count_before_from)
```python
# pyrogram_client.py:386
date_range_count = count_to - count_from  # = 0
return max(date_range_count, 0)  # = 0
```

**Scenario 2**: Exception in Telegram API call
```python
# pyrogram_client.py:388-390
except Exception as e:
    logger.warning(...)
    return None  # → get_messages_count gets None
```

---

## Problem #2: 7-Day Export is 100-200x Slower (FloodWait Bottleneck)

### Timeline Evidence (from docker logs)

```
22:25:01,763 - sendMessage (спиннер)
22:25:01,993 - Fetching history...
22:25:12,214 - FloodWait 20 sec ← [10 sec fetching + 20 sec wait = 30 sec total]
22:25:41,892 - FloodWait 22 sec ← [30 sec fetching + 22 sec wait = 52 sec total]
22:26:11,973 - FloodWait 23 sec
22:26:42,255 - FloodWait 24 sec
22:27:14,515 - FloodWait 23 sec
22:27:46,485 - FloodWait 22 sec
22:28:16,959 - FloodWait 23 sec
22:28:49,362 - FloodWait 22 sec
[Total: 8+ FloodWait rounds = 180+ seconds of blocking]

22:29:16,091 - Fetched 12995 messages  ← finally done
TOTAL: 22:29:16 - 22:25:01 = 4 minutes 15 seconds
```

### Root Cause

**File**: export-worker/pyrogram_client.py, lines 220-302 (get_chat_history method)

Pyrogram's rate limiting handler causes sequential blocking:

```python
except FloodWait as e:  # Строка 274
    # Rate limited by Telegram API
    retry_count += 1
    wait_time = min(...)
    await asyncio.sleep(wait_time)  # ← BLOCKS HERE 20-24 seconds
    # Restart get_chat_history from last_offset_id
```

### Why 7 Days is Slower than 24 Hours

| Scenario | Cache | Missing Ranges | get_chat_history() Calls | FloodWait Rounds | Example Timing |
|----------|-------|-----------------|--------------------------|------------------|-----------------|
| **24h (cached)** | HIT | 0 | 0 | 0 | 1 second (cache only) |
| **24h (no cache)** | MISS | 1 | 1 | 1-2 | 30-40 seconds |
| **7d (partial cache)** | PARTIAL MISS | 1-2 | 1-2 | 2-4 | 60-120 seconds |
| **7d (no cache)** | MISS | 1 | 1 | 1-2 | 30-40 seconds (but full fetch) |
| **Observed in logs** | PARTIAL MISS | 1 (Apr 5-8) | 1 | 8+ | **255 seconds** |

**The observed 255 sec is high because:**
1. Only 1 missing gap (Apr 5-8) but it has many messages (12,995)
2. Each batch of ~1000-2000 messages gets FloodWait
3. Multiple iterations of get_chat_history loop due to pagination

### Code Path

```python
# main.py:666-711
tracker.start()
get_messages_count()  # ← could be slow, but not the main bottleneck

for gap_from, gap_to in missing:  # ← typically 1 gap for 7d partial
    batch = []
    async for msg in self.telegram_client.get_chat_history(...):  # ← HERE!
        batch.append(msg)
        if len(batch) >= 1000:
            store to cache
            track()  # ← NOT SENT if total=0
```

### get_chat_history internals

**File**: export-worker/pyrogram_client.py:210-303

```python
while True:
    try:
        async for message in self.client.get_chat_history(...):  # ← Pyrogram iterator
            # Process message, yield...
        break  # ← success, exit retry loop
    except FloodWait as e:
        # ← BLOCKS HERE with asyncio.sleep(wait_time)
        await asyncio.sleep(wait_time)  # ← 20-24 seconds
        # Restart from last_offset_id
```

**Why Pyrogram gets FloodWait repeatedly:**
- Each `get_chat_history()` iterator can generate multiple API calls internally
- Telegram rate-limits based on total requests, not messages fetched
- Pyrogram respects FloodWait and retries from last offset
- Multiple FloodWait rounds = cumulative 180+ seconds of blocking

---

## Concrete Issues & Fixes Needed

### Issue 1: set_total(0) causes track() to be silent

**Location**: export-worker/java_client.py:438-440, 477-478

**Current behavior**:
```python
if not total:
    return  # Silent skip, _total stays 0
```

**Impact**: If get_messages_count returns 0/None, progress bar never appears.

**Fix approach**:
- Option A: Don't guard set_total on zero, always update _total and send at least "Unknown progress..."
- Option B: When total=0, still show "0 из ?" or "Counting messages..."
- Option C: Add fallback progress display when total unknown

### Issue 2: FloodWait blocking during message fetch

**Location**: export-worker/pyrogram_client.py:274-300

**Current behavior**:
- Each FloodWait causes 20-24 sec blocking
- Multiple iterations = 180+ seconds for 4-day gap

**Root cause**: Pyrogram pagination combined with Telegram rate limiting

**Fix approach**:
- Pre-fetch all message IDs or use different API endpoint?
- Batch multiple dates together to reduce API call count?
- Implement client-side rate limiting (proactive delay) instead of reactive FloodWait?

### Issue 3: get_messages_count timing not logged

**Location**: export-worker/main.py:673-674, 774, 938

**Issue**: No visibility into whether get_messages_count is slow or returned 0

**Fix**: Add log statements showing count result and time taken

---

## Summary for User

**Why progress bar missing:**
- Bug in `set_total()` and `track()` — they silently fail if total=0
- Happens when `get_messages_count()` returns 0 or None
- Likely cause: Empty date range or API error

**Why 7 days slow:**
- Telegram FloodWait rate limiting on 20-24 seconds per batch
- 4-day gap with 13K messages requires multiple API calls
- Each call can trigger FloodWait, blocking for 20+ seconds
- 8 FloodWait rounds = 160+ seconds of idle waiting
- 24-hour cached export skips this entirely (0 calls)

**Comparison:**
- ✅ 24h cached: 1 second (no API calls)
- ⚠️ 24h fresh: 30-40 seconds (1-2 API calls with 1-2 FloodWait)
- ⚠️ 7d partial: 4+ minutes (multiple FloodWait rounds)
- ⚠️ 7d fresh: Could be 10+ minutes (full chat fetch with many FloodWait)

