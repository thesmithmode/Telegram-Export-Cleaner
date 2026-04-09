"""
Java API Client: Optimized for massive exports (250k+ messages).
Memory Strategy: O(1) Streaming.
"""

import json
import logging
import asyncio
import re
import os
import tempfile
from datetime import datetime
from typing import Optional, Union, AsyncIterator

import httpx

from config import settings
from models import ExportedMessage

logger = logging.getLogger(__name__)


class JavaBotClient:
    """Uploads exported messages to Java API using disk-based streaming."""

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
            write=None,
            connect=30.0
        )
        self._http_client = httpx.AsyncClient(timeout=custom_timeout)
        logger.info(f"Java API Client initialized (O(1) Memory, Timeout: {self.timeout}s)")

    async def send_response(
        self,
        task_id: str,
        status: str,
        messages: Union[list[ExportedMessage], AsyncIterator[ExportedMessage]],
        actual_count: int = 0,
        error: Optional[str] = None,
        error_code: Optional[str] = None,
        user_chat_id: Optional[int] = None,
        chat_title: Optional[str] = None,
        from_date: Optional[str] = None,
        to_date: Optional[str] = None,
        keywords: Optional[str] = None,
        exclude_keywords: Optional[str] = None,
    ) -> bool:
        """
        Processes export results by streaming to Java and back to Telegram.
        """
        if status == "failed":
            if error and user_chat_id and self.bot_token:
                await self._notify_user_failure(user_chat_id, task_id, error)
            return True

        # 1. Stream messages directly to a temporary file on disk (Memory O(1))
        tmp_path = await self._stream_to_temp_json(messages, actual_count)
        
        try:
            file_size_mb = os.path.getsize(tmp_path) / (1024 * 1024)
            logger.info(f"📤 Streaming {file_size_mb:.2f} MB from disk (task {task_id})")

            # 2. Upload using httpx streaming capabilities
            cleaned_text = await self._upload_file_to_java(
                tmp_path,
                from_date=from_date,
                to_date=to_date,
                keywords=keywords,
                exclude_keywords=exclude_keywords
            )
        finally:
            if os.path.exists(tmp_path):
                os.unlink(tmp_path)

        if cleaned_text is None:
            logger.error(f"❌ Java API processing failed for task {task_id}")
            if user_chat_id and self.bot_token:
                await self._notify_user_failure(
                    user_chat_id, task_id, "Processing service unavailable"
                )
            return False

        # 3. Deliver cleaned text to user
        if user_chat_id and self.bot_token:
            filename = self._build_filename(chat_title, from_date, to_date)
            sent = await self._send_file_to_user(
                user_chat_id, task_id, cleaned_text, filename=filename
            )
            if not sent:
                await self._notify_user_failure(
                    user_chat_id, task_id, "Не удалось отправить файл. Попробуйте снова."
                )
                return False
        
        return True

    async def _stream_to_temp_json(
        self,
        messages: Union[list[ExportedMessage], AsyncIterator[ExportedMessage]],
        count: int
    ) -> str:
        """Writes messages to result.json format on disk, one by one."""
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
            if os.path.exists(tmp_path): os.unlink(tmp_path)
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
    ) -> Optional[str]:
        """POSTs a file from disk using Multipart streaming."""
        url = f"{self.base_url}/api/convert"
        
        headers = {}
        if settings.JAVA_API_KEY:
            headers["X-API-Key"] = settings.JAVA_API_KEY

        data = {}
        if from_date: data["startDate"] = from_date[:10]
        if to_date: data["endDate"] = to_date[:10]
        if keywords: data["keywords"] = keywords
        if exclude_keywords: data["excludeKeywords"] = exclude_keywords

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
                        headers=headers,
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
        """UTF-16 safe extraction for Telegram entities."""
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
        except: return entities

    @staticmethod
    def _sanitize_filename(name: str) -> str:
        name = re.sub(r'[^\w\s\-.]', '', name)
        return re.sub(r'\s+', '_', name.strip())[:80] or "export"

    def _build_filename(self, title, f_date, t_date) -> str:
        base = self._sanitize_filename(title) if title else "export"
        if f_date and t_date: return f"{base}_{f_date[:10]}_{t_date[:10]}.txt"
        return f"{base}_all.txt"

    async def _send_file_to_user(self, chat_id, task_id, text, filename) -> bool:
        url = f"https://api.telegram.org/bot{self.bot_token}/sendDocument"
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
        except: return False

    async def _notify_user_failure(self, chat_id, task_id, error):
        url = f"https://api.telegram.org/bot{self.bot_token}/sendMessage"
        text = f"❌ Export failed (task {task_id})\n\nReason: {error}"
        try: await self._http_client.post(url, data={"chat_id": chat_id, "text": text})
        except: pass

    async def _notify_user_empty(self, chat_id, task_id):
        url = f"https://api.telegram.org/bot{self.bot_token}/sendMessage"
        text = f"ℹ️ Экспорт завершён ({task_id})\n\nСообщений не найдено."
        try: await self._http_client.post(url, data={"chat_id": chat_id, "text": text})
        except: pass

    def create_progress_tracker(self, user_chat_id: int, task_id: str):
        from java_client import ProgressTracker
        return ProgressTracker(self, user_chat_id, task_id)

    async def send_progress_update(self, user_chat_id, task_id, message_count, total=None, started=False, elapsed_seconds=0, progress_message_id=None):
        if total:
            pct = min(message_count * 100 // total, 100) if total > 0 else 0
            text = f"📊 {'▓' * (pct//10)}{'░' * (10-(pct//10))} {pct}% ({message_count}/{total})"
        elif started: text = "⏳ Экспорт начался..."
        else: text = f"📊 Экспортировано {message_count}..."

        try:
            if progress_message_id:
                url = f"https://api.telegram.org/bot{self.bot_token}/editMessageText"
                resp = await self._http_client.post(url, data={"chat_id": user_chat_id, "message_id": progress_message_id, "text": text})
                return progress_message_id if resp.status_code == 200 else None
            else:
                url = f"https://api.telegram.org/bot{self.bot_token}/sendMessage"
                resp = await self._http_client.post(url, data={"chat_id": user_chat_id, "text": text})
                return resp.json().get("result", {}).get("message_id") if resp.status_code == 200 else None
        except: return None

class ProgressTracker:
    def __init__(self, client, user_chat_id, task_id):
        self._client, self._user_chat_id, self._task_id = client, user_chat_id, task_id
        self._message_id, self._total, self._last_reported_pct = None, None, 0
    
    async def start(self, total=None):
        self._total = total
        self._message_id = await self._client.send_progress_update(self._user_chat_id, self._task_id, 0, total, True)

    async def track(self, count):
        if not self._total: return
        pct = count * 100 // self._total
        if pct < self._last_reported_pct + 5: return
        self._last_reported_pct = pct
        mid = await self._client.send_progress_update(self._user_chat_id, self._task_id, count, self._total, False, 0, self._message_id)
        if mid: self._message_id = mid

    async def finalize(self, count):
        await self._client.send_progress_update(self._user_chat_id, self._task_id, count, self._total or count, False, 0, self._message_id)

    async def update_queue_position(self, user_chat_id: int, msg_id: int, position: int, total: int):
        """Редактирует сообщение в очереди: позиция 0 = задание начато, иначе — место в очереди."""
        if not self.bot_token:
            return
        if position == 0:
            text = "⏳ Ваш экспорт начался..."
        else:
            text = f"🕐 Вы в очереди: позиция {position}/{total}. Ожидайте..."
        try:
            url = f"https://api.telegram.org/bot{self.bot_token}/editMessageText"
            await self._http_client.post(url, data={
                "chat_id": user_chat_id,
                "message_id": msg_id,
                "text": text,
            })
        except Exception:
            pass

    async def aclose(self):
        """Закрывает httpx клиент."""
        await self._http_client.aclose()


async def create_java_client() -> JavaBotClient:
    client = JavaBotClient()
    if not await client.verify_connectivity():
        raise RuntimeError(f"Cannot reach Java API at {settings.JAVA_API_BASE_URL}")
    return client
