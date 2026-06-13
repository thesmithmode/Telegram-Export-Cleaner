
import json
import logging
import asyncio
import codecs
import re
import os
import shutil
import tempfile
import time
import unicodedata
from datetime import datetime
from typing import Optional, Union, AsyncIterator

import httpx

from config import settings
from models import ExportedMessage, SendResponsePayload
from text_format import (
    format_cached_message_line,
    format_java_export_date,
    parse_keywords,
)

logger = logging.getLogger(__name__)

TELEGRAM_MAX_FILE_SIZE_BYTES = 45 * 1024 * 1024
_DISK_FREE_CHECK_INTERVAL_BYTES = 16 * 1024 * 1024
_CONVERT_SENTINEL = b"\n##OK##"
_NEWLINE_RE = re.compile(r"\r\n|\r|\n")

_BOT_TOKEN_RE = re.compile(r'/bot[^/]+/')
_HTTP_LOG_RECORD_FACTORY_INSTALLED = False
_ORIGINAL_LOG_RECORD_FACTORY = logging.getLogRecordFactory()

def _safe_err(e: Exception) -> str:
    return _BOT_TOKEN_RE.sub('/bot<REDACTED>/', str(e))


class _BotTokenRedactionFilter(logging.Filter):
    def filter(self, record: logging.LogRecord) -> bool:
        record.msg = _BOT_TOKEN_RE.sub('/bot<REDACTED>/', record.getMessage())
        record.args = ()
        return True


def configure_httpx_logging() -> None:
    global _HTTP_LOG_RECORD_FACTORY_INSTALLED
    if not _HTTP_LOG_RECORD_FACTORY_INSTALLED:
        def _redacting_record_factory(*args, **kwargs):
            record = _ORIGINAL_LOG_RECORD_FACTORY(*args, **kwargs)
            if record.name == "httpx" or record.name.startswith("httpx."):
                record.msg = _BOT_TOKEN_RE.sub('/bot<REDACTED>/', record.getMessage())
                record.args = ()
            elif record.name == "httpcore" or record.name.startswith("httpcore."):
                record.msg = _BOT_TOKEN_RE.sub('/bot<REDACTED>/', record.getMessage())
                record.args = ()
            return record

        logging.setLogRecordFactory(_redacting_record_factory)
        _HTTP_LOG_RECORD_FACTORY_INSTALLED = True

    for logger_name in ("httpx", "httpcore"):
        target_logger = logging.getLogger(logger_name)
        if not any(isinstance(f, _BotTokenRedactionFilter) for f in target_logger.filters):
            target_logger.addFilter(_BotTokenRedactionFilter())


configure_httpx_logging()

class JavaBotClient:

    def __init__(self, timeout: int = 3600, max_retries: int = 3):
        self.base_url = settings.JAVA_API_BASE_URL.rstrip("/")
        self.timeout = timeout
        self.max_retries = max_retries
        self.bot_token = settings.TELEGRAM_BOT_TOKEN

        # /api/convert: write фаза — multi-GB upload (write=300s между чанками,
        # не суммарно). read=None — Java может долго парсить и стримить cleaned-text
        # обратно; точечного нижнего предела на read нет, suggested timeout
        # покрывается общим timeout=self.timeout.
        custom_timeout = httpx.Timeout(
            timeout=float(self.timeout),
            read=None,
            write=300.0,
            connect=30.0
        )
        default_headers = {}
        if settings.JAVA_API_KEY:
            default_headers["X-API-Key"] = settings.JAVA_API_KEY
        self._http_client = httpx.AsyncClient(timeout=custom_timeout, headers=default_headers)
        # Отдельный клиент для Telegram Bot API: ограниченное чтение, чтобы зависший
        # сетевой запрос не повисал бесконечно (read=None из основного клиента не подходит).
        self._tg_timeout = httpx.Timeout(timeout=300.0, read=300.0, write=300.0, connect=30.0)
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

            # 2. Upload using httpx streaming capabilities and keep the converted
            # response on disk. Do not materialize large exports in worker memory.
            cleaned_path = await self._upload_file_to_java(
                tmp_path,
                from_date=payload.from_date,
                to_date=payload.to_date,
                keywords=payload.keywords,
                exclude_keywords=payload.exclude_keywords,
                task_id=payload.task_id,
                bot_user_id=payload.user_id,
                chat_title=payload.chat_title,
                messages_count=payload.actual_count,
                subscription_id=payload.subscription_id,
            )
        finally:
            try:
                os.unlink(tmp_path)
            except FileNotFoundError:
                pass

        if cleaned_path is None:
            logger.error(f"❌ Java API processing failed for task {payload.task_id}")
            if payload.user_chat_id and self.bot_token:
                await self.notify_user_failure(
                    payload.user_chat_id, payload.task_id, "Processing service unavailable"
                )
            return False

        try:
            # Защита от невидимых non-whitespace символов (BOM, ZWSP, ZWJ, форматирование):
            # Java-фильтр пропускает их, Telegram отклоняет sendDocument с пустым телом.
            if await self._file_is_effectively_empty(cleaned_path):
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
                    payload.chat_title,
                    payload.from_date,
                    payload.to_date,
                    payload.chat_username,
                )
                sent = await self._send_file_path_to_user(
                    payload.user_chat_id, payload.task_id, cleaned_path, filename=filename
                )
                if not sent:
                    await self.notify_user_failure(
                        payload.user_chat_id, payload.task_id,
                        "Не удалось отправить файл. Попробуйте снова."
                    )
                    return False

            return True
        finally:
            try:
                os.unlink(cleaned_path)
            except FileNotFoundError:
                pass

    async def send_cached_response_direct(
        self,
        payload: SendResponsePayload,
        cache=None,
        cache_context: Optional[dict] = None,
    ) -> tuple[bool, Optional[int]]:
        if payload.status != "completed" or payload.actual_count == 0:
            return await self.send_response(payload), None

        started_at = time.monotonic()
        artifact_eligible = self._is_full_artifact_eligible(payload, cache, cache_context)
        if artifact_eligible:
            artifact_lookup_started = time.monotonic()
            artifact = await cache.get_full_export_artifact(
                cache_context["chat_id"],
                cache_context.get("topic_id", 0),
                cache_context["coverage_max_id"],
                cache_context["message_count"],
            )
            logger.info(
                "Task %s: artifact exact lookup took %.3fs hit=%s",
                payload.task_id,
                time.monotonic() - artifact_lookup_started,
                artifact is not None,
            )
            if artifact is not None:
                artifact_path, artifact_size = artifact
                upload_started = time.monotonic()
                if payload.user_chat_id and self.bot_token:
                    filename = self._build_filename(
                        payload.chat_title,
                        payload.from_date,
                        payload.to_date,
                        payload.chat_username,
                    )
                    sent = await self._send_file_path_to_user(
                        payload.user_chat_id,
                        payload.task_id,
                        artifact_path,
                        filename=filename,
                    )
                    if not sent:
                        await self.notify_user_failure(
                            payload.user_chat_id, payload.task_id,
                            "Не удалось отправить файл. Попробуйте снова."
                        )
                        return False, artifact_size
                logger.info(
                    "Task %s: sent cached export artifact size=%d upload=%.3fs total=%.3fs",
                    payload.task_id,
                    artifact_size,
                    time.monotonic() - upload_started,
                    time.monotonic() - started_at,
                )
                return True, artifact_size

            cleaned_path = await self._try_extend_full_export_artifact(
                payload,
                cache,
                cache_context,
            )
        else:
            cleaned_path = None

        if cleaned_path is None:
            stream_started = time.monotonic()
            cleaned_path = await self._stream_messages_to_cleaned_text(
                payload,
                cache=cache,
                cache_context=cache_context,
            )
            logger.info(
                "Task %s: direct export stream stage took %.3fs",
                payload.task_id,
                time.monotonic() - stream_started,
            )
        if cleaned_path is None:
            logger.info(
                "Task %s: direct cached export returned empty text "
                "(actual_count=%s)",
                payload.task_id,
                payload.actual_count,
            )
            if payload.user_chat_id and self.bot_token:
                await self.notify_empty_export(
                    payload.user_chat_id,
                    payload.task_id,
                    payload.from_date,
                    payload.to_date,
                )
            return True, 0

        try:
            count_started = time.monotonic()
            bytes_count = self._count_export_text_bytes(cleaned_path)
            logger.info(
                "Task %s: direct export byte count took %.3fs bytes=%d",
                payload.task_id,
                time.monotonic() - count_started,
                bytes_count,
            )
            if await self._file_is_effectively_empty(cleaned_path):
                logger.info(
                    "Task %s: direct cached export is effectively empty "
                    "(actual_count=%s)",
                    payload.task_id,
                    payload.actual_count,
                )
                if payload.user_chat_id and self.bot_token:
                    await self.notify_empty_export(
                        payload.user_chat_id,
                        payload.task_id,
                        payload.from_date,
                        payload.to_date,
                    )
                return True, 0

            if payload.user_chat_id and self.bot_token:
                filename = self._build_filename(
                    payload.chat_title,
                    payload.from_date,
                    payload.to_date,
                    payload.chat_username,
                )
                upload_started = time.monotonic()
                sent = await self._send_file_path_to_user(
                    payload.user_chat_id, payload.task_id, cleaned_path, filename=filename
                )
                logger.info(
                    "Task %s: Telegram document upload took %.3fs",
                    payload.task_id,
                    time.monotonic() - upload_started,
                )
                if not sent:
                    await self.notify_user_failure(
                        payload.user_chat_id, payload.task_id,
                        "Не удалось отправить файл. Попробуйте снова."
                    )
                    return False, bytes_count
            if artifact_eligible:
                try:
                    save_started = time.monotonic()
                    await cache.save_full_export_artifact(
                        chat_id=cache_context["chat_id"],
                        topic_id=cache_context.get("topic_id", 0),
                        coverage_max_id=cache_context["coverage_max_id"],
                        message_count=cache_context["message_count"],
                        source_path=cleaned_path,
                    )
                    logger.info(
                        "Task %s: export artifact save took %.3fs",
                        payload.task_id,
                        time.monotonic() - save_started,
                    )
                except Exception as exc:
                    logger.warning(
                        "Task %s: export artifact save skipped: %s",
                        payload.task_id,
                        _safe_err(exc),
                    )
            logger.info(
                "Task %s: direct cached export total took %.3fs",
                payload.task_id,
                time.monotonic() - started_at,
            )
            return True, bytes_count
        finally:
            try:
                os.unlink(cleaned_path)
            except FileNotFoundError:
                pass

    @staticmethod
    def _is_full_artifact_eligible(
        payload: SendResponsePayload,
        cache,
        cache_context: Optional[dict],
    ) -> bool:
        if cache is None or not cache_context:
            return False
        if not cache_context.get("full_export"):
            return False
        if payload.from_date or payload.to_date or payload.keywords or payload.exclude_keywords:
            return False
        return (
            cache_context.get("coverage_max_id") is not None
            and cache_context.get("message_count") is not None
        )

    async def _try_extend_full_export_artifact(
        self,
        payload: SendResponsePayload,
        cache,
        cache_context: dict,
    ) -> Optional[str]:
        getter = getattr(cache, "get_latest_full_export_artifact", None)
        if not callable(getter):
            return None
        lookup_started = time.monotonic()
        previous = await getter(
            cache_context["chat_id"],
            cache_context.get("topic_id", 0),
            cache_context["coverage_max_id"],
            cache_context["message_count"],
        )
        logger.info(
            "Task %s: artifact base lookup took %.3fs hit=%s",
            payload.task_id,
            time.monotonic() - lookup_started,
            previous is not None,
        )
        if previous is None:
            return None

        artifact_path, artifact_size, previous_max_id, previous_count = previous
        current_max_id = int(cache_context["coverage_max_id"])
        current_count = int(cache_context["message_count"])
        if previous_max_id >= current_max_id or previous_count > current_count:
            return None

        fd, output_path = self._mkstemp(suffix=".txt", prefix="tg_direct_inc_")
        appended = 0
        started = time.monotonic()
        try:
            with os.fdopen(fd, "wb") as out, open(artifact_path, "rb") as src:
                shutil.copyfileobj(src, out, length=1024 * 1024)
                if artifact_size > 0:
                    src.seek(-1, os.SEEK_END)
                    if src.read(1) != b"\n":
                        out.write(b"\n")
                lines_iter = cache.iter_export_lines(
                    cache_context["chat_id"],
                    previous_max_id + 1,
                    current_max_id,
                    topic_id=cache_context.get("topic_id", 0),
                )
                async for line in lines_iter:
                    if line is None:
                        continue
                    out.write(line.encode("utf-8"))
                    out.write(b"\n")
                    appended += 1
            logger.info(
                "Task %s: extended export artifact from max_id=%d to %d "
                "with %d lines in %.3fs",
                payload.task_id,
                previous_max_id,
                current_max_id,
                appended,
                time.monotonic() - started,
            )
            return output_path
        except Exception:
            try:
                os.unlink(output_path)
            except FileNotFoundError:
                pass
            raise

    async def _stream_to_temp_json(
        self,
        messages: Union[list[ExportedMessage], AsyncIterator[ExportedMessage]],
        count: int
    ) -> str:
        fd, tmp_path = self._mkstemp(suffix=".json", prefix="tg_stream_")
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

    async def _stream_messages_to_cleaned_text(
        self,
        payload: SendResponsePayload,
        cache=None,
        cache_context: Optional[dict] = None,
    ) -> Optional[str]:
        fd, output_path = self._mkstemp(suffix=".txt", prefix="tg_direct_")
        written = 0
        bytes_since_disk_check = 0
        disk_exhausted = False
        include_keywords = self._parse_keywords(payload.keywords)
        exclude_keywords = self._parse_keywords(payload.exclude_keywords)
        try:
            if not self._has_free_disk_for_write(
                output_path,
                _DISK_FREE_CHECK_INTERVAL_BYTES,
            ):
                os.close(fd)
                try:
                    os.unlink(output_path)
                except FileNotFoundError:
                    pass
                raise OSError(f"Not enough free disk space for direct export task {payload.task_id}")

            lines_iter = None
            if cache is not None and cache_context:
                if cache_context.get("range_type") == "date":
                    lines_iter = cache.iter_export_lines(
                        cache_context["chat_id"],
                        topic_id=cache_context.get("topic_id", 0),
                        from_date=cache_context.get("from_date"),
                        to_date=cache_context.get("to_date"),
                        include_keywords=include_keywords,
                        exclude_keywords=exclude_keywords,
                    )
                else:
                    lines_iter = cache.iter_export_lines(
                        cache_context["chat_id"],
                        cache_context.get("low_id", 0),
                        cache_context.get("high_id", 2 ** 62),
                        topic_id=cache_context.get("topic_id", 0),
                        include_keywords=include_keywords,
                        exclude_keywords=exclude_keywords,
                    )
            msgs_iter = None
            if lines_iter is None:
                msgs_iter = (
                    payload.messages
                    if not isinstance(payload.messages, list)
                    else self._iter_list(payload.messages)
                )
            with os.fdopen(fd, "w", encoding="utf-8", newline="") as out:
                source_iter = lines_iter if lines_iter is not None else msgs_iter
                async for item in source_iter:
                    if lines_iter is not None:
                        line = item
                    else:
                        line = self._format_cached_message_line(
                            item,
                            include_keywords=include_keywords,
                            exclude_keywords=exclude_keywords,
                        )
                    if line is None:
                        continue
                    encoded_len = len(line.encode("utf-8")) + 1
                    bytes_since_disk_check += encoded_len
                    if bytes_since_disk_check >= _DISK_FREE_CHECK_INTERVAL_BYTES:
                        if not self._has_free_disk_for_write(output_path, encoded_len):
                            logger.error("Export temp disk reserve exhausted for task %s", payload.task_id)
                            disk_exhausted = True
                            break
                        bytes_since_disk_check = 0
                    out.write(line)
                    out.write("\n")
                    written += 1
        except Exception:
            try:
                os.unlink(output_path)
            except FileNotFoundError:
                pass
            raise

        if disk_exhausted:
            try:
                os.unlink(output_path)
            except FileNotFoundError:
                pass
            raise OSError(f"Not enough free disk space for direct export task {payload.task_id}")

        if written == 0:
            try:
                os.unlink(output_path)
            except FileNotFoundError:
                pass
            return None

        logger.info("Direct cached export formatted %d lines for task %s", written, payload.task_id)
        return output_path

    @classmethod
    def _format_cached_message_line(
        cls,
        msg: ExportedMessage,
        include_keywords: Optional[list[str]] = None,
        exclude_keywords: Optional[list[str]] = None,
    ) -> Optional[str]:
        return format_cached_message_line(
            msg,
            include_keywords=include_keywords,
            exclude_keywords=exclude_keywords,
        )

    @staticmethod
    def _format_java_export_date(date_str: Optional[str]) -> str:
        return format_java_export_date(date_str)

    @staticmethod
    def _parse_keywords(raw: Optional[str]) -> list[str]:
        return parse_keywords(raw)

    def _mkstemp(self, suffix: str, prefix: str) -> tuple[int, str]:
        temp_dir = settings.EXPORT_TEMP_DIR
        if temp_dir:
            os.makedirs(temp_dir, exist_ok=True)
        return tempfile.mkstemp(suffix=suffix, prefix=prefix, dir=temp_dir or None)

    @staticmethod
    def _count_export_text_bytes(file_path: str) -> int:
        total = 0
        with open(file_path, "r", encoding="utf-8") as f:
            while True:
                chunk = f.read(1024 * 1024)
                if not chunk:
                    break
                total += len(chunk.encode("utf-8"))
        return total

    @staticmethod
    def _min_free_disk_bytes() -> int:
        return max(0, int(settings.EXPORT_MIN_FREE_DISK_MB)) * 1024 * 1024

    def _has_free_disk_for_write(self, path: str, bytes_to_write: int = 0) -> bool:
        required = self._min_free_disk_bytes() + max(0, bytes_to_write)
        if required <= 0:
            return True

        directory = os.path.dirname(path) or "."
        try:
            free = shutil.disk_usage(directory).free
        except OSError as e:
            logger.error("Cannot check free disk space for %s: %s", directory, _safe_err(e))
            return False

        if free < required:
            logger.error(
                "Not enough free disk space for export temp file: free=%dMB required=%dMB dir=%s",
                free // (1024 * 1024),
                required // (1024 * 1024),
                directory,
            )
            return False
        return True

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
        subscription_id: Optional[int] = None,
    ) -> Optional[str]:
        """Upload JSON to Java and stream the cleaned text into a temp file."""
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
        if subscription_id is not None: data["subscriptionId"] = str(subscription_id)

        retry_count = 0
        while retry_count <= self.max_retries:
            output_path = None
            keep_output = False
            try:
                with open(file_path, "rb") as f:
                    files = {"file": ("result.json", f, "application/json")}
                    async with self._http_client.stream(
                        "POST",
                        url,
                        files=files,
                        data=data,
                    ) as response:
                        if response.status_code == 200:
                            output_path = await self._stream_convert_response_to_file(
                                response,
                                task_id=task_id,
                            )
                            if output_path is not None:
                                keep_output = True
                                return output_path
                        else:
                            err = await self._read_response_preview(response)
                            logger.error(f"Java API error {response.status_code}: {err}")
                            if response.status_code == 400:
                                return None

            except (FileNotFoundError, PermissionError) as fs_err:
                # Файл пропал или /tmp read-only — ретрай бесполезен.
                logger.error(f"Export file unavailable {file_path}: {fs_err}")
                return None
            except Exception as e:
                # Прочие OSError (disk full, transient FS issue) и сетевые ошибки → retry.
                logger.error(f"Upload to Java failed (attempt {retry_count + 1}): {_safe_err(e)}")
            finally:
                if output_path is not None and not keep_output:
                    try:
                        os.unlink(output_path)
                    except FileNotFoundError:
                        pass

            retry_count += 1
            if retry_count <= self.max_retries:
                await asyncio.sleep(settings.RETRY_BASE_DELAY * retry_count)

        return None

    async def _stream_convert_response_to_file(
        self,
        response: httpx.Response,
        task_id: Optional[str] = None,
    ) -> Optional[str]:
        fd, output_path = self._mkstemp(suffix=".txt", prefix="tg_cleaned_")
        total_size = 0
        bytes_since_disk_check = 0
        tail = b""
        success = False
        try:
            if not self._has_free_disk_for_write(
                output_path,
                _DISK_FREE_CHECK_INTERVAL_BYTES,
            ):
                os.close(fd)
                return None

            with os.fdopen(fd, "wb") as out:
                async for chunk in response.aiter_bytes():
                    if not chunk:
                        continue
                    total_size += len(chunk)

                    buffered = tail + chunk
                    if len(buffered) > len(_CONVERT_SENTINEL):
                        write_len = len(buffered) - len(_CONVERT_SENTINEL)
                        bytes_since_disk_check += write_len
                        if bytes_since_disk_check >= _DISK_FREE_CHECK_INTERVAL_BYTES:
                            if not self._has_free_disk_for_write(output_path, write_len):
                                logger.error("Export temp disk reserve exhausted for task %s", task_id)
                                return None
                            bytes_since_disk_check = 0
                        out.write(buffered[:write_len])
                        tail = buffered[write_len:]
                    else:
                        tail = buffered

                if tail != _CONVERT_SENTINEL:
                    logger.error(
                        "Java /api/convert response truncated — sentinel missing, "
                        "got %d bytes ending with ...%r",
                        total_size,
                        tail[-50:],
                    )
                    return None

            success = True
            return output_path
        except Exception as e:
            logger.error("Java /api/convert response stream failed for task %s: %s", task_id, _safe_err(e))
            return None
        finally:
            if not success:
                try:
                    os.unlink(output_path)
                except FileNotFoundError:
                    pass

    @staticmethod
    async def _read_response_preview(response: httpx.Response, limit: int = 200) -> str:
        body = bytearray()
        async for chunk in response.aiter_bytes():
            remaining = limit - len(body)
            if remaining <= 0:
                break
            body.extend(chunk[:remaining])
        return body.decode("utf-8", errors="replace")

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

    def _build_filename(self, title, f_date, t_date, username=None) -> str:
        base_source = username or title or "export"
        base = self._sanitize_filename(base_source)
        if f_date and t_date: return f"{base}_{f_date[:10]}_{t_date[:10]}.txt"
        return f"{base}_all.txt"

    async def _send_file_path_to_user(self, chat_id, task_id, file_path, filename) -> bool:
        file_size = os.path.getsize(file_path)

        if file_size <= TELEGRAM_MAX_FILE_SIZE_BYTES:
            with open(file_path, "rb") as f:
                return await self._send_single_file(
                    chat_id,
                    task_id,
                    f,
                    filename,
                    "✅ Экспорт завершен",
                )

        part_no = 0
        async for part_path in self._split_file_by_size(file_path, TELEGRAM_MAX_FILE_SIZE_BYTES):
            part_no += 1
            try:
                with open(part_path, "rb") as f:
                    success = await self._send_single_file(
                        chat_id,
                        task_id,
                        f,
                        f"part{part_no}_{filename}",
                        f"✅ Часть {part_no}",
                    )
                if not success:
                    return False
            finally:
                try:
                    os.unlink(part_path)
                except FileNotFoundError:
                    pass
        return True

    async def _split_file_by_size(self, file_path: str, max_bytes: int):
        fd, part_path = self._mkstemp(suffix=".txt", prefix="tg_part_")
        current_size = 0
        part = None
        try:
            part = os.fdopen(fd, "wb")
            with open(file_path, "rb") as src:
                for line in src:
                    start = 0
                    while start < len(line):
                        remaining = max_bytes - current_size
                        if remaining == 0:
                            part.close()
                            yield part_path
                            fd, part_path = self._mkstemp(suffix=".txt", prefix="tg_part_")
                            part = os.fdopen(fd, "wb")
                            current_size = 0
                            remaining = max_bytes

                        take = min(remaining, len(line) - start)
                        take = self._safe_utf8_cut(line[start:], take)
                        if take == 0:
                            if current_size:
                                part.close()
                                yield part_path
                                fd, part_path = self._mkstemp(suffix=".txt", prefix="tg_part_")
                                part = os.fdopen(fd, "wb")
                                current_size = 0
                                continue
                            take = min(self._utf8_char_width(line[start]), len(line) - start)

                        part.write(line[start:start + take])
                        current_size += take
                        start += take

            part.close()
            if current_size:
                yield part_path
            else:
                os.unlink(part_path)
        except Exception:
            try:
                part.close()
            except Exception:
                pass
            try:
                os.unlink(part_path)
            except FileNotFoundError:
                pass
            raise

    @staticmethod
    def _safe_utf8_cut(data: bytes, max_len: int) -> int:
        cut = min(max_len, len(data))
        while cut > 0 and cut < len(data) and (data[cut] & 0b1100_0000) == 0b1000_0000:
            cut -= 1
        return cut

    @staticmethod
    def _utf8_char_width(first_byte: int) -> int:
        if first_byte < 0b1000_0000:
            return 1
        if (first_byte & 0b1110_0000) == 0b1100_0000:
            return 2
        if (first_byte & 0b1111_0000) == 0b1110_0000:
            return 3
        if (first_byte & 0b1111_1000) == 0b1111_0000:
            return 4
        return 1

    async def _file_is_effectively_empty(self, file_path: str) -> bool:
        if os.path.getsize(file_path) == 0:
            return True

        decoder = codecs.getincrementaldecoder("utf-8")("replace")
        with open(file_path, "rb") as f:
            while True:
                chunk = f.read(1024 * 1024)
                if not chunk:
                    break
                text = decoder.decode(chunk)
                if any(
                    unicodedata.category(c) not in ("Cc", "Cf", "Zs", "Zl", "Zp")
                    for c in text
                ):
                    return False

        tail = decoder.decode(b"", final=True)
        return not any(
            unicodedata.category(c) not in ("Cc", "Cf", "Zs", "Zl", "Zp")
            for c in tail
        )


    async def _send_single_file(self, chat_id, task_id, document, filename, caption) -> bool:
        url = f"https://api.telegram.org/bot{self.bot_token}/sendDocument"
        try:
            response = await self._http_client.post(
                url,
                data={"chat_id": chat_id, "caption": caption},
                files={"document": (filename, document, "text/plain")},
                timeout=self._tg_timeout,
            )
            return response.status_code == 200
        except Exception as e:
            logger.error(f"Telegram upload failed: {_safe_err(e)}")
            return False

    async def verify_connectivity(self) -> bool:
        try:
            resp = await self._http_client.get(f"{self.base_url}/api/health")
            return resp.status_code == 200
        except Exception as e:
            logger.debug("Java API connectivity check failed: %s", e)
            return False

    async def notify_user_failure(self, chat_id, task_id, error):
        url = f"https://api.telegram.org/bot{self.bot_token}/sendMessage"
        text = f"❌ Export failed (task {task_id})\n\nReason: {error}"
        try:
            await self._http_client.post(
                url, data={"chat_id": chat_id, "text": text}, timeout=self._tg_timeout
            )
        except Exception as e:
            logger.warning(f"Failed to notify user {chat_id} about failure: {_safe_err(e)}")

    async def notify_subscription_empty(
        self, chat_id: int, chat_label: str, from_date: Optional[str], to_date: Optional[str]
    ) -> None:
        """Уведомить пользователя об итерации подписки без новых сообщений."""
        url = f"https://api.telegram.org/bot{self.bot_token}/sendMessage"
        from_str = self._format_date_human(from_date)
        to_str = self._format_date_human(to_date)
        text = (
            f"ℹ️ Итерация подписки по чату {chat_label} выполнена. "
            f"Новых сообщений в окне {from_str} → {to_str} нет."
        )
        try:
            response = await self._http_client.post(
                url, data={"chat_id": chat_id, "text": text}, timeout=self._tg_timeout
            )
            if response.status_code != 200:
                logger.warning(
                    f"Bot API rejected subscription notification for {chat_id}: "
                    f"{response.status_code} {response.text[:200]}"
                )
        except Exception as e:
            logger.warning(f"Failed to notify user {chat_id} about empty subscription: {_safe_err(e)}")

    @staticmethod
    def _format_date_human(date_str: Optional[str]) -> str:
        """Преобразует ISO-строку даты в человекочитаемый формат 'YYYY-MM-DD HH:MM UTC'."""
        if not date_str:
            return "—"
        for fmt in ("%Y-%m-%dT%H:%M:%S", "%Y-%m-%dT%H:%M", "%Y-%m-%d"):
            try:
                dt = datetime.strptime(date_str, fmt)
                return dt.strftime("%Y-%m-%d %H:%M UTC")
            except ValueError:
                continue
        return date_str

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
            await self._http_client.post(
                url, data={"chat_id": chat_id, "text": text}, timeout=self._tg_timeout
            )
        except Exception as e:
            logger.warning(f"Failed to notify user {chat_id} about empty export: {_safe_err(e)}")

    @staticmethod
    def _format_period(from_date: Optional[str], to_date: Optional[str]) -> Optional[str]:
        # NOTE: _format_date_human существует рядом и тоже форматирует даты, но с
        # точностью до HH:MM UTC. Здесь намеренно используется [:10] (только дата)
        # для компактного текста "За период X — Y". Объединять не стоит: контракты разные.
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
                timeout=self._tg_timeout,
            )
        except Exception as e:
            logger.debug("Failed to update queue position: %s", _safe_err(e))

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
        counting=False,
        progress_message_id=None,
        eta_text: Optional[str] = None,
        topic_name: Optional[str] = None,
    ):
        if counting:
            text = "🔢 Определяю количество сообщений..."
        elif total is not None:
            # total может быть 0 (Telegram ещё не посчитал) — показываем 0% вместо спиннера.
            # "N из M" вместо "N/M" чтобы Telegram не превращал в ссылку на телефон.
            display_count = min(message_count, total) if total > 0 else message_count
            pct = min(message_count * 100 // total, 100) if total > 0 else 0
            bar = self._build_progress_bar(pct)
            text = f"📊 {bar} {pct}% ({display_count} из {total})"
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
                    timeout=self._tg_timeout,
                )
                return progress_message_id if resp.status_code == 200 else None
            else:
                url = f"https://api.telegram.org/bot{self.bot_token}/sendMessage"
                resp = await self._http_client.post(
                    url,
                    data={"chat_id": user_chat_id, "text": text},
                    timeout=self._tg_timeout,
                )
                if resp.status_code == 200:
                    return resp.json().get("result", {}).get("message_id")
                return None
        except Exception as e:
            logger.warning("Telegram progress edit/send failed: %s", _safe_err(e))
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
        self._total_adjustment_logged = False

    def _raise_total_to_observed(self, count: int) -> None:
        if self._total is not None and count > self._total:
            if not self._total_adjustment_logged:
                logger.info(
                    "Progress total estimate adjusted for task %s: total=%d observed=%d",
                    self._task_id,
                    self._total,
                    count,
                )
                self._total_adjustment_logged = True
            self._total = count

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
        # total=0/None — Telegram не смог подсчитать. Чтобы не зависнуть на
        # "🔢 Определяю количество сообщений..." на всё время экспорта, переключаем
        # на started-сообщение ("⏳ Экспорт начался...") — пользователь видит, что
        # воркер не застрял, а дальше track() будет слать "📊 Экспортировано N...".
        if not total:
            if self._message_id:
                await self._client.send_progress_update(
                    self._user_chat_id,
                    self._task_id,
                    message_count=self._baseline_count,
                    started=True,
                    progress_message_id=self._message_id,
                    topic_name=self._topic_name,
                )
            return
        self._total = total
        self._raise_total_to_observed(self._last_count)
        if self._message_id:
            await self._client.send_progress_update(
                self._user_chat_id,
                self._task_id,
                message_count=self._baseline_count,
                total=self._total,
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
        if self._baseline_count <= 0:
            self._raise_total_to_observed(count)
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

    async def counting(self) -> None:
        # Показываем пользователю что worker запрашивает total у Telegram.
        # Без этого метода видно "Экспорт начался" и длинная тишина пока
        # messages.GetHistory (count-only) ждёт FloodWait до 20с.
        if not self._message_id:
            return
        await self._client.send_progress_update(
            self._user_chat_id,
            self._task_id,
            message_count=0,
            counting=True,
            progress_message_id=self._message_id,
            topic_name=self._topic_name,
        )

    async def on_floodwait(self, wait_seconds: int) -> None:
        if not self._message_id:
            return
        if self._baseline_count <= 0:
            self._raise_total_to_observed(self._last_count)
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
        final_total = self._total if self._total is not None else count
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
    try:
        if not await client.verify_connectivity():
            raise RuntimeError(f"Cannot reach Java API at {settings.JAVA_API_BASE_URL}")
        return client
    except BaseException:
        await client.aclose()
        raise
