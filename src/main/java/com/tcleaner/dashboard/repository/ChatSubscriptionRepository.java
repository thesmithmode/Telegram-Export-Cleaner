package com.tcleaner.dashboard.repository;

import com.tcleaner.dashboard.domain.ChatSubscription;
import com.tcleaner.dashboard.domain.SubscriptionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * JPA-репозиторий для {@link ChatSubscription}.
 * Derived-query методы покрывают CRUD операции дашборда и планировщиков.
 */
@Repository
public interface ChatSubscriptionRepository extends JpaRepository<ChatSubscription, Long> {

    /**
     * Проверка уникальности ACTIVE-подписки перед созданием новой.
     */
    Optional<ChatSubscription> findByBotUserIdAndStatus(Long botUserId, SubscriptionStatus status);

    /**
     * Все подписки пользователя для отображения на дашборде (USER-роль).
     */
    List<ChatSubscription> findAllByBotUserId(Long botUserId);

    /**
     * Все подписки с заданным статусом — для ADMIN-просмотра и schedulers.
     */
    List<ChatSubscription> findAllByStatus(SubscriptionStatus status);

    /**
     * ACTIVE-подписки, готовые к запуску очередной итерации экспорта.
     */
    @Query("SELECT s FROM ChatSubscription s "
            + "WHERE s.status = com.tcleaner.dashboard.domain.SubscriptionStatus.ACTIVE "
            + "AND (s.lastSuccessAt IS NULL OR s.lastSuccessAt < :cutoffTs)")
    List<ChatSubscription> findDueForRun(@Param("cutoffTs") Instant cutoffTs);

    /**
     * ACTIVE-подписки, которым пора отправить запрос подтверждения.
     */
    @Query("SELECT s FROM ChatSubscription s "
            + "WHERE s.status = com.tcleaner.dashboard.domain.SubscriptionStatus.ACTIVE "
            + "AND s.lastConfirmAt < :confirmCutoff AND s.confirmSentAt IS NULL")
    List<ChatSubscription> findDueForConfirmation(@Param("confirmCutoff") Instant confirmCutoff);

    /**
     * ACTIVE-подписки, просроченные по подтверждению.
     */
    @Query("SELECT s FROM ChatSubscription s "
            + "WHERE s.status = com.tcleaner.dashboard.domain.SubscriptionStatus.ACTIVE "
            + "AND s.confirmSentAt IS NOT NULL AND s.confirmSentAt < :archiveCutoff")
    List<ChatSubscription> findDueForArchive(@Param("archiveCutoff") Instant archiveCutoff);
}
