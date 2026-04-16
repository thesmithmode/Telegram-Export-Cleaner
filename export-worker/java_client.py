
import json
import logging
import asyncio
import re
import os
import tempfile
import time
import unicodedata
from typing import Optional, Union, AsyncIterator

import httpx

from config import settings
from models import ExportedMessage, SendResponsePayload

logger = logging.getLogger(__name__)

class JavaBotClient:

    def __init__(self, timeout: int = 3600, max_retries: int = 3):
        self.base_url = settings.JAVA_API_BASE_URL.rstrip("/")
        self.timeout = timeout
        self.max_retries = max_retries
        self.bot_token = settings.TELEGRAM_BOT_TOKEN
        
        # Senior Configuration: 
        # - read=None: Wait indefinitely for Java to process the massive file.
        # - write=None: Don't timeout while streaming gigabytes to Java.
        custom_timeout = httpx.Timeout(
            timeout=float(self.timeout),
            read=None,
            write=300.0,
            connect=30.0
        )
        self._http_client = httpx.AsyncClient(timeout=custom_timeout)
        logger.info(f"Java API Client initialized (O(1) Memory, Timeout: {self.timeout}s)")

    async def send_response(self, payload: SendResponsePayload) -> bool:
        if payload.status == "failed":
            if payload.error and payload.user_chat_id and self.bot_token:
                await self.notify_user_failure(
                    payload.user_chat_id, payload.task_id, payload.error
                )
            return True

        # Нет сообщений за выбранный период — отдаём пользователю человеко-
        # читаемое уведомление через sendMessage и не ходим в Java.
        # Ранее пустой JSON {"messages":[]} проходил через /api/convert,
        # но Telegram отклоняет отправку пустого документа, и пользователь
        # получал "Не удалось отправить файл. Попробуйте снова."
        if payload.actual_count == 0:
            # messages может быть async-генератором (из _export_with_date_cache
            # и т.п.) — при early return он иначе висит открытым и держит
            # ресурсы (SQLite-курсор, внутренний батч). Закрываем явно.
            aclose = getattr(payload.messages, "aclose", None)
            if callable(aclose):
                try:
                    await aclose()
                except Exception as e:
                    logger.debug(f"aclose() on empty messages iterator raised: {e}")
            if payload.user_chat_id and self.bot_token:
                await self.notify_empty_export(
                    payload.user_chat_id,
                    payload.task_id,
                    payload.from_date,
                    payload.to_date,
                )
            return True

        # 1. Stream messages directly to a temporary file on disk (Memory O(1))
        tmp_path = await self._stream_to_temp_json(payload.messages, payload.actual_count)

        try:
            file_size_mb = os.path.getsize(tmp_path) / (1024 * 1024)
            logger.info(f"📤 Streaming {file_size_mb:.2f} MB from disk (task {payload.task_id})")

            # 2. Upload using httpx streaming capabilities
            cleaned_text = await self._upload_file_to_java(
                tmp_path,
                from_date=payload.from_date,
                to_date=payload.to_date,
                keywords=payload.keywords,
                exclude_keywords=payload.exclude_keywords,
                task_id=payload.task_id,
                bot_user_id=payload.user_id,
                chat_title=payload.chat_title,
                messages_count=payload.actual_count,
            )
        finally:
            try:
                os.unlink(tmp_path)
            except FileNotFoundError:
                pass

        if cleaned_text is None:
            logger.error(f"❌ Java API processing failed for task {payload.task_id}")
            if payload.user_chat_id and self.bot_token:
                await self.notify_user_failure(
                    payload.user_chat_id, payload.task_id, "Processing service unavailable"
                )
            return False

        # Защита от невидимых non-whitespace символов (BOM, ZWSP, ZWJ, форматирование):
        # Java-фильтр пропускает их, Telegram отклоняет sendDocument с пустым телом.
        if not cleaned_text or all(
            unicodedata.category(c) in ("Cc", "Cf", "Zs", "Zl", "Zp")
            for c in cleaned_text
        ):
            logger.info(
                f"ℹ️ Task {payload.task_id}: Java returned empty text "
                f"(actual_count={payload.actual_count}) — notifying user "
                f"instead of uploading empty document"
            )
            if payload.user_chat_id and self.bot_token:
                await self.notify_empty_export(
                    payload.user_chat_id,
                    payload.task_id,
                    payload.from_date,
                    payload.to_date,
                )
            return True

        # 3. Deliver cleaned text to user
        if payload.user_chat_id and self.bot_token:
            filename = self._build_filename(
                payload.chat_title, payload.from_date, payload.to_date
            )
            sent = await self._send_file_to_user(
                payload.user_chat_id, payload.task_id, cleaned_text, filename=filename
            )
            if not sent:
                await self.notify_user_failure(
                    payload.user_chat_id, payload.task_id,
                    "Не удалось отправить файл. Попробуйте снова."
                )
                return False

        return True

    async def _stream_to_temp_json(
        self,
        messages: Union[list[ExportedMessage], AsyncIterator[ExportedMessage]],
        count: int
    ) -> str:
        fd, tmp_path = tempfile.mkstemp(suffix=".json", prefix="tg_stream_")
        try:
            with os.fdopen(fd, "wb") as f:
                f.write(f'{{"type":"personal_chat","name":"Export","message_count":{count},"messages":['.encode("utf-8"))
                
                first = True
                msgs_iter = messages if not isinstance(messages, list) else self._iter_list(messages)
                
                async for msg in msgs_iter:
                    m_dict = msg.model_dump(exclude_none=True)
                    if m_dict.get("text_entities"):
                        m_dict["text_entities"] = self._transform_entities(m_dict.get("text") or "", m_dict["text_entities"])
                    
                    if not first: f.write(b",")
                    f.write(json.dumps(m_dict, ensure_ascii=False).encode("utf-8"))
                    first = False
                
                f.write(b"]}")
        except Exception:
            try:
                os.unlink(tmp_path)
            except FileNotFoundError:
                pass
            except Exception as cleanup_err:
                logger.warning(f"Failed to clean up temp file {tmp_path}: {cleanup_err}")
            raise
        return tmp_path

    async def _iter_list(self, lst):
        for item in lst: yield item

    async def _upload_file_to_java(
        self,
        file_path: str,
        from_date: Optional[str] = None,
        to_date: Optional[str] = None,
        keywords: Optional[str] = None,
        exclude_keywords: Optional[str] = None,
        task_id: Optional[str] = None,
        bot_user_id: Optional[int] = None,
        chat_title: Optional[str] = None,
        messages_count: Optional[int] = None,
    ) -> Optional[str]:
        url = f"{self.base_url}/api/convert"

        data = {}
        if from_date: data["startDate"] = from_date[:10]
        if to_date: data["endDate"] = to_date[:10]
        if keywords: data["keywords"] = keywords
        if exclude_keywords: data["excludeKeywords"] = exclude_keywords
        if task_id: data["taskId"] = task_id
        if bot_user_id is not None: data["botUserId"] = str(bot_user_id)
        if chat_title: data["chatTitle"] = chat_title
        if messages_count: data["messagesCount"] = str(messages_count)

        retry_count = 0
        while retry_count <= self.max_retries:
            try:
                # 'with open' as a file handle allows httpx to stream from disk
                with open(file_path, "rb") as f:
                    files = {"file": ("result.json", f, "application/json")}
                    response = await self._http_client.post(
                        url,
                        files=files,
                        data=data,
                    )

                if response.status_code == 200:
                    return response.text
                
                logger.error(f"Java API error {response.status_code}: {response.text[:200]}")
                if response.status_code == 400:
                    return None
                    
            except Exception as e:
                logger.error(f"Upload to Java failed (attempt {retry_count + 1}): {e}")
            
            retry_count += 1
            if retry_count <= self.max_retries:
                await asyncio.sleep(settings.RETRY_BASE_DELAY * retry_count)
        
        return None

    @staticmethod
    def _transform_entities(text: str, entities: list[dict]) -> list[dict]:
        if not entities or not text: return entities
        res = []
        try:
            text_utf16 = text.encode('utf-16-le')
            for e in entities:
                off, length = e.get("offset", 0), e.get("length", 0)
                byte_off, byte_end = off * 2, (off + length) * 2
                new_e = {
                    "type": e.get("type", "plain"),
                    "text": text_utf16[byte_off:byte_end].decode('utf-16-le')
                }
                if "url" in e: new_e["href"] = e["url"]
                if "user_id" in e: new_e["user_id"] = e["user_id"]
                res.append(new_e)
            return res
        except Exception as e:
            logger.warning("Entity transformation failed, returning original: %s", e)
            return entities

    @staticmethod
    def _sanitize_filename(name: str) -> str:
        name = re.sub(r'[^\w\s\-.]', '', name)
        return re.sub(r'\s+', '_', name.strip())[:80] or "export"

    def _build_filename(self, title, f_date, t_date) -> str:
        base = self._sanitize_filename(title) if title else "export"
        if f_date and t_date: return f"{base}_{f_date[:10]}_{t_date[:10]}.txt"
        return f"{base}_all.txt"

    async def _send_file_to_user(self, chat_id, task_id, text, filename) -> bool:
        text_bytes = text.encode("utf-8")
        
        # Max 45MB for Telegram Bot API
        if len(text_bytes) <= 45 * 1024 * 1024:
            return await self._send_single_file(chat_id, task_id, text_bytes, filename, "✅ Экспорт завершен")
        
        parts = self._split_text_by_size(text, 45 * 1024 * 1024)
        for i, part in enumerate(parts, 1):
            success = await self._send_single_file(chat_id, task_id, part.encode("utf-8"), f"part{i}_{filename}", f"✅ Часть {i}/{len(parts)}")
            if not success: return False
        return True

    async def _send_single_file(self, chat_id, task_id, file_bytes, filename, caption) -> bool:
        url = f"https://api.telegram.org/bot{self.bot_token}/sendDocument"
        try:
            response = await self._http_client.post(
                url,
                data={"chat_id": chat_id, "caption": caption},
                files={"document": (filename, file_bytes, "text/plain")},
            )
            return response.status_code == 200
        except Exception as e:
            logger.error(f"Telegram upload failed: {e}")
            return False

    def _split_text_by_size(self, text: str, max_bytes: int) -> list[str]:
        parts, lines, current_part, current_size = [], text.split("\n"), [], 0
        for line in lines:
            line_bytes = len(line.encode("utf-8")) + 1
            if current_size + line_bytes > max_bytes and current_part:
                parts.append("\n".join(current_part))
                current_part, current_size = [], 0
            current_part.append(line)
            current_size += line_bytes
        if current_part: parts.append("\n".join(current_part))
        return parts

    async def verify_connectivity(self) -> bool:
        try:
            resp = await self._http_client.get(f"{self.base_url}/api/health")
            return resp.status_code == 200
        except Exception:
            return False

    async def notify_user_failure(self, chat_id, task_id, error):
        url = f"https://api.telegram.org/bot{self.bot_token}/sendMessage"
        text = f"❌ Export failed (task {task_id})\n\nReason: {error}"
        try:
            await self._http_client.post(url, data={"chat_id": chat_id, "text": text})
        except Exception as e:
            logger.warning(f"Failed to notify user {chat_id} about failure: {e}")

    async def notify_empty_export(self, chat_id, task_id, from_date, to_date):
        url = f"https://api.telegram.org/bot{self.bot_token}/sendMessage"
        period = self._format_period(from_date, to_date)
        if period:
            text = (
                f"ℹ️ За {period} в чате не найдено ни одного сообщения.\n"
                f"Попробуйте расширить диапазон дат или выбрать другой чат."
            )
        else:
            # Экспорт без date-фильтра: префикс «За выбранный период» звучит
            # коряво — убираем его, сообщение короче и честнее.
            text = (
                "ℹ️ В чате не найдено ни одного сообщения.\n"
                "Возможно, стоит выбрать другой чат."
            )
        try:
            await self._http_client.post(url, data={"chat_id": chat_id, "text": text})
        except Exception as e:
            logger.warning(f"Failed to notify user {chat_id} about empty export: {e}")

    @staticmethod
    def _format_period(from_date: Optional[str], to_date: Optional[str]) -> Optional[str]:
        if from_date and to_date:
            return f"период {from_date[:10]} — {to_date[:10]}"
        if from_date:
            return f"период с {from_date[:10]}"
        if to_date:
            return f"период до {to_date[:10]}"
        return None

    def create_progress_tracker(self, user_chat_id: int, task_id: str,
                                topic_name: Optional[str] = None):
        return ProgressTracker(self, user_chat_id, task_id, topic_name=topic_name)

    async def update_queue_position(
        self,
        user_chat_id: int,
        msg_id: int,
        position: int,
        total: int,
    ) -> None:
        if not self.bot_token:
            return

        if position == 0:
            text = "⏳ Ваш экспорт начался..."
        else:
            text = f"🕐 Вы в очереди: позиция {position}/{total}. Ожидайте..."

        try:
            url = f"https://api.telegram.org/bot{self.bot_token}/editMessageText"
            await self._http_client.post(
                url,
                data={
                    "chat_id": user_chat_id,
                    "message_id": msg_id,
                    "text": text,
                },
            )
        except Exception as e:
            logger.debug("Failed to update queue position: %s", e)

    async def aclose(self) -> None:
        await self._http_client.aclose()

    @staticmethod
    def _build_progress_bar(pct: int, width: int = 10) -> str:
        clamped = max(0, min(pct, 100))
        filled = (clamped * width) // 100
        return "▓" * filled + "░" * (width - filled)

    async def send_progress_update(
        self,
        user_chat_id,
        task_id,
        message_count,
        total=None,
        started=False,
        progress_message_id=None,
        eta_text: Optional[str] = None,
        topic_name: Optional[str] = None,
    ):
        if total is not None:
            # total может быть 0 (Telegram ещё не посчитал) — показываем 0% вместо спиннера.
            # "N из M" вместо "N/M" чтобы Telegram не превращал в ссылку на телефон.
            pct = min(message_count * 100 // total, 100) if total > 0 else 0
            bar = self._build_progress_bar(pct)
            text = f"📊 {bar} {pct}% ({message_count} из {total})"
            if eta_text:
                text += f"   ~{eta_text}"
        elif started:
            text = "⏳ Экспорт начался..."
        else:
            text = f"📊 Экспортировано {message_count}..."
        if topic_name:
            text += f"\nТопик: {topic_name}"

        try:
            if progress_message_id:
                url = f"https://api.telegram.org/bot{self.bot_token}/editMessageText"
                resp = await self._http_client.post(
                    url,
                    data={
                        "chat_id": user_chat_id,
                        "message_id": progress_message_id,
                        "text": text,
                    },
                )
                return progress_message_id if resp.status_code == 200 else None
            else:
                url = f"https://api.telegram.org/bot{self.bot_token}/sendMessage"
                resp = await self._http_client.post(
                    url,
                    data={"chat_id": user_chat_id, "text": text},
                )
                if resp.status_code == 200:
                    return resp.json().get("result", {}).get("message_id")
                return None
        except Exception:
            return None

# Progress-tracker tuning constants
_PROGRESS_STEP_PCT = 5            # notify whenever pct grows by at least this much
_PROGRESS_MIN_INTERVAL_SEC = 3.0  # hard anti-spam: never faster than this between edits
_ETA_MIN_ELAPSED_SEC = 5.0        # skip ETA while rate estimate is still noisy
_ETA_MIN_FRESH_COUNT = 100        # same: require some headcount before trusting rate

def _format_eta(seconds: float) -> Optional[str]:
    if seconds <= 0:
        return None
    sec = int(seconds)
    if sec < 60:
        return f"{sec} сек"
    if sec < 3600:
        return f"{sec // 60} мин"
    hours = sec // 3600
    mins = (sec % 3600) // 60
    return f"{hours} ч {mins} мин" if mins else f"{hours} ч"

class ProgressTracker:

    def __init__(self, client, user_chat_id, task_id, topic_name: Optional[str] = None):
        self._client = client
        self._user_chat_id = user_chat_id
        self._task_id = task_id
        self._topic_name = topic_name
        self._message_id: Optional[int] = None
        self._total: Optional[int] = None
        self._last_reported_pct = 0
        self._last_reported_at = 0.0
        self._start_time = 0.0
        self._baseline_count = 0
        self._last_count: int = 0

    async def start(self, total=None):
        self._total = total
        self._start_time = time.time()
        # Инициализируем в now, а не в 0 — иначе первый track() всегда emit
        # (time_delta = now - 0 ≈ 1.7B >> _PROGRESS_MIN_INTERVAL_SEC), что
        # сдвигает все 5%-пороги на 1 (1,6,11,...), ломая тест-ожидания.
        self._last_reported_at = time.time()
        self._last_reported_pct = 0
        self._baseline_count = 0
        self._message_id = await self._client.send_progress_update(
            self._user_chat_id, self._task_id, 0, total=total, started=True,
            topic_name=self._topic_name,
        )

    async def set_total(self, total):
        # total=0 означает что Telegram не смог подсчитать — трактуем как неизвестный total.
        # Иначе прогресс-бар навсегда застрянет на "0 из 0" пока воркер качает сообщения.
        if not total:
            return
        self._total = total
        if self._message_id:
            await self._client.send_progress_update(
                self._user_chat_id,
                self._task_id,
                message_count=self._baseline_count,
                total=total,
                progress_message_id=self._message_id,
                topic_name=self._topic_name,
            )

    async def seed(self, cached_count: int) -> None:
        if not self._total or cached_count <= 0:
            return
        self._baseline_count = cached_count
        self._start_time = time.time()  # ETA timer starts fresh from "now"
        self._last_reported_at = self._start_time
        self._last_reported_pct = min(cached_count * 100 // self._total, 100)
        mid = await self._client.send_progress_update(
            self._user_chat_id,
            self._task_id,
            cached_count,
            self._total,
            progress_message_id=self._message_id,
            topic_name=self._topic_name,
        )
        if mid:
            self._message_id = mid

    async def track(self, count):
        self._last_count = count
        if not self._total:
            return
        now = time.time()
        pct = min(count * 100 // self._total, 100) if self._total > 0 else 0

        # 100% сообщение отправляет finalize(), не track() — иначе будет
        # дублирование: track(N==total) + finalize() оба emit при pct=100.
        if pct >= 100:
            return

        pct_delta = pct - self._last_reported_pct
        time_delta = now - self._last_reported_at
        # Throttle: emit only when pct moved enough OR enough wall-clock passed.
        if pct_delta < _PROGRESS_STEP_PCT and time_delta < _PROGRESS_MIN_INTERVAL_SEC:
            return
        if pct_delta <= 0:
            return

        self._last_reported_pct = pct
        self._last_reported_at = now

        # ETA: only when rate estimate is trustworthy AND we're not at the finish line
        eta_text: Optional[str] = None
        elapsed = now - self._start_time
        fresh = count - self._baseline_count
        if (
            fresh >= _ETA_MIN_FRESH_COUNT
            and elapsed >= _ETA_MIN_ELAPSED_SEC
            and count < self._total
        ):
            rate = fresh / elapsed  # messages per second in this session
            if rate > 0:
                remaining = self._total - count
                eta_text = _format_eta(remaining / rate)

        mid = await self._client.send_progress_update(
            self._user_chat_id,
            self._task_id,
            count,
            self._total,
            progress_message_id=self._message_id,
            eta_text=eta_text,
            topic_name=self._topic_name,
        )
        if mid:
            self._message_id = mid

    async def on_floodwait(self, wait_seconds: int) -> None:
        if not self._message_id:
            return
        await self._client.send_progress_update(
            self._user_chat_id,
            self._task_id,
            message_count=self._last_count,
            total=self._total,
            progress_message_id=self._message_id,
            eta_text=f"⏳ Telegram: ожидание ~{wait_seconds}с",
            topic_name=self._topic_name,
        )

    async def finalize(self, count):
        final_total = max(self._total or count, count)
        await self._client.send_progress_update(
            self._user_chat_id,
            self._task_id,
            count,
            final_total,
            progress_message_id=self._message_id,
            topic_name=self._topic_name,
        )

async def create_java_client() -> JavaBotClient:
    client = JavaBotClient()
    if not await client.verify_connectivity():
        raise RuntimeError(f"Cannot reach Java API at {settings.JAVA_API_BASE_URL}")
    return client
