package com.tcleaner.dashboard.service.ingestion;

import com.tcleaner.dashboard.domain.BotUser;
import com.tcleaner.dashboard.domain.Chat;
import com.tcleaner.dashboard.domain.ExportEvent;
import com.tcleaner.dashboard.domain.ExportSource;
import com.tcleaner.dashboard.domain.ExportStatus;
import com.tcleaner.dashboard.events.StatsEventPayload;
import com.tcleaner.dashboard.events.StatsEventType;
import com.tcleaner.dashboard.repository.ExportEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * Центральный обработчик событий стрима {@code stats:events}: маршрутизирует payload
 * по типу и выполняет идемпотентный upsert в {@code bot_users}/{@code chats}/{@code export_events}.
 * <p>
 * Идемпотентность обеспечивается UNIQUE({@code export_events.task_id}): повтор события
 * или late-arriving {@code completed} до {@code started} приводят к merge через {@code COALESCE(new, old)},
 * а не к дубликатам. Счётчики {@code total_*} у {@link BotUser} инкрементируются строго при переходах
 * статуса — дважды не считаем.
 */
@Service
public class ExportEventIngestionService {

    private static final Logger log = LoggerFactory.getLogger(ExportEventIngestionService.class);

    private final ExportEventRepository eventRepository;
    private final BotUserUpserter botUserUpserter;
    private final ChatUpserter chatUpserter;

    public ExportEventIngestionService(
            ExportEventRepository eventRepository,
            BotUserUpserter botUserUpserter,
            ChatUpserter chatUpserter
    ) {
        this.eventRepository = eventRepository;
        this.botUserUpserter = botUserUpserter;
        this.chatUpserter = chatUpserter;
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
            log.error("Ошибка ingest события {} (task={}): {}",
                    payload.getType(), payload.getTaskId(), ex.getMessage(), ex);
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

    /**
     * Upsert export_events по task_id.
     * @param desiredStatus новый статус или {@code null} для bytes_measured (статус не трогаем).
     */
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
                    .createdAt(now)
                    .updatedAt(now)
                    .build();
            if (isTerminal(desiredStatus)) {
                created.setFinishedAt(payload.getTs() != null ? payload.getTs() : now);
            }
            eventRepository.save(created);
            maybeBumpUserTotals(user, null, created);
            return;
        }

        // MERGE: non-null поля из payload перекрывают старые, остальные — остаются.
        ExportStatus prev = existing.getStatus();
        coalesce(payload.getMessagesCount(), existing::setMessagesCount);
        coalesce(payload.getBytesCount(), existing::setBytesCount);
        coalesce(parseDate(payload.getFromDate()), existing::setFromDate);
        coalesce(parseDate(payload.getToDate()), existing::setToDate);
        coalesce(payload.getKeywords(), existing::setKeywords);
        coalesce(payload.getExcludeKeywords(), existing::setExcludeKeywords);
        coalesce(payload.getError(), existing::setErrorMessage);
        if (payload.getChatTitle() != null || payload.getCanonicalChatId() != null) {
            Chat chat = chatUpserter.upsert(
                    payload.getCanonicalChatId(), payload.getChatIdRaw(),
                    payload.getTopicId(), payload.getChatTitle(), payload.getTs());
            existing.setChatRefId(chat.getId());
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
        }
    }

    /**
     * Накидываем плюс-одну в счётчики пользователя при каждом реальном переходе в terminal-статус
     * (а не при апсерте уже-completed-записи). {@code COMPLETED} приносит messages/bytes; failed/cancelled
     * считаются только в {@code totalExports}.
     */
    private void maybeBumpUserTotals(BotUser user, ExportStatus prev, ExportEvent event) {
        if (!isTerminal(event.getStatus())) {
            return;
        }
        if (prev != null && isTerminal(prev)) {
            return; // уже учитывали
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

    /**
     * Разрешает только переходы QUEUED → PROCESSING → terminal.
     * Не даёт откатить уже завершённую задачу «более ранним» событием
     * (например, если по какой-то причине late {@code started} прилетит после {@code completed}).
     */
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
            log.warn("Невалидная дата в payload: {}", iso);
            return null;
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
