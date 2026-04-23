package com.tcleaner.dashboard.service.ingestion;

import com.tcleaner.core.BotLanguage;
import com.tcleaner.dashboard.domain.BotUser;
import com.tcleaner.dashboard.repository.BotUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * Idempotent upsert для {@link BotUser} по {@code botUserId}.
 * Первое появление выставляет {@code firstSeen} и инициализирует счётчики нулями;
 * повторное — обновляет {@code username}/{@code displayName}/{@code lastSeen}, не трогая
 * {@code firstSeen} и денормализованные {@code total*}-поля (их правит ingestion-сервис
 * при обработке {@code export.*}-событий).
 */
@Component
public class BotUserUpserter {

    private static final Logger log = LoggerFactory.getLogger(BotUserUpserter.class);

    private final BotUserRepository repository;

    public BotUserUpserter(BotUserRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public BotUser upsert(long botUserId, String username, String displayName, Instant seenAt) {
        Instant ts = seenAt != null ? seenAt : Instant.now();
        BotUser user = repository.findById(botUserId).orElseGet(() -> BotUser.builder()
                .botUserId(botUserId)
                .firstSeen(ts)
                .lastSeen(ts)
                .totalExports(0)
                .totalMessages(0L)
                .totalBytes(0L)
                .build());
        if (username != null && !username.isBlank()) {
            user.setUsername(username);
        }
        if (displayName != null && !displayName.isBlank()) {
            user.setDisplayName(displayName);
        }
        if (user.getLastSeen() == null || ts.isAfter(user.getLastSeen())) {
            user.setLastSeen(ts);
        }
        return repository.save(user);
    }

    /**
     * Язык, ранее выбранный пользователем, или {@link Optional#empty()} если запись
     * ещё не существует либо language == null. Бот использует это для решения
     * "показать клавиатуру выбора языка при /start или сразу HELP".
     */
    @Transactional(readOnly = true)
    public Optional<String> getLanguage(long botUserId) {
        return repository.findById(botUserId)
                .map(BotUser::getLanguage)
                .filter(code -> code != null && !code.isBlank());
    }

    /**
     * Возвращает {@link BotLanguage} пользователя или EN по умолчанию.
     * Централизованный резолв для всех callers чтобы не дублировать fallback-логику.
     */
    @Transactional(readOnly = true)
    public BotLanguage resolveLanguage(long botUserId) {
        return getLanguage(botUserId)
                .flatMap(BotLanguage::fromCode)
                .orElse(BotLanguage.EN);
    }

    /**
     * Синхронно сохраняет выбор языка. Гарантирует существование записи
     * {@link BotUser} — если её нет, создаёт с минимальным набором полей
     * (остальные проставит ближайший {@link #upsert}).
     */
    @Transactional
    public void setLanguage(long botUserId, String code) {
        Instant ts = Instant.now();
        Optional<BotUser> existing = repository.findById(botUserId);
        BotUser user = existing.orElseGet(() -> BotUser.builder()
                .botUserId(botUserId)
                .firstSeen(ts)
                .lastSeen(ts)
                .totalExports(0)
                .totalMessages(0L)
                .totalBytes(0L)
                .build());
        user.setLanguage(code);
        repository.save(user);
        if (existing.isEmpty()) {
            log.info("BotUser создан через setLanguage: botUserId={} code={}", botUserId, code);
        } else {
            log.info("BotUser язык обновлён: botUserId={} code={}", botUserId, code);
        }
    }
}
