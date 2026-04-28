package com.tcleaner.dashboard.repository;

import com.tcleaner.dashboard.domain.DashboardRole;
import com.tcleaner.dashboard.domain.DashboardUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DashboardUserRepository extends JpaRepository<DashboardUser, Long> {

    Optional<DashboardUser> findByUsername(String username);

    Optional<DashboardUser> findByTelegramId(Long telegramId);

    List<DashboardUser> findAllByRole(DashboardRole role);

}
