package com.tcleaner.bot;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

/**
 * Контракт задачи экспорта в Redis-очереди. Сериализуется в JSON,
 * читается Python-воркером (export-worker/models.py::ExportRequest).
 *
 * <p><b>Wire format — snake_case</b> (через {@link JsonProperty}). Python ExportRequest
 * использует те же имена полей. Изменение порядка/имён ломает оба сайда —
 * любое расширение требует синхронной правки на Python.
 *
 * <p><b>chatId</b> — {@link Object}: либо {@link Long} (числовой Telegram ID),
 * либо {@link String} (@username, t.me/... ссылка). Python coerce'ит обе формы.
 * Отдельный {@code record ChatIdentifier} избыточен — Jackson сериализует Object
 * корректно, Pydantic валидирует {@code Union[int, str]}.
 *
 * <p>Раньше тут был {@code Map<String,Object>} — schema-drift между Java и Python
 * порождал инциденты (см. CLAUDE.md feedback_verify_protocol_data_format).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Builder
public record ExportJobPayload(
        @JsonProperty("task_id") String taskId,
        @JsonProperty("user_id") long userId,
        @JsonProperty("user_chat_id") long userChatId,
        @JsonProperty("chat_id") Object chatId,
        @JsonProperty("topic_id") Integer topicId,
        @JsonProperty("limit") int limit,
        @JsonProperty("offset_id") int offsetId,
        @JsonProperty("from_date") String fromDate,
        @JsonProperty("to_date") String toDate,
        @JsonProperty("keywords") String keywords,
        @JsonProperty("exclude_keywords") String excludeKeywords,
        @JsonProperty("source") String source,
        @JsonProperty("subscription_id") Long subscriptionId
) {
}
