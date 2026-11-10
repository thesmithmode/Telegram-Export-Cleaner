package com.tcleaner.dashboard.repository;

import com.tcleaner.dashboard.domain.BotUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * JPA-репозиторий для {@link BotUser}. Агрегации "top users" живут в
 * {@code ExportEventRepository} — здесь только CRUD + lookup по username.
 */
@Repository
public interface BotUserRepository extends JpaRepository<BotUser, Long> {

    Optional<BotUser> findByUsername(String username);

    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("UPDATE BotUser u SET u.feedbackBlocked = :blocked WHERE u.botUserId = :id")
    int setFeedbackBlocked(@Param("id") long id, @Param("blocked") boolean blocked);
}
