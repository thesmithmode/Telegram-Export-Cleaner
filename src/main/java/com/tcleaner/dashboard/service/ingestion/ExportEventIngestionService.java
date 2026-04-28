package com.tcleaner.dashboard.service.ingestion;

import com.tcleaner.dashboard.domain.BotUser;
import com.tcleaner.dashboard.domain.Chat;
import com.tcleaner.dashboard.domain.ExportEvent;
import com.tcleaner.dashboard.domain.ExportSource;
import com.tcleaner.dashboard.domain.ExportStatus;
import com.tcleaner.dashboard.events.StatsEventPayload;
import com.tcleaner.dashboard.events.StatsEventType;
import com.tcleaner.dashboard.repository.ExportEventRepository;
import com.tcleaner.dashboard.service.subscription.SubscriptionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;

// Идемпотентность по UNIQUE(task_id): late-arriving completed до started → merge COALESCE(new, old).
// total_* счётчики инкрементируются только при первом переходе в terminal — двойной счёт исключён.
@Service
public class ExportEventIngestionService {

    private static final Logger log = LoggerFactory.getLogger(ExportEventIngestionService.class);

    private final ExportEventRepository eventRepository;
    private final BotUserUpserter botUserUpserter;
    private final ChatUpserter chatUpserter;
    private final SubscriptionService subscriptionService;

    public ExportEventIngestionService(
            ExportEventRepository eventRepository,
            BotUserUpserter botUserUpserter,
            ChatUpserter chatUpserter,
            SubscriptionService subscriptionService
    ) {
        this.eventRepository = eventRepository;
        this.botUserUpserter = botUserUpserter;
        this.chatUpserter = chatUpserter;
        this.subscriptionService = subscriptionService;
    }

    @Transactional
    public void ingest(StatsEventPayload payload) {
        if (payload == null || payload.getType() == null) {
            log.warn("Пропуск события без type");
            return;
        }
        try {
            switch (payload.getType()) {
                case BOT_USER_SEEN -> handleBotUserSeen(payload);
                case EXPORT_STARTED -> upsertEvent(payload, ExportStatus.QUEUED);
                case EXPORT_COMPLETED -> upsertEvent(payload, ExportStatus.COMPLETED);
                case EXPORT_FAILED -> upsertEvent(payload, ExportStatus.FAILED);
                case EXPORT_CANCELLED -> upsertEvent(payload, ExportStatus.CANCELLED);
                case EXPORT_BYTES_MEASURED -> upsertEvent(payload, null);
            }
        } catch (Exception ex) {
            // Re-throw: @Transactional откатывает частичный upsert (chat/user/event),
            // иначе failure после части writes оставлял БД в несогласованном состоянии.
            // Upstream (StatsStreamConsumer#onMessage) поймает и всё равно ACK'нет запись
            // — at-least-once + идемпотентность task_id страхует от повторов.
            log.error("Ошибка ingest события {} (task={}): {}",
                    payload.getType(), payload.getTaskId(), ex.getMessage(), ex);
            throw ex;
        }
    }

    private void handleBotUserSeen(StatsEventPayload payload) {
        if (payload.getBotUserId() == null) {
            log.warn("bot_user.seen без bot_user_id — пропуск");
            return;
        }
        botUserUpserter.upsert(
                payload.getBotUserId(),
                payload.getUsername(),
                payload.getDisplayName(),
                payload.getTs());
    }

    private void upsertEvent(StatsEventPayload payload, ExportStatus desiredStatus) {
        if (payload.getTaskId() == null || payload.getTaskId().isBlank()) {
            log.warn("{} без task_id — пропуск", payload.getType());
            return;
        }
        Instant now = Instant.now();
        ExportEvent existing = eventRepository.findByTaskId(payload.getTaskId()).orElse(null);

        if (existing == null) {
            if (!hasMinimalFieldsForInsert(payload)) {
                log.warn("{}(task={}) прилетел первым без bot_user_id/chat — пропуск",
                        payload.getType(), payload.getTaskId());
                return;
            }
            Chat chat = chatUpserter.upsert(
                    payload.getCanonicalChatId(), payload.getChatIdRaw(),
                    payload.getTopicId(), payload.getChatTitle(), payload.getTs());
            BotUser user = botUserUpserter.upsert(
                    payload.getBotUserId(), payload.getUsername(),
                    payload.getDisplayName(), payload.getTs());

            ExportEvent created = ExportEvent.builder()
                    .taskId(payload.getTaskId())
                    .botUserId(user.getBotUserId())
                    .chatRefId(chat.getId())
                    .startedAt(payload.getTs() != null ? payload.getTs() : now)
                    .status(desiredStatus != null ? desiredStatus : ExportStatus.QUEUED)
                    .messagesCount(payload.getMessagesCount())
                    .bytesCount(payload.getBytesCount())
                    .fromDate(parseDate(payload.getFromDate()))
                    .toDate(parseDate(payload.getToDate()))
                    .keywords(payload.getKeywords())
                    .excludeKeywords(payload.getExcludeKeywords())
                    .source(parseSource(payload.getSource()))
                    .errorMessage(payload.getError())
                    .subscriptionId(payload.getSubscriptionId())
                    .createdAt(now)
                    .updatedAt(now)
                    .build();
            if (isTerminal(desiredStatus)) {
                created.setFinishedAt(payload.getTs() != null ? payload.getTs() : now);
            }
            eventRepository.save(created);
            maybeBumpUserTotals(user, null, created);
            updateSubscriptionOnTerminal(created);
            return;
        }

        ExportStatus prev = existing.getStatus();
        coalesce(payload.getMessagesCount(), existing::setMessagesCount);
        coalesce(payload.getBytesCount(), existing::setBytesCount);
        coalesce(parseDate(payload.getFromDate()), existing::setFromDate);
        coalesce(parseDate(payload.getToDate()), existing::setToDate);
        coalesce(payload.getKeywords(), existing::setKeywords);
        coalesce(payload.getExcludeKeywords(), existing::setExcludeKeywords);
        coalesce(payload.getError(), existing::setErrorMessage);
        coalesce(payload.getSubscriptionId(), existing::setSubscriptionId);
        if (payload.getChatTitle() != null || payload.getCanonicalChatId() != null) {
            // Upsert обновляет метаданные чата (title, type), но chat_ref_id события
            // не меняем — late event с другим canonical сломал бы агрегаты по истории.
            chatUpserter.upsert(
                    payload.getCanonicalChatId(), payload.getChatIdRaw(),
                    payload.getTopicId(), payload.getChatTitle(), payload.getTs());
        }
        if (desiredStatus != null && canAdvanceStatus(prev, desiredStatus)) {
            existing.setStatus(desiredStatus);
            if (isTerminal(desiredStatus) && existing.getFinishedAt() == null) {
                existing.setFinishedAt(payload.getTs() != null ? payload.getTs() : now);
            }
        }
        existing.setUpdatedAt(now);
        ExportEvent saved = eventRepository.save(existing);

        if (desiredStatus != null && isTerminal(desiredStatus) && !isTerminal(prev)) {
            BotUser user = botUserUpserter.upsert(
                    saved.getBotUserId(), payload.getUsername(),
                    payload.getDisplayName(), payload.getTs());
            maybeBumpUserTotals(user, prev, saved);
            updateSubscriptionOnTerminal(saved);
        }
    }

    // CANCELLED не трогаем — юзер сам отменил, consecutive_failures не растёт.
    private void updateSubscriptionOnTerminal(ExportEvent event) {
        Long subscriptionId = event.getSubscriptionId();
        if (subscriptionId == null) {
            return;
        }
        try {
            if (event.getStatus() == ExportStatus.COMPLETED) {
                subscriptionService.recordSuccess(subscriptionId);
            } else if (event.getStatus() == ExportStatus.FAILED) {
                subscriptionService.recordFailure(subscriptionId);
            }
        } catch (RuntimeException ex) {
            log.warn("Не удалось обновить lifecycle подписки {} по событию task={}: {}",
                    subscriptionId, event.getTaskId(), ex.getMessage());
        }
    }

    private void maybeBumpUserTotals(BotUser user, ExportStatus prev, ExportEvent event) {
        if (!isTerminal(event.getStatus())) {
            return;
        }
        if (prev != null && isTerminal(prev)) {
            return;
        }
        if (event.getStatus() == ExportStatus.CANCELLED) {
            return;
        }
        user.setTotalExports(user.getTotalExports() + 1);
        if (event.getStatus() == ExportStatus.COMPLETED) {
            if (event.getMessagesCount() != null) {
                user.setTotalMessages(user.getTotalMessages() + event.getMessagesCount());
            }
            if (event.getBytesCount() != null) {
                user.setTotalBytes(user.getTotalBytes() + event.getBytesCount());
            }
        }
    }

    private static boolean hasMinimalFieldsForInsert(StatsEventPayload p) {
        return p.getBotUserId() != null
                && ((p.getChatIdRaw() != null && !p.getChatIdRaw().isBlank())
                    || (p.getCanonicalChatId() != null && !p.getCanonicalChatId().isBlank()));
    }

    private static boolean isTerminal(ExportStatus status) {
        return status == ExportStatus.COMPLETED
                || status == ExportStatus.FAILED
                || status == ExportStatus.CANCELLED;
    }

    // Предотвращает откат статуса: late started после completed не перезаписывает terminal.
    private static boolean canAdvanceStatus(ExportStatus prev, ExportStatus next) {
        if (prev == null) {
            return true;
        }
        if (isTerminal(prev)) {
            return false;
        }
        return true;
    }

    private static <T> void coalesce(T value, java.util.function.Consumer<T> setter) {
        if (value != null) {
            if (value instanceof String s && s.isBlank()) {
                return;
            }
            setter.accept(value);
        }
    }

    private static LocalDate parseDate(String iso) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(iso);
        } catch (DateTimeParseException ex) {
            // payload может прийти как datetime: "2026-04-14T00:00:00" — берём только дату
            try {
                return LocalDateTime.parse(iso).toLocalDate();
            } catch (DateTimeParseException ex2) {
                log.warn("Невалидная дата в payload: {}", iso);
                return null;
            }
        }
    }

    private static ExportSource parseSource(String source) {
        if (source == null || source.isBlank()) {
            return ExportSource.BOT;
        }
        try {
            return ExportSource.valueOf(source.toUpperCase());
        } catch (IllegalArgumentException ex) {
            return ExportSource.BOT;
        }
    }
}
