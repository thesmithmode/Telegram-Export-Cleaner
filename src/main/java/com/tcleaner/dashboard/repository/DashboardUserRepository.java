package com.tcleaner.dashboard.repository;

import com.tcleaner.dashboard.domain.DashboardUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * JPA-репозиторий для {@link DashboardUser}.
 * {@link #findByUsername} нужен Spring Security's UserDetailsService
 * (см. будущий {@code DashboardUserDetailsService}).
 */
@Repository
public interface DashboardUserRepository extends JpaRepository<DashboardUser, Long> {

    Optional<DashboardUser> findByUsername(String username);
}
