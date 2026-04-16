package com.tcleaner.bot;

import java.time.Instant;

/**
 * Состояние диалога пользователя с ботом.
 *
 * <p>Хранится in-memory в {@code ConcurrentHashMap<Long, UserSession>} внутри {@link ExportBot}.
 * Сбрасывается при рестарте — допустимо, пользователь просто начнёт заново.</p>
 *
 * <p>Все методы синхронизированы для обеспечения потокобезопасности при параллельной обработке
 * нескольких обновлений от одного и того же пользователя.</p>
 */
public class UserSession {

    /**
     * Этапы диалога.
     */
    public enum State {
        /** Начальное состояние — ожидание идентификатора чата. */
        IDLE,
        /** Чат идентифицирован — ожидание выбора варианта диапазона (весь чат / указать даты). */
        AWAITING_DATE_CHOICE,
        /** Пользователь выбрал «указать даты» — ожидание ввода начальной даты. */
        AWAITING_FROM_DATE,
        /** Ожидание ввода конечной даты. */
        AWAITING_TO_DATE
    }

    private State state = State.IDLE;
    private String chatId;
    private String chatDisplay;
    private String fromDate;
    private String toDate;
    private Instant lastAccess = Instant.now();

    public synchronized State getState() {
        return state;
    }

    public synchronized void setState(State state) {
        this.state = state;
        touch();
    }

    public synchronized String getChatId() {
        return chatId;
    }

    public synchronized void setChatId(String chatId) {
        this.chatId = chatId;
        touch();
    }

    public synchronized String getChatDisplay() {
        return chatDisplay;
    }

    public synchronized void setChatDisplay(String chatDisplay) {
        this.chatDisplay = chatDisplay;
        touch();
    }

    public synchronized String getFromDate() {
        return fromDate;
    }

    public synchronized void setFromDate(String fromDate) {
        this.fromDate = fromDate;
        touch();
    }

    public synchronized String getToDate() {
        return toDate;
    }

    public synchronized void setToDate(String toDate) {
        this.toDate = toDate;
        touch();
    }

    public synchronized Instant getLastAccess() {
        return lastAccess;
    }

    /** Обновляет метку последнего обращения до текущего момента. */
    public synchronized void touch() {
        this.lastAccess = Instant.now();
    }

    /**
     * Сбрасывает сессию в начальное состояние.
     */
    public synchronized void reset() {
        this.state = State.IDLE;
        this.chatId = null;
        this.chatDisplay = null;
        this.fromDate = null;
        this.toDate = null;
        this.lastAccess = Instant.now();
    }
}
