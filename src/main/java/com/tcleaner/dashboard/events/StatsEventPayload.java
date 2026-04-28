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

// @JsonProperty field-names — контракт с Python-воркером (redis-py xadd). Переименование = breaking change.
// @JsonInclude(NON_NULL) — не засоряем stream пустыми ключами (bot_user.seen содержит только user-поля).
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

    @JsonProperty("subscription_id")
    private Long subscriptionId;

    @JsonProperty("ts")
    private Instant ts;
}
