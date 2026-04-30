package com.tcleaner.bot;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

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
    /** Builder для удобной частичной инициализации (большинство полей nullable). */
    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String taskId;
        private long userId;
        private long userChatId;
        private Object chatId;
        private Integer topicId;
        private int limit = 0;
        private int offsetId = 0;
        private String fromDate;
        private String toDate;
        private String keywords;
        private String excludeKeywords;
        private String source;
        private Long subscriptionId;

        public Builder taskId(String v) { this.taskId = v; return this; }
        public Builder userId(long v) { this.userId = v; return this; }
        public Builder userChatId(long v) { this.userChatId = v; return this; }
        public Builder chatId(Object v) { this.chatId = v; return this; }
        public Builder topicId(Integer v) { this.topicId = v; return this; }
        public Builder limit(int v) { this.limit = v; return this; }
        public Builder offsetId(int v) { this.offsetId = v; return this; }
        public Builder fromDate(String v) { this.fromDate = v; return this; }
        public Builder toDate(String v) { this.toDate = v; return this; }
        public Builder keywords(String v) { this.keywords = v; return this; }
        public Builder excludeKeywords(String v) { this.excludeKeywords = v; return this; }
        public Builder source(String v) { this.source = v; return this; }
        public Builder subscriptionId(Long v) { this.subscriptionId = v; return this; }

        public ExportJobPayload build() {
            return new ExportJobPayload(
                    taskId, userId, userChatId, chatId, topicId,
                    limit, offsetId, fromDate, toDate,
                    keywords, excludeKeywords, source, subscriptionId);
        }
    }
}
