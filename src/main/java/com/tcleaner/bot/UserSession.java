package com.tcleaner.bot;

import java.time.Instant;

public class UserSession {

    public enum State {
        IDLE,
        AWAITING_DATE_CHOICE,
        AWAITING_FROM_DATE,
        AWAITING_TO_DATE
    }

    private State state = State.IDLE;
    private String chatId;
    private String chatDisplay;
    private Integer topicId;
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

    public synchronized Integer getTopicId() {
        return topicId;
    }

    public synchronized void setTopicId(Integer topicId) {
        this.topicId = topicId;
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

    public synchronized void touch() {
        this.lastAccess = Instant.now();
    }

    public synchronized void reset() {
        this.state = State.IDLE;
        this.chatId = null;
        this.chatDisplay = null;
        this.topicId = null;
        this.fromDate = null;
        this.toDate = null;
        this.lastAccess = Instant.now();
    }
}
