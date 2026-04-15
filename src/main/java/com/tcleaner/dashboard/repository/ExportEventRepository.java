package com.tcleaner.dashboard.repository;

import com.tcleaner.dashboard.domain.ExportEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * JPA-репозиторий для {@link ExportEvent}.
 * Поиск по {@code taskId} — ключ идемпотентного upsert из
 * ingestion-сервиса (см. docs/DASHBOARD.md). Агрегации overview/users/chats
 * будут добавлены отдельным PR (native SQL + strftime-buckets).
 */
@Repository
public interface ExportEventRepository extends JpaRepository<ExportEvent, Long> {

    Optional<ExportEvent> findByTaskId(String taskId);
}
