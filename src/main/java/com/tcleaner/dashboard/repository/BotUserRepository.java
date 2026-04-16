package com.tcleaner.dashboard.repository;

import com.tcleaner.dashboard.domain.BotUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * JPA-репозиторий для {@link BotUser}. Агрегации "top users" живут в
 * {@code ExportEventRepository} — здесь только CRUD + lookup по username.
 */
@Repository
public interface BotUserRepository extends JpaRepository<BotUser, Long> {

    Optional<BotUser> findByUsername(String username);
}
