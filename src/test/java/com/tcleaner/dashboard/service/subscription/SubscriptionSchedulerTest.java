package com.tcleaner.dashboard.service.subscription;

import com.tcleaner.bot.ExportJobProducer;
import com.tcleaner.dashboard.domain.Chat;
import com.tcleaner.dashboard.domain.ChatSubscription;
import com.tcleaner.dashboard.domain.SubscriptionStatus;
import com.tcleaner.dashboard.repository.ChatRepository;
import com.tcleaner.dashboard.repository.ChatSubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SubscriptionScheduler")
class SubscriptionSchedulerTest {

    private static final ZoneId MSK = ZoneId.of("Europe/Moscow");
    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");

    @Mock private ChatSubscriptionRepository repository;
    @Mock private SubscriptionService subscriptionService;
    @Mock private ExportJobProducer jobProducer;
    @Mock private ChatRepository chatRepository;

    private SubscriptionScheduler scheduler;

    @BeforeEach
    void init() {
        scheduler = new SubscriptionScheduler(repository, subscriptionService, jobProducer, chatRepository);
    }

    // ------------------------------------------------------------------ helpers

    private static ChatSubscription activeSub(long id, long botUserId, long chatRefId,
                                               int periodHours, String desiredTimeMsk,
                                               Instant sinceDate, Instant lastSuccessAt) {
        return ChatSubscription.builder()
                .id(id)
                .botUserId(botUserId)
                .chatRefId(chatRefId)
                .periodHours(periodHours)
                .desiredTimeMsk(desiredTimeMsk)
                .sinceDate(sinceDate)
                .status(SubscriptionStatus.ACTIVE)
                .consecutiveFailures(0)
                .lastSuccessAt(lastSuccessAt)
                .lastConfirmAt(Instant.now())
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    private static Chat chat(long id, String chatIdRaw) {
        return Chat.builder()
                .id(id)
                .canonicalChatId(chatIdRaw)
                .chatIdRaw(chatIdRaw)
                .firstSeen(Instant.now())
                .lastSeen(Instant.now())
                .build();
    }

    /**
     * Возвращает строку "HH:mm" для текущего момента + offsetMinutes по МСК.
     * Используется для построения desiredTimeMsk, гарантированно попадающего в окно.
     */
    private static String mskHhMm(int offsetMinutes) {
        ZonedDateTime target = ZonedDateTime.now(MSK).plusMinutes(offsetMinutes);
        return HHMM.format(target);
    }

    // ------------------------------------------------------------------ тесты

    @Test
    @DisplayName("runDueSubscriptions: если hasActiveProcessingJob=true → подписка не ставится в очередь")
    void skipsWhenWorkerIsProcessing() {
        when(jobProducer.hasActiveProcessingJob()).thenReturn(true);

        scheduler.runDueSubscriptions();

        verify(jobProducer, never()).enqueueSubscription(anyLong(), anyLong(), anyString(), anyString(), anyString(), anyLong());
        verify(repository, never()).findDueForRun(any());
    }

    @Test
    @DisplayName("runDueSubscriptions: если getQueueLength>0 → подписка не ставится в очередь")
    void skipsWhenQueueIsNotEmpty() {
        when(jobProducer.hasActiveProcessingJob()).thenReturn(false);
        when(jobProducer.getQueueLength()).thenReturn(3L);

        scheduler.runDueSubscriptions();

        verify(jobProducer, never()).enqueueSubscription(anyLong(), anyLong(), anyString(), anyString(), anyString(), anyLong());
        verify(repository, never()).findDueForRun(any());
    }

    @Test
    @DisplayName("runDueSubscriptions: idle + кандидат в окне → enqueue вызван + recordRunStarted вызван")
    void enqueuedAndRunStartedWhenIdleAndInWindow() {
        // Воркер свободен
        when(jobProducer.hasActiveProcessingJob()).thenReturn(false);
        when(jobProducer.getQueueLength()).thenReturn(0L);

        // Подписка: sinceDate = 25 ч назад, period = 24h → период истёк
        // desiredTimeMsk = текущее МСК + 10 мин → окно открылось 20 мин назад (windowStart = target - 30min)
        Instant sinceDate = Instant.now().minusSeconds(25 * 3600L);
        String desiredTime = mskHhMm(10); // через 10 мин от сейчас — окно открылось 20 мин назад

        ChatSubscription sub = activeSub(1L, 100L, 200L, 24, desiredTime, sinceDate, null);
        when(repository.findDueForRun(any())).thenReturn(List.of(sub));

        Chat testChat = chat(200L, "@testchat");
        when(chatRepository.findAllById(any())).thenReturn(List.of(testChat));
        when(jobProducer.enqueueSubscription(anyLong(), anyLong(), anyString(), anyString(), anyString(), anyLong()))
                .thenReturn("sub_abc123");

        scheduler.runDueSubscriptions();

        verify(jobProducer).enqueueSubscription(eq(100L), eq(100L), eq("@testchat"), any(), any(), eq(1L));
        verify(subscriptionService).recordRunStarted(1L);
    }

    @Test
    @DisplayName("runDueSubscriptions: fromIso/toIso без наносекунд (формат YYYY-MM-DDTHH:MM:SS) — совместим с Python валидатором")
    void enqueuedDateFormatHasNoNanoseconds() {
        when(jobProducer.hasActiveProcessingJob()).thenReturn(false);
        when(jobProducer.getQueueLength()).thenReturn(0L);

        Instant sinceDate = Instant.now().minusSeconds(25 * 3600L);
        String desiredTime = mskHhMm(10);
        ChatSubscription sub = activeSub(1L, 100L, 200L, 24, desiredTime, sinceDate, null);
        when(repository.findDueForRun(any())).thenReturn(List.of(sub));
        when(chatRepository.findAllById(any())).thenReturn(List.of(chat(200L, "@testchat")));
        when(jobProducer.enqueueSubscription(anyLong(), anyLong(), anyString(), anyString(), anyString(), anyLong()))
                .thenReturn("sub_abc");

        scheduler.runDueSubscriptions();

        ArgumentCaptor<String> fromCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> toCap = ArgumentCaptor.forClass(String.class);
        verify(jobProducer).enqueueSubscription(anyLong(), anyLong(), anyString(),
                fromCap.capture(), toCap.capture(), anyLong());

        String regex = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}";
        assertThat(fromCap.getValue()).matches(regex).doesNotContain(".");
        assertThat(toCap.getValue()).matches(regex).doesNotContain(".");
    }

    @Test
    @DisplayName("runDueSubscriptions: кандидат ВНЕ окна desired_time (target=23:00 МСК, now=07:00 МСК) → isInDesiredWindow=false")
    void skipsWhenOutsideDesiredWindow() {
        // desired_time_msk = "23:00" МСК
        // windowStart = 23:00 МСК - 30min = 22:30 МСК
        // now = 07:00 МСК → задолго до окна → isInDesiredWindow должен вернуть false
        String desiredTime = "23:00";
        Instant sinceDate = Instant.parse("2026-04-22T00:00:00Z");

        ChatSubscription sub = activeSub(2L, 100L, 200L, 24, desiredTime, sinceDate, null);

        // Фиксируем now = 07:00 МСК текущего дня (детерминировано, независимо от реального времени теста)
        Instant nowMsk7am = ZonedDateTime.now(MSK)
                .withHour(7).withMinute(0).withSecond(0).withNano(0)
                .toInstant();

        // Прямой вызов package-private метода — unit-тест логики окна
        boolean inWindow = scheduler.isInDesiredWindow(sub, nowMsk7am);

        org.assertj.core.api.Assertions.assertThat(inWindow).isFalse();
    }

    @Test
    @DisplayName("runDueSubscriptions: период ещё не прошёл (lastSuccessAt=5ч назад при period=24h) → skip")
    void skipsWhenPeriodNotElapsed() {
        when(jobProducer.hasActiveProcessingJob()).thenReturn(false);
        when(jobProducer.getQueueLength()).thenReturn(0L);

        // desiredTimeMsk = текущее МСК + 10 мин → окно уже открыто
        String desiredTime = mskHhMm(10);
        Instant sinceDate = Instant.now().minusSeconds(26 * 3600L);
        // lastSuccessAt = всего 5 часов назад, period = 24h → 19 часов ещё не прошло
        Instant lastSuccessAt = Instant.now().minusSeconds(5 * 3600L);

        ChatSubscription sub = activeSub(3L, 100L, 200L, 24, desiredTime, sinceDate, lastSuccessAt);
        when(repository.findDueForRun(any())).thenReturn(List.of(sub));

        scheduler.runDueSubscriptions();

        verify(jobProducer, never()).enqueueSubscription(anyLong(), anyLong(), anyString(), anyString(), anyString(), anyLong());
        verify(subscriptionService, never()).recordRunStarted(anyLong());
    }

    @Test
    @DisplayName("runDueSubscriptions: исключение при enqueueSubscription → recordFailure вызван")
    void recordsFailureOnException() {
        when(jobProducer.hasActiveProcessingJob()).thenReturn(false);
        when(jobProducer.getQueueLength()).thenReturn(0L);

        // Подписка в допустимом окне и период истёк
        Instant sinceDate = Instant.now().minusSeconds(25 * 3600L);
        String desiredTime = mskHhMm(10);
        ChatSubscription sub = activeSub(4L, 100L, 200L, 24, desiredTime, sinceDate, null);

        when(repository.findDueForRun(any())).thenReturn(List.of(sub));
        when(chatRepository.findAllById(any())).thenReturn(List.of(chat(200L, "@boom")));
        when(jobProducer.enqueueSubscription(anyLong(), anyLong(), anyString(), anyString(), anyString(), anyLong()))
                .thenThrow(new RuntimeException("Redis недоступен"));

        scheduler.runDueSubscriptions();

        verify(subscriptionService).recordFailure(4L);
        verify(subscriptionService, never()).recordRunStarted(anyLong());
    }

    @Test
    @DisplayName("isInDesiredWindow: после desired_time окно остаётся открытым до конца суток — catch-up при занятом воркере")
    void isInDesiredWindow_afterDesiredTime_remainsOpen() {
        // desiredTimeMsk = "09:00" МСК, windowStart = 08:30.
        // now = 13:00 МСК — воркер был занят, но окно не должно закрываться +5 мин.
        // isPeriodElapsed гарантирует, что после успешного run подписка не выстрелит повторно.
        String desiredTime = "09:00";
        Instant sinceDate = Instant.now().minusSeconds(25 * 3600L);
        ChatSubscription sub = activeSub(10L, 100L, 200L, 24, desiredTime, sinceDate, null);

        Instant nowMsk13 = ZonedDateTime.now(MSK)
                .withHour(13).withMinute(0).withSecond(0).withNano(0)
                .toInstant();

        boolean inWindow = scheduler.isInDesiredWindow(sub, nowMsk13);

        org.assertj.core.api.Assertions.assertThat(inWindow).isTrue();
    }

    @Test
    @DisplayName("isPeriodElapsed: lastRunAt только что (lastSuccessAt=null) → НЕ истёк — защита от 5-мин цикла")
    void isPeriodElapsed_guardsAgainstReEnqueueByLastRunAt() {
        Instant sinceDate = Instant.now().minusSeconds(25 * 3600L);
        ChatSubscription sub = ChatSubscription.builder()
                .id(11L).botUserId(100L).chatRefId(200L)
                .periodHours(24).desiredTimeMsk("09:00").sinceDate(sinceDate)
                .status(SubscriptionStatus.ACTIVE).consecutiveFailures(0)
                .lastRunAt(Instant.now().minusSeconds(60))   // запустили минуту назад
                .lastSuccessAt(null)                          // success ещё не пришёл
                .lastConfirmAt(Instant.now())
                .createdAt(Instant.now()).updatedAt(Instant.now())
                .build();

        boolean elapsed = scheduler.isPeriodElapsed(sub, Instant.now());

        assertThat(elapsed).isFalse();
    }

    @Test
    @DisplayName("isPeriodElapsed: lastFailureAt только что → НЕ истёк (не ретраим каждые 5 мин)")
    void isPeriodElapsed_guardsByLastFailureAt() {
        Instant sinceDate = Instant.now().minusSeconds(25 * 3600L);
        ChatSubscription sub = ChatSubscription.builder()
                .id(12L).botUserId(100L).chatRefId(200L)
                .periodHours(24).desiredTimeMsk("09:00").sinceDate(sinceDate)
                .status(SubscriptionStatus.ACTIVE).consecutiveFailures(1)
                .lastRunAt(Instant.now().minusSeconds(120))
                .lastFailureAt(Instant.now().minusSeconds(60))
                .lastSuccessAt(null)
                .lastConfirmAt(Instant.now())
                .createdAt(Instant.now()).updatedAt(Instant.now())
                .build();

        boolean elapsed = scheduler.isPeriodElapsed(sub, Instant.now());

        assertThat(elapsed).isFalse();
    }

    @Test
    @DisplayName("runDueSubscriptions: чат не найден в БД → recordFailure вызван, остальные подписки продолжают обрабатываться")
    void recordsFailureWhenChatNotFound() {
        when(jobProducer.hasActiveProcessingJob()).thenReturn(false);
        when(jobProducer.getQueueLength()).thenReturn(0L);

        Instant sinceDate = Instant.now().minusSeconds(25 * 3600L);
        String desiredTime = mskHhMm(10);

        // Первая подписка: чат не найден
        ChatSubscription sub1 = activeSub(5L, 100L, 999L, 24, desiredTime, sinceDate, null);
        // Вторая подписка: чат найден, всё OK
        ChatSubscription sub2 = activeSub(6L, 200L, 300L, 24, desiredTime, sinceDate, null);

        when(repository.findDueForRun(any())).thenReturn(List.of(sub1, sub2));
        // Chat для chatRefId=300 найден, для 999 — нет (batch findAllById возвращает только 300)
        when(chatRepository.findAllById(any())).thenReturn(List.of(chat(300L, "@ok")));
        when(jobProducer.enqueueSubscription(anyLong(), anyLong(), anyString(), anyString(), anyString(), anyLong()))
                .thenReturn("sub_ok");

        scheduler.runDueSubscriptions();

        verify(subscriptionService).recordFailure(5L);
        verify(subscriptionService).recordRunStarted(6L);
    }
}
