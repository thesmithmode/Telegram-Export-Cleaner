package com.tcleaner.dashboard.service;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.tcleaner.bot.BotMessenger;
import com.tcleaner.dashboard.domain.BotUser;
import com.tcleaner.dashboard.repository.BotUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static com.tcleaner.dashboard.config.CacheConfig.FEEDBACK_RATE_LIMIT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("FeedbackService")
class FeedbackServiceTest {

    private static final long ADMIN_TG_ID = 1000L;
    private static final long USER_TG_ID = 42L;

    @Mock private BotMessenger botMessenger;
    @Mock private BotUserRepository botUsers;

    private CacheManager cacheManager;
    private FeedbackService service;
    private final java.util.concurrent.atomic.AtomicLong ticker = new java.util.concurrent.atomic.AtomicLong();

    private static BotUser user(long id, String username, String displayName) {
        return BotUser.builder()
                .botUserId(id).username(username).displayName(displayName)
                .firstSeen(Instant.now()).lastSeen(Instant.now())
                .totalExports(0).totalMessages(0L).totalBytes(0L).build();
    }

    @BeforeEach
    void init() {
        CaffeineCacheManager mgr = new CaffeineCacheManager();
        mgr.registerCustomCache(FEEDBACK_RATE_LIMIT,
                Caffeine.newBuilder().ticker(ticker::get)
                        .expireAfterWrite(5, TimeUnit.MINUTES).maximumSize(100).build());
        this.cacheManager = mgr;
        this.service = new FeedbackService(botMessenger, botUsers, cacheManager, ADMIN_TG_ID);
    }

    @Test
    @DisplayName("отправляет сформированное сообщение админу и возвращает SENT")
    void sendsMessageToAdmin() {
        when(botUsers.findById(USER_TG_ID)).thenReturn(Optional.of(user(USER_TG_ID, "alice", "Alice Doe")));
        when(botMessenger.trySendWithKeyboard(anyLong(), anyString(), any())).thenReturn(true);

        FeedbackService.Result result = service.submit(USER_TG_ID, "Привет!");

        assertThat(result).isEqualTo(FeedbackService.Result.SENT);
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(botMessenger).trySendWithKeyboard(eq(ADMIN_TG_ID), text.capture(), any());
        assertThat(text.getValue())
                .contains("@alice")
                .contains(String.valueOf(USER_TG_ID))
                .contains("Alice Doe")
                .contains("Привет!");
    }

    @Test
    @DisplayName("без username в BotUser → (no username)")
    void fallbackWhenNoUsername() {
        when(botUsers.findById(USER_TG_ID)).thenReturn(Optional.of(user(USER_TG_ID, null, "Alice")));
        when(botMessenger.trySendWithKeyboard(anyLong(), anyString(), any())).thenReturn(true);

        service.submit(USER_TG_ID, "hi");

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(botMessenger).trySendWithKeyboard(eq(ADMIN_TG_ID), text.capture(), any());
        assertThat(text.getValue()).contains("(no username)");
    }

    @Test
    @DisplayName("BotUser не найден → всё равно шлёт с fallback метаданными")
    void missingBotUserDoesNotCrash() {
        when(botUsers.findById(USER_TG_ID)).thenReturn(Optional.empty());
        when(botMessenger.trySendWithKeyboard(anyLong(), anyString(), any())).thenReturn(true);

        FeedbackService.Result r = service.submit(USER_TG_ID, "msg");

        assertThat(r).isEqualTo(FeedbackService.Result.SENT);
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(botMessenger).trySendWithKeyboard(eq(ADMIN_TG_ID), text.capture(), any());
        assertThat(text.getValue()).contains("(no username)").contains("msg");
    }

    @Test
    @DisplayName("повторная отправка в пределах TTL → RATE_LIMITED")
    void rateLimited() {
        when(botUsers.findById(USER_TG_ID)).thenReturn(Optional.of(user(USER_TG_ID, "a", "A")));
        when(botMessenger.trySendWithKeyboard(anyLong(), anyString(), any())).thenReturn(true);

        FeedbackService.Result first = service.submit(USER_TG_ID, "first");
        FeedbackService.Result second = service.submit(USER_TG_ID, "second");

        assertThat(first).isEqualTo(FeedbackService.Result.SENT);
        assertThat(second).isEqualTo(FeedbackService.Result.RATE_LIMITED);
        verify(botMessenger, times(1)).trySendWithKeyboard(anyLong(), anyString(), any());
    }

    @Test
    void limitExpiresAfterFiveMinutes() {
        when(botUsers.findById(USER_TG_ID)).thenReturn(Optional.empty());
        when(botMessenger.trySendWithKeyboard(anyLong(), anyString(), any())).thenReturn(true);
        assertThat(service.submit(USER_TG_ID, "first")).isEqualTo(FeedbackService.Result.SENT);
        ticker.addAndGet(TimeUnit.MINUTES.toNanos(5) - 1);
        assertThat(service.submit(USER_TG_ID, "early")).isEqualTo(FeedbackService.Result.RATE_LIMITED);
        ticker.incrementAndGet();
        assertThat(service.submit(USER_TG_ID, "next")).isEqualTo(FeedbackService.Result.SENT);
    }

    @Test
    void productionLimiterUsesFiveMinuteWindow() {
        var manager = new com.tcleaner.dashboard.config.CacheConfig().cacheManager();
        var cache = (org.springframework.cache.caffeine.CaffeineCache) manager.getCache(FEEDBACK_RATE_LIMIT);
        assertThat(cache.getNativeCache().policy().expireAfterWrite().orElseThrow()
                .getExpiresAfter(TimeUnit.SECONDS)).isEqualTo(300);
    }

    @Test
    void notificationIncludesBothActionsForTheSender() {
        var keyboard = FeedbackService.moderationKeyboard(USER_TG_ID);
        assertThat(keyboard.getKeyboard().get(0).get(0).getCallbackData()).isEqualTo("feedback:block:42");
        assertThat(keyboard.getKeyboard().get(1).get(0).getCallbackData()).isEqualTo("feedback:unblock:42");
    }

    @Test
    void blockedUserCannotSendEvenWithFreshLimiter() {
        BotUser blocked = user(USER_TG_ID, "alice", "Alice");
        blocked.setFeedbackBlocked(true);
        when(botUsers.findById(USER_TG_ID)).thenReturn(Optional.of(blocked));
        assertThat(service.submit(USER_TG_ID, "spam")).isEqualTo(FeedbackService.Result.BLOCKED);
        verify(botMessenger, never()).trySendWithKeyboard(anyLong(), anyString(), any());
    }

    @Test
    void onlyAdminInPrivateChatCanModerate() {
        assertThat(service.moderate(USER_TG_ID, USER_TG_ID, 43L, true)).isFalse();
        assertThat(service.moderate(ADMIN_TG_ID, -100L, USER_TG_ID, true)).isFalse();
        assertThat(service.moderate(ADMIN_TG_ID, ADMIN_TG_ID, ADMIN_TG_ID, true)).isFalse();
        assertThat(service.moderate(ADMIN_TG_ID, ADMIN_TG_ID, -1L, true)).isFalse();
        verify(botUsers, never()).setFeedbackBlocked(anyLong(), org.mockito.ArgumentMatchers.anyBoolean());
        when(botUsers.setFeedbackBlocked(USER_TG_ID, true)).thenReturn(1);
        when(botUsers.setFeedbackBlocked(USER_TG_ID, false)).thenReturn(1);
        assertThat(service.moderate(ADMIN_TG_ID, ADMIN_TG_ID, USER_TG_ID, true)).isTrue();
        assertThat(service.moderate(ADMIN_TG_ID, ADMIN_TG_ID, USER_TG_ID, false)).isTrue();
    }

    @Test
    @DisplayName("разные пользователи — независимые лимиты")
    void perUserLimit() {
        when(botUsers.findById(any())).thenReturn(Optional.of(user(0L, "x", "X")));
        when(botMessenger.trySendWithKeyboard(anyLong(), anyString(), any())).thenReturn(true);

        service.submit(42L, "m1");
        FeedbackService.Result other = service.submit(43L, "m2");

        assertThat(other).isEqualTo(FeedbackService.Result.SENT);
        verify(botMessenger, times(2)).trySendWithKeyboard(anyLong(), anyString(), any());
    }

    @Test
    @DisplayName("trySend → false → SEND_FAILED, лимит не расходуется")
    void sendFailureDoesNotConsumeLimit() {
        when(botUsers.findById(USER_TG_ID)).thenReturn(Optional.of(user(USER_TG_ID, "a", "A")));
        when(botMessenger.trySendWithKeyboard(anyLong(), anyString(), any())).thenReturn(false);

        FeedbackService.Result first = service.submit(USER_TG_ID, "m");
        assertThat(first).isEqualTo(FeedbackService.Result.SEND_FAILED);

        when(botMessenger.trySendWithKeyboard(anyLong(), anyString(), any())).thenReturn(true);
        FeedbackService.Result second = service.submit(USER_TG_ID, "m");
        assertThat(second).isEqualTo(FeedbackService.Result.SENT);
    }

    @Test
    @DisplayName("null rawMessage → NPE (защита от обхода контроллерной валидации)")
    void nullMessageThrows() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> service.submit(USER_TG_ID, null))
                .isInstanceOf(NullPointerException.class);
        verify(botMessenger, never()).trySendWithKeyboard(anyLong(), anyString(), any());
    }

    @Test
    @DisplayName("submit от самого админа → FORBIDDEN, BotMessenger не вызван")
    void adminCannotSendToSelf() {
        FeedbackService.Result result = service.submit(ADMIN_TG_ID, "m");

        assertThat(result).isEqualTo(FeedbackService.Result.FORBIDDEN);
        verify(botMessenger, never()).trySendWithKeyboard(anyLong(), anyString(), any());
    }

    @Test
    @DisplayName("сообщение обрезается по пробелам")
    void trimsMessage() {
        when(botUsers.findById(USER_TG_ID)).thenReturn(Optional.of(user(USER_TG_ID, "a", "A")));
        when(botMessenger.trySendWithKeyboard(anyLong(), anyString(), any())).thenReturn(true);

        service.submit(USER_TG_ID, "   hi   ");

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(botMessenger).trySendWithKeyboard(eq(ADMIN_TG_ID), text.capture(), any());
        assertThat(text.getValue()).endsWith("hi");
    }
}
