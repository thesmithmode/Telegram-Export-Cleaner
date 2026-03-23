package com.tcleaner.bot;

/**
 * Состояние диалога пользователя с ботом.
 *
 * <p>Хранится in-memory в {@code ConcurrentHashMap<Long, UserSession>} внутри {@link ExportBot}.
 * Сбрасывается при рестарте — допустимо, пользователь просто начнёт заново.</p>
 */
public class UserSession {

    /**
     * Этапы wizard-диалога.
     */
    public enum State {
        /** Начальное состояние — ожидание идентификатора чата. */
        IDLE,
        /** Чат выбран — ожидание выбора: "Весь чат" или "Указать диапазон". */
        AWAITING_DATE_CHOICE,
        /** Ожидание ввода начальной даты. */
        AWAITING_FROM_DATE,
        /** Ожидание ввода конечной даты. */
        AWAITING_TO_DATE
    }

    private State state = State.IDLE;
    private Object chatId;
    private String chatDisplay;
    private String fromDate;
    private String toDate;

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
     * Сбрасывает сессию в начальное состояние.
     */
    public void reset() {
        this.state = State.IDLE;
        this.chatId = null;
        this.chatDisplay = null;
        this.fromDate = null;
        this.toDate = null;
    }
}
