package com.tcleaner.dashboard.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * JSON-схема сообщения в Redis-стриме {@code stats:events}.
 * Поля {@code @JsonInclude(NON_NULL)} — чтобы не засорять stream пустыми ключами
 * (bot_user.seen — только user-поля; export.bytes_measured — только taskId + bytes).
 * <p>
 * Python-воркер шлёт тот же JSON через redis-py {@code xadd} —
 * field-name'ы зафиксированы {@code @JsonProperty}-ами ниже.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({"type", "task_id", "bot_user_id", "ts"})
public class StatsEventPayload {

    @JsonProperty("type")
    private StatsEventType type;

    @JsonProperty("task_id")
    private String taskId;

    @JsonProperty("bot_user_id")
    private Long botUserId;

    @JsonProperty("username")
    private String username;

    @JsonProperty("display_name")
    private String displayName;

    @JsonProperty("chat_id_raw")
    private String chatIdRaw;

    @JsonProperty("canonical_chat_id")
    private String canonicalChatId;

    @JsonProperty("topic_id")
    private Integer topicId;

    @JsonProperty("chat_title")
    private String chatTitle;

    @JsonProperty("from_date")
    private String fromDate;

    @JsonProperty("to_date")
    private String toDate;

    @JsonProperty("keywords")
    private String keywords;

    @JsonProperty("exclude_keywords")
    private String excludeKeywords;

    @JsonProperty("messages_count")
    private Long messagesCount;

    @JsonProperty("bytes_count")
    private Long bytesCount;

    @JsonProperty("status")
    private String status;

    @JsonProperty("error")
    private String error;

    @JsonProperty("source")
    private String source;

    /**
     * ID подписки ({@link com.tcleaner.dashboard.domain.ChatSubscription}),
     * инициировавшей экспорт. {@code null} для ручных экспортов.
     * Передаётся Python-воркером при подписочных запусках.
     */
    @JsonProperty("subscription_id")
    private Long subscriptionId;

    @JsonProperty("ts")
    private Instant ts;
}
