package com.tcleaner.dashboard.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Типы событий в Redis-стриме {@code stats:events}.
 * Строковое значение (field {@code "type"}) — то, что ложится в JSON payload;
 * все паблишеры (Java {@code ExportBot}, {@code ExportJobProducer},
 * {@code TelegramController}, Python {@code queue_consumer}) шлют одно из этих значений.
 */
public enum StatsEventType {
    BOT_USER_SEEN("bot_user.seen"),
    EXPORT_STARTED("export.started"),
    EXPORT_COMPLETED("export.completed"),
    EXPORT_FAILED("export.failed"),
    EXPORT_CANCELLED("export.cancelled"),
    EXPORT_BYTES_MEASURED("export.bytes_measured");

    private final String wire;

    StatsEventType(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }

    @JsonCreator
    public static StatsEventType fromWire(String wire) {
        for (StatsEventType type : values()) {
            if (type.wire.equals(wire)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Неизвестный тип события: " + wire);
    }
}
