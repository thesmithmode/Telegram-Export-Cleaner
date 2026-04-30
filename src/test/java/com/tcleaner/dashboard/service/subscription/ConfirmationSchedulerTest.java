package com.tcleaner.dashboard.service.subscription;

import com.tcleaner.bot.BotI18n;
import com.tcleaner.bot.BotKeyboards;
import com.tcleaner.bot.BotMessenger;
import com.tcleaner.core.BotLanguage;
import com.tcleaner.dashboard.domain.ChatSubscription;
import com.tcleaner.dashboard.repository.ChatSubscriptionRepository;
import com.tcleaner.dashboard.service.ingestion.BotUserUpserter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ConfirmationScheduler")
class ConfirmationSchedulerTest {

    private static final long BOT_USER_ID_1 = 101L;
    private static final long BOT_USER_ID_2 = 102L;
    private static final long SUB_ID_1 = 1L;
    private static final long SUB_ID_2 = 2L;

    @Mock private ChatSubscriptionRepository repository;
    @Mock private SubscriptionService subscriptionService;
    @Mock private BotMessenger messenger;
    @Mock private BotI18n i18n;
    @Mock private BotKeyboards keyboards;
    @Mock private BotUserUpserter userUpserter;

    private ConfirmationScheduler scheduler;

    @BeforeEach
    void initScheduler() {
        scheduler = new ConfirmationScheduler(repository, subscriptionService, messenger,
                i18n, keyboards, userUpserter, new SimpleMeterRegistry());
    }

    // ------------------------------------------------------------------ helpers

    private static ChatSubscription sub(long id, long botUserId) {
        return ChatSubscription.builder()
                .id(id)
                .botUserId(botUserId)
                .build();
    }

    // ------------------------------------------------------------------ tests

    @Test
    @DisplayName("tick: пустые выборки репозитория → никаких вызовов messenger")
    void tick_emptyRepositoryResults_noMessengerCalls() {
        when(repository.findDueForConfirmation(any())).thenReturn(List.of());
        when(repository.findDueForArchive(any())).thenReturn(List.of());

        scheduler.tick();

        verify(messenger, never()).sendWithKeyboard(anyLong(), anyString(), any());
        verify(messenger, never()).trySend(anyLong(), anyString());
        verify(subscriptionService, never()).markConfirmSent(anyLong());
        verify(subscriptionService, never()).archive(anyLong());
    }

    @Test
    @DisplayName("sendConfirmationPrompts: для найденных подписок отправляет сообщение + markConfirmSent")
    void sendConfirmationPrompts_foundSubscriptions_sendsMessageAndMarksConfirmSent() {
        ChatSubscription sub1 = sub(SUB_ID_1, BOT_USER_ID_1);
        ChatSubscription sub2 = sub(SUB_ID_2, BOT_USER_ID_2);
        when(repository.findDueForConfirmation(any())).thenReturn(List.of(sub1, sub2));

        InlineKeyboardMarkup kb = mock(InlineKeyboardMarkup.class);
        when(userUpserter.resolveLanguage(BOT_USER_ID_1)).thenReturn(BotLanguage.RU);
        when(userUpserter.resolveLanguage(BOT_USER_ID_2)).thenReturn(BotLanguage.RU);
        when(keyboards.subConfirmKeyboard(eq(BotLanguage.RU), eq(SUB_ID_1))).thenReturn(kb);
        when(keyboards.subConfirmKeyboard(eq(BotLanguage.RU), eq(SUB_ID_2))).thenReturn(kb);
        when(i18n.msg(eq(BotLanguage.RU), eq("bot.sub.confirm.request"))).thenReturn("Подтвердите подписку");

        scheduler.sendConfirmationPrompts(Instant.now());

        verify(messenger).sendWithKeyboard(BOT_USER_ID_1, "Подтвердите подписку", kb);
        verify(messenger).sendWithKeyboard(BOT_USER_ID_2, "Подтвердите подписку", kb);
        verify(subscriptionService).markConfirmSent(SUB_ID_1);
        verify(subscriptionService).markConfirmSent(SUB_ID_2);
    }

    @Test
    @DisplayName("sendConfirmationPrompts: exception в messenger → markConfirmSent ВСЁ РАВНО вызван (защита от infinite loop у заблокированных пользователей)")
    void sendConfirmationPrompts_messengerThrows_markConfirmSentStillCalled() {
        ChatSubscription sub1 = sub(SUB_ID_1, BOT_USER_ID_1);
        ChatSubscription sub2 = sub(SUB_ID_2, BOT_USER_ID_2);
        when(repository.findDueForConfirmation(any())).thenReturn(List.of(sub1, sub2));

        InlineKeyboardMarkup kb = mock(InlineKeyboardMarkup.class);
        when(userUpserter.resolveLanguage(BOT_USER_ID_1)).thenReturn(BotLanguage.RU);
        when(userUpserter.resolveLanguage(BOT_USER_ID_2)).thenReturn(BotLanguage.RU);
        when(keyboards.subConfirmKeyboard(eq(BotLanguage.RU), eq(SUB_ID_1))).thenReturn(kb);
        when(keyboards.subConfirmKeyboard(eq(BotLanguage.RU), eq(SUB_ID_2))).thenReturn(kb);
        when(i18n.msg(eq(BotLanguage.RU), eq("bot.sub.confirm.request"))).thenReturn("Подтвердите подписку");

        doThrow(new RuntimeException("boom"))
                .when(messenger).sendWithKeyboard(eq(BOT_USER_ID_1), anyString(), any());

        scheduler.sendConfirmationPrompts(Instant.now());

        // markConfirmSent вызывается ДО отправки — оба sub помечены (иначе заблокированный юзер
        // висел бы вечно в due-списке). Через 48 часов без ответа ARCHIVED естественным timeout.
        verify(subscriptionService).markConfirmSent(SUB_ID_1);
        verify(subscriptionService).markConfirmSent(SUB_ID_2);
        // sub2: обработана успешно (отправка не упала)
        verify(messenger).sendWithKeyboard(BOT_USER_ID_2, "Подтвердите подписку", kb);
    }

    @Test
    @DisplayName("archiveUnconfirmed: archive + уведомление пользователя")
    void archiveUnconfirmed_foundSubscriptions_archivesAndNotifiesUser() {
        ChatSubscription sub1 = sub(SUB_ID_1, BOT_USER_ID_1);
        when(repository.findDueForArchive(any())).thenReturn(List.of(sub1));

        when(userUpserter.resolveLanguage(BOT_USER_ID_1)).thenReturn(BotLanguage.RU);
        when(i18n.msg(eq(BotLanguage.RU), eq("bot.sub.archived"))).thenReturn("Подписка архивирована");

        scheduler.archiveUnconfirmed(Instant.now());

        verify(subscriptionService).archive(SUB_ID_1);
        verify(messenger).trySend(BOT_USER_ID_1, "Подписка архивирована");
    }

    @Test
    @DisplayName("archiveUnconfirmed: exception в archive → уведомление НЕ шлётся, но процесс продолжается")
    void archiveUnconfirmed_archiveThrows_notificationNotSentButContinues() {
        ChatSubscription sub1 = sub(SUB_ID_1, BOT_USER_ID_1);
        ChatSubscription sub2 = sub(SUB_ID_2, BOT_USER_ID_2);
        when(repository.findDueForArchive(any())).thenReturn(List.of(sub1, sub2));

        doThrow(new RuntimeException("db error"))
                .when(subscriptionService).archive(SUB_ID_1);

        when(userUpserter.resolveLanguage(BOT_USER_ID_2)).thenReturn(BotLanguage.RU);
        when(i18n.msg(eq(BotLanguage.RU), eq("bot.sub.archived"))).thenReturn("Подписка архивирована");

        scheduler.archiveUnconfirmed(Instant.now());

        // sub1: archive бросил исключение — уведомление не должно быть отправлено
        verify(messenger, never()).trySend(eq(BOT_USER_ID_1), anyString());
        // sub2: обработана успешно
        verify(subscriptionService).archive(SUB_ID_2);
        verify(messenger).trySend(BOT_USER_ID_2, "Подписка архивирована");
    }

    @Test
    @DisplayName("resolveLang: если юзер выбрал ru → возвращает BotLanguage.RU; если не выбран → EN fallback")
    void resolveLang_ruLanguageSelected_returnsBotLanguageRu_andFallbackToEnWhenAbsent() {
        ChatSubscription subWithRu = sub(SUB_ID_1, BOT_USER_ID_1);
        ChatSubscription subWithNoLang = sub(SUB_ID_2, BOT_USER_ID_2);
        when(repository.findDueForConfirmation(any())).thenReturn(List.of(subWithRu, subWithNoLang));

        InlineKeyboardMarkup kb = mock(InlineKeyboardMarkup.class);
        when(userUpserter.resolveLanguage(BOT_USER_ID_1)).thenReturn(BotLanguage.RU);
        when(userUpserter.resolveLanguage(BOT_USER_ID_2)).thenReturn(BotLanguage.EN);
        when(keyboards.subConfirmKeyboard(eq(BotLanguage.RU), eq(SUB_ID_1))).thenReturn(kb);
        when(keyboards.subConfirmKeyboard(eq(BotLanguage.EN), eq(SUB_ID_2))).thenReturn(kb);
        when(i18n.msg(eq(BotLanguage.RU), eq("bot.sub.confirm.request"))).thenReturn("ru-text");
        when(i18n.msg(eq(BotLanguage.EN), eq("bot.sub.confirm.request"))).thenReturn("en-text");

        scheduler.sendConfirmationPrompts(Instant.now());

        // Для sub1 использован RU
        verify(keyboards).subConfirmKeyboard(BotLanguage.RU, SUB_ID_1);
        verify(messenger).sendWithKeyboard(BOT_USER_ID_1, "ru-text", kb);
        // Для sub2 использован EN (fallback)
        verify(keyboards).subConfirmKeyboard(BotLanguage.EN, SUB_ID_2);
        verify(messenger).sendWithKeyboard(BOT_USER_ID_2, "en-text", kb);
    }
}
