package com.tcleaner.bot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("UserSession")
class UserSessionTest {

    @Test
    @DisplayName("topicId по умолчанию null")
    void topicIdDefaultsToNull() {
        UserSession session = new UserSession();
        assertThat(session.getTopicId()).isNull();
    }

    @Test
    @DisplayName("setTopicId / getTopicId работают")
    void setAndGetTopicId() {
        UserSession session = new UserSession();
        session.setTopicId(148220);
        assertThat(session.getTopicId()).isEqualTo(148220);
    }

    @Test
    @DisplayName("setTopicId(null) обнуляет значение")
    void setTopicIdToNull() {
        UserSession session = new UserSession();
        session.setTopicId(123);
        session.setTopicId(null);
        assertThat(session.getTopicId()).isNull();
    }

    @Test
    @DisplayName("reset() обнуляет topicId")
    void resetClearsTopicId() {
        UserSession session = new UserSession();
        session.setTopicId(999);
        session.reset();
        assertThat(session.getTopicId()).isNull();
    }

    @Test
    @DisplayName("setTopicId() обновляет lastAccess")
    void setTopicIdTouchesLastAccess() {
        UserSession session = new UserSession();
        var before = session.getLastAccess();
        session.setTopicId(456);
        assertThat(session.getLastAccess()).isAfterOrEqualTo(before);
    }
}
