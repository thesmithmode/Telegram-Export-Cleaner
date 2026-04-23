package com.tcleaner.dashboard.service.subscription;

import com.tcleaner.core.TelegramExporter;
import com.tcleaner.dashboard.domain.BotUser;
import com.tcleaner.dashboard.domain.Chat;
import com.tcleaner.dashboard.domain.ChatSubscription;
import com.tcleaner.dashboard.domain.SubscriptionStatus;
import com.tcleaner.dashboard.repository.BotUserRepository;
import com.tcleaner.dashboard.repository.ChatRepository;
import com.tcleaner.dashboard.repository.ChatSubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
@DisplayName("SubscriptionService")
class SubscriptionServiceTest {

    @Autowired
    private SubscriptionService subscriptionService;

    @Autowired
    private ChatSubscriptionRepository subscriptionRepo;

    @Autowired
    private BotUserRepository botUserRepo;

    @Autowired
    private ChatRepository chatRepo;

    @MockitoBean
    private TelegramExporter mockExporter;

    private static final long BOT_USER_ID = 42L;
    private long chatRefId;

    @BeforeEach
    void seed() {
        Instant now = Instant.now();

        BotUser user = BotUser.builder()
                .botUserId(BOT_USER_ID)
                .username("testuser")
                .displayName("Test User")
                .firstSeen(now)
                .lastSeen(now)
                .totalExports(0)
                .totalMessages(0L)
                .totalBytes(0L)
                .build();
        botUserRepo.save(user);

        Chat chat = Chat.builder()
                .canonicalChatId("-100123456789")
                .chatIdRaw("-100123456789")
                .chatTitle("Test Chat")
                .chatType("supergroup")
                .firstSeen(now)
                .lastSeen(now)
                .build();
        Chat savedChat = chatRepo.save(chat);
        chatRefId = savedChat.getId();
    }

    // ─── Happy path: create ────────────────────────────────────────────────────

    @Test
    @DisplayName("create: создаёт ACTIVE-подписку с заполненными полями и consecutiveFailures=0")
    void createSubscriptionHappyPath() {
        Instant before = Instant.now();
        Instant sinceDate = Instant.now().minusSeconds(3600);

        ChatSubscription sub = subscriptionService.create(BOT_USER_ID, chatRefId, 24, "09:00", sinceDate);

        Instant after = Instant.now();

        assertThat(sub.getId()).isNotNull();
        assertThat(sub.getBotUserId()).isEqualTo(BOT_USER_ID);
        assertThat(sub.getChatRefId()).isEqualTo(chatRefId);
        assertThat(sub.getPeriodHours()).isEqualTo(24);
        assertThat(sub.getDesiredTimeMsk()).isEqualTo("09:00");
        assertThat(sub.getSinceDate()).isEqualTo(sinceDate);
        assertThat(sub.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(sub.getConsecutiveFailures()).isZero();
        assertThat(sub.getLastConfirmAt()).isBetween(before, after);
        assertThat(sub.getCreatedAt()).isBetween(before, after);
        assertThat(sub.getUpdatedAt()).isBetween(before, after);
    }

    // ─── Happy path: pause ─────────────────────────────────────────────────────

    @Test
    @DisplayName("pause: ACTIVE → PAUSED")
    void pauseChangesStatusToPaused() {
        Instant sinceDate = Instant.now().minusSeconds(3600);
        ChatSubscription sub = subscriptionService.create(BOT_USER_ID, chatRefId, 24, "10:00", sinceDate);

        ChatSubscription paused = subscriptionService.pause(sub.getId());

        assertThat(paused.getStatus()).isEqualTo(SubscriptionStatus.PAUSED);
    }

    // ─── Happy path: resume ────────────────────────────────────────────────────

    @Test
    @DisplayName("resume: PAUSED → ACTIVE (нет другой ACTIVE-подписки)")
    void resumeChangesStatusToActive() {
        Instant sinceDate = Instant.now().minusSeconds(3600);
        ChatSubscription sub = subscriptionService.create(BOT_USER_ID, chatRefId, 24, "11:00", sinceDate);
        subscriptionService.pause(sub.getId());

        ChatSubscription resumed = subscriptionService.resume(sub.getId());

        assertThat(resumed.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
    }

    // ─── Happy path: delete ────────────────────────────────────────────────────

    @Test
    @DisplayName("delete: запись удаляется из БД (hard delete)")
    void deleteRemovesRecord() {
        Instant sinceDate = Instant.now().minusSeconds(3600);
        ChatSubscription sub = subscriptionService.create(BOT_USER_ID, chatRefId, 24, "12:00", sinceDate);
        long id = sub.getId();

        subscriptionService.delete(id);

        Optional<ChatSubscription> found = subscriptionRepo.findById(id);
        assertThat(found).isEmpty();
    }

    // ─── Happy path: archive ───────────────────────────────────────────────────

    @Test
    @DisplayName("archive: ACTIVE → ARCHIVED")
    void archiveChangesStatusToArchived() {
        Instant sinceDate = Instant.now().minusSeconds(3600);
        ChatSubscription sub = subscriptionService.create(BOT_USER_ID, chatRefId, 24, "13:00", sinceDate);

        ChatSubscription archived = subscriptionService.archive(sub.getId());

        assertThat(archived.getStatus()).isEqualTo(SubscriptionStatus.ARCHIVED);
    }

    // ─── Happy path: recordRunStarted ──────────────────────────────────────────

    @Test
    @DisplayName("recordRunStarted: lastRunAt обновляется до текущего времени")
    void recordRunStartedUpdatesLastRunAt() {
        Instant sinceDate = Instant.now().minusSeconds(3600);
        ChatSubscription sub = subscriptionService.create(BOT_USER_ID, chatRefId, 24, "14:00", sinceDate);
        assertThat(sub.getLastRunAt()).isNull();

        Instant before = Instant.now();
        ChatSubscription updated = subscriptionService.recordRunStarted(sub.getId());
        Instant after = Instant.now();

        assertThat(updated.getLastRunAt()).isBetween(before, after);
    }

    // ─── Happy path: recordSuccess ─────────────────────────────────────────────

    @Test
    @DisplayName("recordSuccess: lastSuccessAt обновляется, consecutiveFailures сбрасывается в 0")
    void recordSuccessUpdatesLastSuccessAtAndResetsFailures() {
        Instant sinceDate = Instant.now().minusSeconds(3600);
        ChatSubscription sub = subscriptionService.create(BOT_USER_ID, chatRefId, 24, "15:00", sinceDate);
        subscriptionService.recordFailure(sub.getId());

        Instant before = Instant.now();
        ChatSubscription updated = subscriptionService.recordSuccess(sub.getId());
        Instant after = Instant.now();

        assertThat(updated.getLastSuccessAt()).isBetween(before, after);
        assertThat(updated.getConsecutiveFailures()).isZero();
    }

    // ─── Happy path: recordFailure x1 ─────────────────────────────────────────

    @Test
    @DisplayName("recordFailure один раз: consecutiveFailures=1, статус остаётся ACTIVE")
    void recordFailureOnceSetsCounterAndKeepsActive() {
        Instant sinceDate = Instant.now().minusSeconds(3600);
        ChatSubscription sub = subscriptionService.create(BOT_USER_ID, chatRefId, 24, "16:00", sinceDate);

        Instant before = Instant.now();
        ChatSubscription updated = subscriptionService.recordFailure(sub.getId());
        Instant after = Instant.now();

        assertThat(updated.getConsecutiveFailures()).isEqualTo(1);
        assertThat(updated.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(updated.getLastFailureAt()).isBetween(before, after);
    }

    // ─── Happy path: recordFailure x2 ─────────────────────────────────────────

    @Test
    @DisplayName("recordFailure дважды: consecutiveFailures=2, статус=PAUSED, lastFailureAt обновлён")
    void recordFailureTwicePausesSubscription() {
        Instant sinceDate = Instant.now().minusSeconds(3600);
        ChatSubscription sub = subscriptionService.create(BOT_USER_ID, chatRefId, 24, "17:00", sinceDate);
        subscriptionService.recordFailure(sub.getId());

        Instant before = Instant.now();
        ChatSubscription updated = subscriptionService.recordFailure(sub.getId());
        Instant after = Instant.now();

        assertThat(updated.getConsecutiveFailures()).isEqualTo(2);
        assertThat(updated.getStatus()).isEqualTo(SubscriptionStatus.PAUSED);
        assertThat(updated.getLastFailureAt()).isBetween(before, after);
    }

    // ─── Happy path: markConfirmSent ───────────────────────────────────────────

    @Test
    @DisplayName("markConfirmSent: confirmSentAt обновляется до текущего времени")
    void markConfirmSentUpdatesConfirmSentAt() {
        Instant sinceDate = Instant.now().minusSeconds(3600);
        ChatSubscription sub = subscriptionService.create(BOT_USER_ID, chatRefId, 24, "18:00", sinceDate);
        assertThat(sub.getConfirmSentAt()).isNull();

        Instant before = Instant.now();
        ChatSubscription updated = subscriptionService.markConfirmSent(sub.getId());
        Instant after = Instant.now();

        assertThat(updated.getConfirmSentAt()).isBetween(before, after);
    }

    // ─── Happy path: confirmReceived ───────────────────────────────────────────

    @Test
    @DisplayName("confirmReceived: lastConfirmAt=now, confirmSentAt=null")
    void confirmReceivedUpdatesLastConfirmAtAndClearsSentAt() {
        Instant sinceDate = Instant.now().minusSeconds(3600);
        ChatSubscription sub = subscriptionService.create(BOT_USER_ID, chatRefId, 24, "19:00", sinceDate);
        subscriptionService.markConfirmSent(sub.getId());

        Instant before = Instant.now();
        ChatSubscription updated = subscriptionService.confirmReceived(sub.getId());
        Instant after = Instant.now();

        assertThat(updated.getLastConfirmAt()).isBetween(before, after);
        assertThat(updated.getConfirmSentAt()).isNull();
    }

    // ─── Happy path: listForUser vs listAll ───────────────────────────────────

    @Test
    @DisplayName("listForUser фильтрует только подписки данного пользователя; listAll возвращает все")
    void listForUserFiltersCorrectlyVsListAll() {
        Instant now = Instant.now();

        // Создаём второго пользователя с отдельным chatRefId
        BotUser otherUser = BotUser.builder()
                .botUserId(99L)
                .username("otheruser")
                .displayName("Other User")
                .firstSeen(now)
                .lastSeen(now)
                .totalExports(0)
                .totalMessages(0L)
                .totalBytes(0L)
                .build();
        botUserRepo.save(otherUser);

        Chat otherChat = Chat.builder()
                .canonicalChatId("-100987654321")
                .chatIdRaw("-100987654321")
                .chatTitle("Other Chat")
                .chatType("supergroup")
                .firstSeen(now)
                .lastSeen(now)
                .build();
        long otherChatRefId = chatRepo.save(otherChat).getId();

        Instant sinceDate = Instant.now().minusSeconds(3600);
        subscriptionService.create(BOT_USER_ID, chatRefId, 24, "09:00", sinceDate);
        subscriptionService.create(99L, otherChatRefId, 48, "10:00", sinceDate);

        List<ChatSubscription> forUser = subscriptionService.listForUser(BOT_USER_ID);
        List<ChatSubscription> all = subscriptionService.listAll();

        assertThat(forUser).hasSize(1);
        assertThat(forUser).allMatch(s -> s.getBotUserId().equals(BOT_USER_ID));
        assertThat(all).hasSizeGreaterThanOrEqualTo(2);
    }

    // ─── Validation: недопустимый periodHours ─────────────────────────────────

    @Test
    @DisplayName("create с невалидным periodHours (99) → IllegalArgumentException про period_hours")
    void createWithInvalidPeriodHoursThrows() {
        Instant sinceDate = Instant.now().minusSeconds(3600);

        assertThatThrownBy(() -> subscriptionService.create(BOT_USER_ID, chatRefId, 99, "09:00", sinceDate))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("period_hours");
    }

    // ─── Validation: невалидный desiredTimeMsk ────────────────────────────────

    @Test
    @DisplayName("create с невалидным desiredTimeMsk (25:00) → IllegalArgumentException про HH:MM")
    void createWithInvalidDesiredTimeThrows() {
        Instant sinceDate = Instant.now().minusSeconds(3600);

        assertThatThrownBy(() -> subscriptionService.create(BOT_USER_ID, chatRefId, 24, "25:00", sinceDate))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HH:MM");
    }

    @Test
    @DisplayName("create с невалидным desiredTimeMsk (9:00 без ведущего нуля) → IllegalArgumentException про HH:MM")
    void createWithSingleDigitHourThrows() {
        Instant sinceDate = Instant.now().minusSeconds(3600);

        assertThatThrownBy(() -> subscriptionService.create(BOT_USER_ID, chatRefId, 24, "9:00", sinceDate))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HH:MM");
    }

    // ─── Validation: sinceDate в будущем ──────────────────────────────────────

    @Test
    @DisplayName("create с sinceDate в будущем → IllegalArgumentException про future")
    void createWithFutureSinceDateThrows() {
        Instant futureDate = Instant.now().plusSeconds(3600);

        assertThatThrownBy(() -> subscriptionService.create(BOT_USER_ID, chatRefId, 24, "09:00", futureDate))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("future");
    }

    // ─── Validation: sinceDate старше period window ────────────────────────────

    @Test
    @DisplayName("create с sinceDate старше period (24ч): sinceDate=48ч назад → IllegalArgumentException про period window")
    void createWithSinceDateOlderThanPeriodWindowThrows() {
        Instant tooOld = Instant.now().minusSeconds(48 * 3600 + 1);

        assertThatThrownBy(() -> subscriptionService.create(BOT_USER_ID, chatRefId, 24, "09:00", tooOld))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("period window");
    }

    // ─── Validation: дублирующаяся ACTIVE при create ──────────────────────────

    @Test
    @DisplayName("create когда у юзера уже есть ACTIVE-подписка → IllegalStateException")
    void createWhenAlreadyActiveThrows() {
        Instant sinceDate = Instant.now().minusSeconds(3600);
        subscriptionService.create(BOT_USER_ID, chatRefId, 24, "09:00", sinceDate);

        assertThatThrownBy(() -> subscriptionService.create(BOT_USER_ID, chatRefId, 48, "10:00", sinceDate))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active subscription");
    }

    // ─── Validation: дублирующаяся PAUSED при create ─────────────────────────

    @Test
    @DisplayName("create когда у юзера есть PAUSED-подписка → IllegalStateException")
    void createWhenPausedExistsThrows() {
        Instant sinceDate = Instant.now().minusSeconds(3600);
        ChatSubscription sub = subscriptionService.create(BOT_USER_ID, chatRefId, 24, "09:00", sinceDate);
        subscriptionService.pause(sub.getId());

        assertThatThrownBy(() -> subscriptionService.create(BOT_USER_ID, chatRefId, 48, "10:00", sinceDate))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("paused subscription");
    }

    // ─── Validation: resume ARCHIVED ──────────────────────────────────────────

    @Test
    @DisplayName("resume: ARCHIVED подписка → IllegalStateException про archived")
    void resumeArchivedSubscriptionThrows() {
        Instant sinceDate = Instant.now().minusSeconds(3600);
        ChatSubscription sub = subscriptionService.create(BOT_USER_ID, chatRefId, 24, "20:00", sinceDate);
        subscriptionService.archive(sub.getId());

        assertThatThrownBy(() -> subscriptionService.resume(sub.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("archived");
    }

    // ─── Validation: resume при наличии другой ACTIVE ─────────────────────────

    @Test
    @DisplayName("resume когда у юзера уже есть другая ACTIVE → IllegalStateException")
    void resumeWhenAnotherActiveExistsThrows() {
        Instant now = Instant.now();
        Instant sinceDate = now.minusSeconds(3600);

        // Создаём второго пользователя для другого чата, чтобы иметь два отдельных чата
        Chat secondChat = Chat.builder()
                .canonicalChatId("-100555444333")
                .chatIdRaw("-100555444333")
                .chatTitle("Second Chat")
                .chatType("supergroup")
                .firstSeen(now)
                .lastSeen(now)
                .build();
        long secondChatRefId = chatRepo.save(secondChat).getId();

        // Создаём первую подписку (будет ACTIVE)
        ChatSubscription first = subscriptionService.create(BOT_USER_ID, chatRefId, 24, "09:00", sinceDate);

        // Имитируем вторую через прямое сохранение в репо (bypassing service, чтобы обойти PAUSED-проверку)
        ChatSubscription second = ChatSubscription.builder()
                .botUserId(BOT_USER_ID)
                .chatRefId(secondChatRefId)
                .periodHours(48)
                .desiredTimeMsk("10:00")
                .sinceDate(sinceDate)
                .status(SubscriptionStatus.PAUSED)
                .consecutiveFailures(0)
                .lastConfirmAt(now)
                .createdAt(now)
                .updatedAt(now)
                .build();
        ChatSubscription savedSecond = subscriptionRepo.save(second);

        // Теперь пытаемся восстановить вторую — но первая уже ACTIVE
        assertThatThrownBy(() -> subscriptionService.resume(savedSecond.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active subscription");
    }
}
