package com.tcleaner.bot;

import java.time.Instant;

/**
 * Состояние диалога пользователя с ботом.
 *
 * <p>Хранится in-memory в {@code ConcurrentHashMap<Long, UserSession>} внутри {@link ExportBot}.
 * Сбрасывается при рестарте — допустимо, пользователь просто начнёт заново.</p>
 *
 * <p>Содержит временну́ю метку последнего обращения ({@link #getLastAccess()}) для
 * периодического вытеснения неактивных сессий и предотвращения утечки памяти.</p>
 */
public class UserSession {

    /**
     * Этапы диалога.
     */
    public enum State {
        /** Начальное состояние — ожидание идентификатора чата. */
        IDLE,
        /** Чат выбран — ожидание ввода начальной даты. */
        AWAITING_FROM_DATE,
        /** Ожидание ввода конечной даты. */
        AWAITING_TO_DATE
    }

    private volatile State state = State.IDLE;
    private volatile Object chatId;
    private volatile String chatDisplay;
    private volatile String fromDate;
    private volatile String toDate;
    private volatile Instant lastAccess = Instant.now();

    public State getState() {
        return state;
    }

    public void setState(State state) {
        this.state = state;
    }

    public Object getChatId() {
        return chatId;
    }

    public void setChatId(Object chatId) {
        this.chatId = chatId;
    }

    public String getChatDisplay() {
        return chatDisplay;
    }

    public void setChatDisplay(String chatDisplay) {
        this.chatDisplay = chatDisplay;
    }

    public String getFromDate() {
        return fromDate;
    }

    public void setFromDate(String fromDate) {
        this.fromDate = fromDate;
    }

    public String getToDate() {
        return toDate;
    }

    public void setToDate(String toDate) {
        this.toDate = toDate;
    }

    /**
     * Возвращает метку времени последнего обращения к сессии.
     *
     * @return {@link Instant} последнего обращения
     */
    public Instant getLastAccess() {
        return lastAccess;
    }

    /** Обновляет метку последнего обращения до текущего момента. */
    public void touch() {
        this.lastAccess = Instant.now();
    }

    /**
     * Сбрасывает сессию в начальное состояние.
     */
    public void reset() {
        this.state = State.IDLE;
        this.chatId = null;
        this.chatDisplay = null;
        this.fromDate = null;
        this.toDate = null;
        this.lastAccess = Instant.now();
    }
}
