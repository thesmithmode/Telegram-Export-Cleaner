package com.tcleaner.dashboard.domain;

/**
 * Статус экспорта в таблице {@code export_events}.
 * Значения сериализуются в TEXT-колонку как есть (EnumType.STRING).
 */
public enum ExportStatus {
    QUEUED,
    PROCESSING,
    COMPLETED,
    FAILED,
    CANCELLED
}
