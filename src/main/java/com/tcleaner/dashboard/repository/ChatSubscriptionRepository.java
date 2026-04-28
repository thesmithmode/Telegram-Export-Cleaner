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

@Repository
public interface ChatSubscriptionRepository extends JpaRepository<ChatSubscription, Long> {

    Optional<ChatSubscription> findByBotUserIdAndStatus(Long botUserId, SubscriptionStatus status);

    List<ChatSubscription> findAllByBotUserId(Long botUserId);

    List<ChatSubscription> findAllByStatus(SubscriptionStatus status);

    @Query("SELECT s FROM ChatSubscription s "
            + "WHERE s.status = com.tcleaner.dashboard.domain.SubscriptionStatus.ACTIVE "
            + "AND (s.lastSuccessAt IS NULL OR s.lastSuccessAt < :cutoffTs)")
    List<ChatSubscription> findDueForRun(@Param("cutoffTs") Instant cutoffTs);

    @Query("SELECT s FROM ChatSubscription s "
            + "WHERE s.status = com.tcleaner.dashboard.domain.SubscriptionStatus.ACTIVE "
            + "AND s.lastConfirmAt < :confirmCutoff AND s.confirmSentAt IS NULL")
    List<ChatSubscription> findDueForConfirmation(@Param("confirmCutoff") Instant confirmCutoff);

    @Query("SELECT s FROM ChatSubscription s "
            + "WHERE s.status = com.tcleaner.dashboard.domain.SubscriptionStatus.ACTIVE "
            + "AND s.confirmSentAt IS NOT NULL AND s.confirmSentAt < :archiveCutoff")
    List<ChatSubscription> findDueForArchive(@Param("archiveCutoff") Instant archiveCutoff);
}
